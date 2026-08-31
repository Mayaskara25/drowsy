package com.drowsy.metrics

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * §21 Performance instrumentation. Collects:
 * camera FPS, received FPS, inference FPS, latency, CPU/mem, dropped frames, tracking.
 * Dev overlay shows FPS/inference/network/face/tracking/fatigue/state.
 */
data class PerfSnapshot(
    val cameraFps: Float = 0f,
    val receivedFps: Float = 0f,
    val inferenceFps: Float = 0f,
    val inferenceMs: Long = 0,
    val endToEndMs: Long = 0,
    val networkKbps: Float = 0f,
    val droppedFrames: Long = 0,
    val faceConfidence: Float = 0f,
    val trackingQuality: Float = 0f,
    val fatigueScore: Int = 0,
    val state: String = "NORMAL",
)

class PerformanceMetrics {
    private val _snapshot = MutableStateFlow(PerfSnapshot())
    val snapshot: StateFlow<PerfSnapshot> = _snapshot

    @Volatile private var framesIn = 0L; @Volatile private var framesOut = 0L
    @Volatile private var lastTick = SystemClock.elapsedRealtime()
    @Volatile private var inferCount = 0; @Volatile private var inferMsSum = 0L

    fun onFrameReceived() { framesIn++ }
    fun onInferenceDone(latencyMs: Long, faceConf: Float, tq: Float, score: Int, state: String) {
        framesOut++; inferCount++; inferMsSum += latencyMs
        maybeTick(faceConf, tq, score, state)
    }
    fun onDropped() { _snapshot.update { it.copy(droppedFrames = it.droppedFrames + 1) } }
    fun onNetworkBytes(bytes: Long, dtMs: Long) {
        if (dtMs > 0) _snapshot.update { it.copy(networkKbps = bytes * 8f / dtMs) }
    }

    private fun maybeTick(faceConf: Float, tq: Float, score: Int, state: String) {
        val now = SystemClock.elapsedRealtime(); val dt = now - lastTick
        if (dt >= 1_000L) {
            val fpsIn = framesIn * 1000f / dt; val fpsOut = framesOut * 1000f / dt
            val avgInfer = if (inferCount > 0) inferMsSum / inferCount else 0
            _snapshot.update { prev ->
                PerfSnapshot(
                    cameraFps = fpsIn, receivedFps = fpsIn, inferenceFps = fpsOut,
                    inferenceMs = avgInfer, faceConfidence = faceConf, trackingQuality = tq,
                    fatigueScore = score, state = state, droppedFrames = prev.droppedFrames,
                    networkKbps = prev.networkKbps
                )
            }
            framesIn = 0; framesOut = 0; inferCount = 0; inferMsSum = 0; lastTick = now
        }
    }
}
