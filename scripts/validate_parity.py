#!/usr/bin/env python3
"""Python ↔ Kotlin parity check (§22). Replays same perception sequence through Python engine
and compares expected Kotlin outputs (from FatigueParityTest)."""
from drowsy.perception import PerceptionFrame, EyeFeatures, MouthFeatures, HeadPose, eye_aspect_ratio, Point2D
from drowsy.fatigue import FatigueEngine, DriverStateMachine
from drowsy.config import FatigueThresholds

def frame(ear, mar, pitch=0, tq=1.0, ts=0):
    eye = EyeFeatures(ear, ear, ear, ear < 0.20, 1.0 if ear < 0.20 else 0.0)
    mouth = MouthFeatures(mar, mar > 0.55, mar)
    hp = HeadPose(pitch, 0, 0, abs(pitch) > 25)
    return PerceptionFrame(True, 1.0, 1.0, tq, eye, mouth, hp, None, ts)

# EAR sanity
pts = [Point2D(0,0), Point2D(1,0.2), Point2D(2,0.2), Point2D(4,0), Point2D(2,-0.2), Point2D(1,-0.2)]
print(f"EAR open synthetic: {eye_aspect_ratio(pts):.3f} (expect >0.15)")

# Simulate 45s like Kotlin test
eng = FatigueEngine(); sm = DriverStateMachine()
for i in range(450):
    t = i*100
    if t < 8000: ear, mar, pitch = 0.30, 0.2, 0
    elif t < 8500: ear, mar, pitch = 0.06, 0.2, 0
    elif 8500 <= t <= 9500: ear, mar, pitch = 0.06, 0.70, 0
    elif 9500 < t < 9800: ear, mar, pitch = 0.06, 0.2, 0
    elif 9800 <= t <= 11200: ear, mar, pitch = 0.06, 0.75 if 10200 <= t <= 11200 else 0.2, 30
    elif t < 11500: ear, mar, pitch = 0.06, 0.2, 30
    else: ear, mar, pitch = 0.30, 0.2, 0
    score, snap = eng.update(frame(ear, mar, pitch, ts=t))
    sm.step(score, t)
    if t in (10000, 15000, 40000):
        print(f"{t}ms score={score} state={sm.state.value} perclos={snap.perclos:.2f} yawns={snap.yawn_count}")

print(f"Final state: {sm.state.value} (expect NORMAL after recovery)")
print("Parity check DONE — compare with Kotlin FatigueParityTest.simulateParitySmoke")
