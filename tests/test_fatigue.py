from drowsy.perception import PerceptionFrame, EyeFeatures, MouthFeatures, HeadPose, GazeFeatures
from drowsy.fatigue import FatigueEngine, DriverStateMachine, TemporalFeatureBuffer, score_snapshot
from drowsy.config import FatigueThresholds, TemporalConfig, PerceptionConfig
from drowsy.data import DriverState

def make_frame(ts, ear=0.3, mar=0.2, pitch=0, yaw=0, tq=0.95, face=True):
    eye = EyeFeatures(ear_mean=ear, ear_left=ear, ear_right=ear, eyes_closed=ear<0.20, closure_confidence=1.0)
    mouth = MouthFeatures(mar=mar, is_yawning=mar>0.55, mouth_open_ratio=mar)
    hp = HeadPose(pitch=pitch, yaw=yaw, roll=0, abnormal=abs(pitch)>25 or abs(yaw)>30)
    gaze = GazeFeatures(yaw=yaw, pitch=pitch, forward_prob=0.9)
    return PerceptionFrame(face_present=face, face_confidence=0.95, landmark_confidence=0.95, tracking_quality=tq, eye=eye, mouth=mouth, head_pose=hp, gaze=gaze, timestamp_ms=ts)

def test_normal_blink_no_fatigue():
    eng = FatigueEngine()
    # 5s normal + one short blink 200ms
    ts=0
    for i in range(50):
        ear = 0.30
        if 20 <= i < 22: ear=0.05  # 200ms blink
        f=make_frame(ts, ear=ear)
        score,_=eng.update(f)
        ts+=100
    assert score < 31, f"score {score} should stay NORMAL"

def test_sustained_closure_triggers():
    eng = FatigueEngine()
    ts=0
    # 2s eyes closed sustained (PERCLOS high, max closure ~2000ms)
    for i in range(300):  # 30s window
        ear = 0.05 if i>= 100 and i<120 else 0.30  # actually need longer
        # make last 2s closed
        if i>= 280: ear=0.05
        f=make_frame(ts, ear=ear)
        score,snap=eng.update(f)
        ts+=100
    # Now push 2s continuous closed
    for i in range(20):
        f=make_frame(ts, ear=0.05)
        score,snap=eng.update(f)
        ts+=100
    assert snap.max_closure_ms >= 1500
    assert score >= 30  # at least attention

def test_perclos_high_score():
    eng = FatigueEngine()
    ts=0
    # 30s window with 40% closed
    for i in range(300):
        ear = 0.05 if i%2==0 else 0.30  # 50% closed - extreme
        f=make_frame(ts, ear=ear)
        score,snap=eng.update(f)
        ts+=100
    assert snap.perclos > 0.3
    assert score > 30

def test_poor_tracking_dampens():
    from drowsy.fatigue import score_snapshot, TemporalSnapshot
    snap = TemporalSnapshot(perclos=0.5, max_closure_ms=2000, mean_ear=0.1, blink_count=5, yawn_count=2, head_abnormal_ratio=0.5, gaze_off_ratio=0.5, tracking_quality_mean=0.2, eye_closure=True, prolonged_closure=True)
    th = FatigueThresholds()
    s = score_snapshot(snap, th)
    snap2 = TemporalSnapshot(perclos=0.5, max_closure_ms=2000, mean_ear=0.1, blink_count=5, yawn_count=2, head_abnormal_ratio=0.5, gaze_off_ratio=0.5, tracking_quality_mean=0.9, eye_closure=True, prolonged_closure=True)
    s2 = score_snapshot(snap2, th)
    assert s < s2, "poor tracking should dampen"

def test_hysteresis():
    sm = DriverStateMachine()
    # go up
    sm.step(60, 0)
    assert sm.state == DriverState.FATIGUE or sm.state==DriverState.ATTENTION
    # small drop should not immediately recover due to hysteresis
    sm.state = DriverState.FATIGUE
    sm.step(55, 1000)  # still above fatigue - hyst 5 -> threshold 51, so stays FATIGUE
    assert sm.state == DriverState.FATIGUE
    sm.step(40, 2000)  # below 51 -> drops
    assert sm.state != DriverState.FATIGUE

def test_yawn_increases_score():
    eng = FatigueEngine()
    ts=0
    for i in range(300):
        mar = 0.7 if i in (50, 100, 150) else 0.2  # need sustained to count; use injected longer yawns via buffer hack
        f=make_frame(ts, mar=mar)
        # keep yawn for 10 frames (1s) to count
        if 50 <= i < 60: f.mouth.is_yawning=True
        if 100 <= i < 110: f.mouth.is_yawning=True
        if 150 <= i < 160: f.mouth.is_yawning=True
        score,snap=eng.update(f)
        ts+=100
    assert snap.yawn_count >= 2
