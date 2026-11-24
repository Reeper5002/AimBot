package com.aimbot.project

/**
 * Handles sniper-specific logic.
 */
class SniperManager(private val cfg: AimConfig.Sniper) {

    enum class Phase { UNSCOPED, SCOPING_IN, SCOPED, SCOPING_OUT }

    var phase: Phase = Phase.UNSCOPED
        private set
    var scopedSince: Long = 0
    var lastShotMs: Long = 0
    var maintainingRecoilLock: Boolean = false

    fun scopeIn(nowMs: Long) {
        if (phase == Phase.UNSCOPED) {
            phase = Phase.SCOPING_IN
            scopedSince = nowMs
        }
    }

    fun scopeOut(nowMs: Long) {
        if (phase == Phase.SCOPED) {
            phase = Phase.SCOPING_OUT
        }
    }

    fun onShotFired(nowMs: Long) {
        lastShotMs = nowMs
        if (cfg.maintainLockThroughRecoil) {
            maintainingRecoilLock = true
            Thread {
                Thread.sleep(cfg.postShotReacquireMs.toLong())
                maintainingRecoilLock = false
            }.start()
        }
    }

    fun update(nowMs: Long) {
        when (phase) {
            Phase.SCOPING_IN -> {
                val targetMs = if (cfg.fastScopeIn) cfg.scopeInMs else cfg.defaultScopeInMs
                if (nowMs - scopedSince >= targetMs) phase = Phase.SCOPED
            }
            Phase.SCOPING_OUT -> {
                val targetMs = if (cfg.fastScopeIn) cfg.scopeOutMs else cfg.defaultScopeOutMs
                if (nowMs - scopedSince >= targetMs) phase = Phase.UNSCOPED
            }
            else -> {}
        }
    }

    fun effectiveSniperParams(distanceToTarget: Float): Pair<Int, Float> {
        if (phase != Phase.SCOPED && phase != Phase.SCOPING_IN) {
            return 130 to 0.42f
        }

        val radius = cfg.sniperLockRadiusPx
        var snug = cfg.sniperSnugness

        if (cfg.closeRangeLock && distanceToTarget <= cfg.closeRangePx) {
            snug = cfg.closeRangeSnugness
            return (radius * 0.6).toInt() to snug
        }

        return radius to snug
    }

    fun isScoped() = phase == Phase.SCOPED
    fun isRecoilLocking() = maintainingRecoilLock
}
