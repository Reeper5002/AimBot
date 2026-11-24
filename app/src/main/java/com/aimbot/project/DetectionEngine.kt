package com.aimbot.project

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.abs

/** Shared between detection thread and aimbot loop */
object SharedState {
    @Volatile var enemyX = 0f
    @Volatile var enemyY = 0f
    @Volatile var enemyVisible = false
}

class DetectionEngine(
    private val projection: MediaProjection,
    private val cfg: AimConfig,
    screenW: Int,
    screenH: Int,
    dpi: Int
) {
    // Downsample 2x = 4x less CPU (critical for Redmi A3x)
    private val w = (screenW / 2).coerceAtLeast(1)
    private val h = (screenH / 2).coerceAtLeast(1)

    private val thread = HandlerThread("Detect").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private val tolerance = cfg.targets.colorTolerance

    fun start() {
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection.createVirtualDisplay(
            "aimbot-detect", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ onImage() }, handler)
        Log.i("Detect", "Detection started ${w}x${h} tol=$tolerance")
    }

    private fun onImage() {
        val image = reader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val iw = image.width
            val ih = image.height

            val enemies = cfg.targets.enemyColors
            val allyColor = cfg.targets.teamColor
            val ignoreAllies = cfg.targets.ignoreAllies

            var sumX = 0f; var sumY = 0f; var count = 0
            var minY = ih; var maxY = 0

            var y = 0
            while (y < ih) {
                val rowPos = y * rowStride
                var x = 0
                while (x < iw) {
                    val idx = rowPos + x * pixelStride
                    val r = buffer.get(idx).toInt() and 0xFF
                    val g = buffer.get(idx + 1).toInt() and 0xFF
                    val b = buffer.get(idx + 2).toInt() and 0xFF

                    if (matchesEnemy(r, g, b, enemies) &&
                        !(ignoreAllies && isAlly(r, g, b, allyColor))
                    ) {
                        sumX += x; sumY += y; count++
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    x += 2
                }
                y += 2
            }

            val minPixels = maxOf(6, (cfg.targets.enemyMinSizePx / 2) * (cfg.targets.enemyMinSizePx / 2))

            if (count >= minPixels) {
                // Scale back to full-screen coordinates
                val cx = (sumX / count) * 2f
                val cy = (sumY / count) * 2f
                val headY = if (cfg.targets.headDetection)
                    (minY + (maxY - minY) * cfg.targets.headSizeRatio) * 2f
                else cy

                SharedState.enemyX = cx
                SharedState.enemyY = headY
                SharedState.enemyVisible = true
            } else {
                SharedState.enemyVisible = false
            }
        } catch (e: Exception) {
            Log.w("Detect", "scan: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun matchesEnemy(r: Int, g: Int, b: Int, colors: Array<IntArray>): Boolean {
        for (c in colors) {
            if (abs(r - c[0]) <= tolerance && abs(g - c[1]) <= tolerance && abs(b - c[2]) <= tolerance)
                return true
        }
        return false
    }

    private fun isAlly(r: Int, g: Int, b: Int, a: IntArray): Boolean =
        abs(r - a[0]) <= tolerance && abs(g - a[1]) <= tolerance && abs(b - a[2]) <= tolerance

    fun stop() {
        try {
            display?.release()
            reader?.close()
            projection.stop()
        } catch (_: Exception) { }
        thread.quitSafely()
    }
}
