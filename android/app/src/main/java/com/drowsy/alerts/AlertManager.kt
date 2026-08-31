package com.drowsy.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.drowsy.fatigue.DriverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

enum class AlertLevel { NONE, ATTENTION, FATIGUE, HIGH_RISK }

/**
 * Phone speaker alerts §13 / §15. Fatigue engine requests alert; this class owns audio.
 * Mirrors Python alerts.AlertManager.
 *
 * ```kotlin
 * interface AlertManager {
 *     fun attention()
 *     fun fatigueWarning()
 *     fun highRiskWarning()
 *     fun stop()
 * }
 * ```
 */
interface AlertManager {
    fun attention(nowMs: Long)
    fun fatigueWarning(nowMs: Long)
    fun highRiskWarning(nowMs: Long)
    fun stop(nowMs: Long)
    fun handleState(state: DriverState, nowMs: Long, canAlert: Boolean)
}

class PhoneAlertManager(
    private val context: Context,
    private val beepOnAttention: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AlertManager {

    var active: AlertLevel = AlertLevel.NONE
        private set
    var lastBeepMs: Long? = null
        private set
    val history: MutableList<Pair<Long, String>> = mutableListOf()

    override fun attention(nowMs: Long) {
        if (!beepOnAttention) return
        active = AlertLevel.ATTENTION; lastBeepMs = nowMs
        history.add(nowMs to "ATTENTION_BEEP")
        scope.launch { beep(800, 150) }
    }
    override fun fatigueWarning(nowMs: Long) {
        active = AlertLevel.FATIGUE; lastBeepMs = nowMs
        history.add(nowMs to "FATIGUE_BEEP")
        scope.launch { beep(1000, 400) }
    }
    override fun highRiskWarning(nowMs: Long) {
        active = AlertLevel.HIGH_RISK; lastBeepMs = nowMs
        history.add(nowMs to "HIGH_RISK_BEEP")
        scope.launch { beep(1200, 600); beep(1200, 600) }
    }
    override fun stop(nowMs: Long) {
        if (active != AlertLevel.NONE) history.add(nowMs to "STOP")
        active = AlertLevel.NONE
        // stop ongoing AudioTrack if needed
    }

    override fun handleState(state: DriverState, nowMs: Long, canAlert: Boolean) {
        when {
            state == DriverState.HIGH_RISK && canAlert -> highRiskWarning(nowMs)
            state == DriverState.FATIGUE && canAlert -> fatigueWarning(nowMs)
            state == DriverState.ATTENTION && beepOnAttention && canAlert -> attention(nowMs)
            state == DriverState.NORMAL -> stop(nowMs)
            // ATTENTION without beep → no audio, just UI
        }
    }

    private fun beep(freqHz: Int, durationMs: Int) {
        try {
            val sr = 44100; val n = (sr * durationMs / 1000)
            val buf = ShortArray(n) { i -> (Short.MAX_VALUE * 0.3 * sin(2 * Math.PI * freqHz * i / sr)).toInt().toShort() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size); track.play()
            Thread.sleep(durationMs.toLong() + 20); track.stop(); track.release()
        } catch (_: Exception) { /* headless / no audio device — still logs history */ }
    }
}
