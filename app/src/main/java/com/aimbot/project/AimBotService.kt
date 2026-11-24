package com.aimbot.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.media.projection.MediaProjectionManager
import kotlin.math.hypot
import java.util.concurrent.Executors

class AimBotService : Service() {

    companion object { private const val TAG = "AimBot" }

    private var config: AimConfig? = null
    private var prediction: PredictionEngine? = null
    private var lockManager: LockManager? = null
    private var sniper: SniperManager? = null
    private var antiCheat: AntiCheat? = null
    private var detector: DetectionEngine? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false
    private var projectionStarted = false

    var crosshairX = 0f
    var crosshairY = 0f
    var enemyX = 0f
    var enemyY = 0f
    var enemyVisible = false
    var enemyDead = false
    var enemyId = -1

    override fun onCreate() {
        super.onCreate()
        val dm = resources.displayMetrics
        crosshairX = dm.widthPixels / 2f
        crosshairY = dm.heightPixels / 2f
        loadConfig()
    }

    private fun loadConfig() {
        config = try {
            ConfigReader.load(this)
        } catch (e: Exception) {
            Log.e(TAG, "Config load failed: ${e.message}")
            null
        }
        config?.let { c ->
            prediction = PredictionEngine(
                c.tracking.velocityWindow, c.tracking.predictMaxMs,
                c.tracking.predictSmooth, c.tracking.sprintMultiplier,
                c.tracking.fly3DPredict, c.tracking.airControlCompensation,
                c.tracking.stopDecayMs, c.tracking.minVelocityToTrack
            )
            lockManager = LockManager(c.lock)
            sniper = SniperManager(c.sniper)
            antiCheat = AntiCheat(c.anticheat)
        }
    }

    fun start() {
        if (running) return
        if (!ShizukuInjector.init()) {
            Log.e(TAG, "Shizuku not running! Touches will not work.")
        }
        running = true
        val rate = (config?.general?.updateRateMs ?: 16).toLong()

        executor.execute {
            while (running) {
                try { tick() } catch (e: Exception) { Log.w(TAG, "tick: ${e.message}") }
                try { Thread.sleep(rate) } catch (_: InterruptedException) { return@execute }
            }
        }
        Log.i(TAG, "AimBot loop started @ ${rate}ms")
    }

    fun stop() { running = false }

    private fun tick() {
        val c = config ?: return
        val pred = prediction ?: return
        val lock = lockManager ?: return
        val snp = sniper ?: return
        val ac = antiCheat ?: return

        // --- Read live detection (color scanner) ---
        enemyX = SharedState.enemyX
        enemyY = SharedState.enemyY
        enemyVisible = SharedState.enemyVisible
        enemyDead = false

        // Crosshair is always the physical screen center
        val dm = resources.displayMetrics
        crosshairX = dm.widthPixels / 2f
        crosshairY = dm.heightPixels / 2f

        val now = SystemClock.uptimeMillis()
        snp.update(now)

        if (enemyVisible && !enemyDead) pred.addSample(enemyX, enemyY, 0f, now) else pred.clear()

        val predicted = if (c.tracking.predictEnabled && c.tracking.enabled) pred.predict() else null
        val targetX = predicted?.x ?: enemyX
        val targetY = predicted?.y ?: enemyY

        val dist = hypot(targetX - crosshairX, targetY - crosshairY)
        if (dist > c.fov.maxDistancePx) return
        if (c.fov.visibilityCheck && !enemyVisible && !lock.isLocked()) return

        lock.update(enemyId, dist, enemyVisible, enemyDead, now)
        if (!lock.isLocked() && !lock.isAcquiring()) return

        val smooth = if (snp.isScoped()) snp.effectiveSniperParams(dist).second
                     else c.tracking.smoothFactor

        var dx = (targetX - crosshairX) * smooth
        var dy = (targetY - crosshairY) * smooth

        if (c.tracking.reverseCorrection && dist > c.tracking.reverseMaxOffsetPx) {
            val pullBack = (dist - c.tracking.reverseMaxOffsetPx) * 0.5f
            dx -= (dx / dist) * pullBack
            dy -= (dy / dist) * pullBack
        }

        val maxPerFrame = c.tracking.maxCorrectionPerFramePx.toFloat()
        val mag = hypot(dx, dy)
        if (mag > maxPerFrame) { dx *= maxPerFrame / mag; dy *= maxPerFrame / mag }

        if (snp.isRecoilLocking()) { dx *= 0.5f; dy *= 0.5f }

        val humanized = ac.humanize(dx, dy)
        if (humanized.isEmpty()) {
            val (wx, wy) = ac.idleWiggle()
            if (wx != 0f || wy != 0f) injectMove(wx, wy, now)
        } else {
            for ((mx, my) in humanized) {
                injectMove(mx, my, now)
                try { Thread.sleep(ac.nextDelay()) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun injectMove(dx: Float, dy: Float, baseTime: Long) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = crosshairX + dx; y = crosshairY + dy
            pressure = 1f; size = 1f
        }
        val event = MotionEvent.obtain(
            baseTime, baseTime + 2, MotionEvent.ACTION_MOVE,
            1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try { ShizukuInjector.inject(event) }
        finally { event.recycle() }
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MediaProjection result arriving from MainActivity
        if (!projectionStarted && intent?.hasExtra("resultCode") == true) {
            val resultCode = intent.getIntExtra("resultCode", 0)
            val data = intent.getParcelableExtra<Intent>("data")
            val cfg = config
            if (data != null && cfg != null) {
                // Android 14+: must be foreground service BEFORE creating projection
                val chan = NotificationChannel("aim", "AimBot",
                    NotificationManager.IMPORTANCE_LOW)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(chan)
                val notif = Notification.Builder(this, "aim")
                    .setContentTitle("AimBot running")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .build()
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(1, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else startForeground(1, notif)

                val mp = getSystemService(MediaProjectionManager::class.java)
                val projection = mp.getMediaProjection(resultCode, data)
                val dm = resources.displayMetrics
                detector = DetectionEngine(projection, cfg,
                    dm.widthPixels, dm.heightPixels, dm.densityDpi)
                detector?.start()
                projectionStarted = true
            }
        }

        when (intent?.getStringExtra("action")) {
            "start" -> start()
            "stop" -> stop()
        }
        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stop()
        detector?.stop()
        lockManager?.release()
    }
}
