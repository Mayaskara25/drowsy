package com.drowsy.fatigue

/**
 * Prototype engineering thresholds (§10-11). Not medical. All configurable via DataStore.
 * Mirrors Python drowsy.config.FatigueThresholds / TemporalConfig / PerceptionConfig.
 */
data class PerceptionConfig(
    val earClosedThreshold: Float = 0.20f,
    val earOpenThreshold: Float = 0.25f,
    val marYawnThreshold: Float = 0.55f,
    val marYawnMinDurationMs: Long = 800,
    val eyeClosureMinMs: Long = 400,
    val perclosWindowS: Float = 30f,
    val pitchAbnormalDeg: Float = 25f,
    val yawAbnormalDeg: Float = 30f,
    val rollAbnormalDeg: Float = 25f,
)

data class TemporalConfig(
    val windowS: Float = 30f,
    val featureIntervalMs: Long = 100,
    val maxWindowSize: Int = 300,
)

data class FatigueThresholds(
    val attention: Int = 31,
    val fatigue: Int = 56,
    val highRisk: Int = 76,
    val hysteresis: Int = 5,
    val alertCooldownS: Float = 8.0f,
    val trackingQualityGate: Float = 0.35f,
    val wPerclos: Float = 0.35f,
    val wMaxClosure: Float = 0.25f,
    val wYawn: Float = 0.15f,
    val wHeadPose: Float = 0.15f,
    val wGaze: Float = 0.10f,
    val perclosHigh: Float = 0.30f,
    val closureHighMs: Int = 1500,
    val yawnHighCount: Int = 3,
)

/** YAML/DataStore mapping — use FatigueThresholds as single source of truth via fromYaml(). */
data class FatigueThresholdsYaml(
    val perclosWeight: Float = 0.35f,
    val closureWeight: Float = 0.25f,
    val yawnWeight: Float = 0.15f,
    val headPoseWeight: Float = 0.15f,
    val gazeWeight: Float = 0.10f,
    val attentionThreshold: Int = 31,
    val fatigueThreshold: Int = 56,
    val highRiskThreshold: Int = 76,
) {
    fun toThresholds(base: FatigueThresholds = FatigueThresholds()) = base.copy(
        wPerclos = perclosWeight, wMaxClosure = closureWeight, wYawn = yawnWeight,
        wHeadPose = headPoseWeight, wGaze = gazeWeight,
        attention = attentionThreshold, fatigue = fatigueThreshold, highRisk = highRiskThreshold,
    )
}
