package com.drowsy

import com.drowsy.fatigue.*
import com.drowsy.perception.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Python ↔ Android validation (§22). Given same perception sequence, compare:
 * EAR, MAR, PERCLOS, closure duration, yawn detection, fatigue score, state.
 * Mirrors Python tests/test_fatigue.py + simulate.py deterministic trace.
 */
class FatigueParityTest {

    private fun frame(ear: Float, mar: Float, pitch: Float = 0f, tq: Float = 1f, ts: Long): PerceptionFrame {
        val eye = MockPerceptionEngine.syntheticEye(ear)
        val mouth = MockPerceptionEngine.syntheticMouth(mar)
        val hp = MockPerceptionEngine.syntheticHead(pitch)
        return PerceptionFrame(true, 1f, 1f, tq, eye, mouth, hp, null, ts)
    }

    @Test fun earOpenVsClosed() {
        assertTrue(eyeAspectRatio(listOf(Point2D(0f,0f), Point2D(1f,0.2f), Point2D(2f,0.2f), Point2D(4f,0f), Point2D(2f,-0.2f), Point2D(1f,-0.2f))) > 0.15f)
        assertEquals(true, MockPerceptionEngine.syntheticEye(0.06f).eyesClosed)
        assertEquals(false, MockPerceptionEngine.syntheticEye(0.30f).eyesClosed)
    }

    @Test fun singleBlinkDoesNotTriggerFatigue() {
        val eng = FatigueEngine()
        var score = 0
        for (i in 0 until 300) {
            val ear = if (i in 100..102) 0.06f else 0.30f
            val (s, _) = eng.update(frame(ear, 0.2f, ts = i*100L))
            score = s
        }
        assertTrue("single blink should not trigger fatigue, score=$score", score < 31)
    }

    @Test fun sustainedClosureTriggersFatigue() {
        val eng = FatigueEngine()
        var score = 0
        for (i in 0 until 300) {
            val ear = if (i >= 80) 0.06f else 0.30f
            val (s, _) = eng.update(frame(ear, 0.2f, ts = i*100L))
            score = s
        }
        assertTrue("sustained closure should elevate score, got $score", score >= 30)
    }

    @Test fun poorTrackingDampensScore() {
        val engGood = FatigueEngine()
        val engPoor = FatigueEngine()
        for (i in 0 until 150) {
            engGood.update(frame(0.06f, 0.2f, tq = 1f, ts = i*100L))
            engPoor.update(frame(0.06f, 0.2f, tq = 0.1f, ts = i*100L))
        }
        val (sGood, _) = engGood.update(frame(0.06f, 0.2f, tq=1f, ts=15000))
        val (sPoor, _) = engPoor.update(frame(0.06f, 0.2f, tq=0.1f, ts=15000))
        assertTrue("poor tracking should dampen: good=$sGood poor=$sPoor", sPoor < sGood)
    }

    @Test fun yawnCountRequiresDuration() {
        val buf = TemporalFeatureBuffer()
        // single open-mouth frame must not become yawn (§8)
        buf.push(PerceptionFrame(true,1f,1f,1f, MockPerceptionEngine.syntheticEye(0.3f), MouthFeatures(0.8f,true,0.8f), null, null, 0))
        buf.push(PerceptionFrame(true,1f,1f,1f, MockPerceptionEngine.syntheticEye(0.3f), MouthFeatures(0.2f,false,0.2f), null, null, 100))
        assertEquals(0, buf.snapshot(100).yawnCount)
        // sustained yawn >800ms should count
        val buf2 = TemporalFeatureBuffer()
        for (i in 0..9) buf2.push(PerceptionFrame(true,1f,1f,1f, MockPerceptionEngine.syntheticEye(0.3f), MouthFeatures(0.7f,true,0.7f), null, null, i*100L))
        buf2.push(PerceptionFrame(true,1f,1f,1f, MockPerceptionEngine.syntheticEye(0.3f), MouthFeatures(0.2f,false,0.2f), null, null, 1000))
        assertEquals(1, buf2.snapshot(1000).yawnCount)
    }

    @Test fun hysteresisPreventsOscillation() {
        val th = FatigueThresholds(attention=31, fatigue=56, highRisk=76, hysteresis=5)
        val sm = DriverStateMachine(th)
        assertEquals(DriverState.NORMAL, sm.state)
        sm.step(60, 0); assertEquals(DriverState.FATIGUE, sm.state)
        sm.step(54, 100) // just below fatigue but above hysteresis gate (56-5=51)
        assertEquals(DriverState.FATIGUE, sm.state) // should not drop yet
        sm.step(50, 200) // <=51 → drop
        assertEquals(DriverState.ATTENTION, sm.state)
    }

    @Test fun simulateParitySmoke() {
        // Deterministic 45s trace like Python scripts/simulate.py:34
        val eng = FatigueEngine()
        val sm = DriverStateMachine(eng.thresholds)
        var lastState = DriverState.NORMAL
        for (i in 0 until 450) {
            val tMs = i*100L
            val (ear, mar, pitch) = when {
                tMs < 8000 -> Triple(0.30f, 0.2f, 0f)
                tMs < 8500 -> Triple(0.06f, 0.2f, 0f)
                tMs in 8500..9500 -> Triple(0.06f, 0.70f, 0f)
                tMs in 9500..9800 -> Triple(0.06f, 0.2f, 0f)
                tMs in 9800..11200 -> Triple(0.06f, if (tMs in 10200..11200) 0.75f else 0.2f, 30f)
                tMs < 11500 -> Triple(0.06f, 0.2f, 30f)
                else -> Triple(0.30f, 0.2f, 0f)
            }
            val (score, _) = eng.update(frame(ear, mar, pitch, ts = tMs))
            lastState = sm.step(score, tMs)
        }
        // After recovery, should return to NORMAL (hysteresis decay)
        assertEquals(DriverState.NORMAL, lastState)
    }
}
