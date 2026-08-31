package com.drowsy.fatigue

/** Mirrors Python DriverState + data.DriverState (§16). */
enum class DriverState { NORMAL, ATTENTION, FATIGUE, HIGH_RISK }

/**
 * State machine with hysteresis §12 / §16. Mirrors Python DriverStateMachine:175.
 * - Upward: score >= threshold
 * - Downward: score <= threshold - hysteresis, stepping through intermediates
 * - Prevents rapid NORMAL↔FATIGUE oscillation from a single blink.
 */
class DriverStateMachine(
    val thresholds: FatigueThresholds = FatigueThresholds(),
    var state: DriverState = DriverState.NORMAL,
) {
    var lastTransitionMs: Long? = null
        private set
    val history: MutableList<Triple<Long, DriverState, Int>> = mutableListOf()

    fun step(score: Int, nowMs: Long): DriverState {
        val prev = state
        val h = thresholds.hysteresis
        state = when {
            state == DriverState.NORMAL && score >= thresholds.attention -> DriverState.ATTENTION
            state == DriverState.ATTENTION && score >= thresholds.fatigue -> DriverState.FATIGUE
            state == DriverState.FATIGUE && score >= thresholds.highRisk -> DriverState.HIGH_RISK
            state == DriverState.ATTENTION && score >= thresholds.highRisk -> DriverState.HIGH_RISK
            state == DriverState.NORMAL && score >= thresholds.fatigue -> DriverState.FATIGUE
            // downward with hysteresis
            state == DriverState.HIGH_RISK && score <= thresholds.highRisk - h ->
                if (score >= thresholds.fatigue - h) DriverState.FATIGUE
                else if (score >= thresholds.attention - h) DriverState.ATTENTION else DriverState.NORMAL
            state == DriverState.FATIGUE && score <= thresholds.fatigue - h ->
                if (score >= thresholds.attention - h) DriverState.ATTENTION else DriverState.NORMAL
            state == DriverState.ATTENTION && score <= thresholds.attention - h -> DriverState.NORMAL
            else -> state
        }
        if (state != prev) {
            lastTransitionMs = nowMs
            history.add(Triple(nowMs, state, score))
        }
        return state
    }
}
