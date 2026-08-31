"""Offline pipeline simulation — no camera, no backend required. Proves §4 loop."""
import time, uuid
from datetime import datetime, timezone
from drowsy.perception import PerceptionFrame, EyeFeatures, MouthFeatures, HeadPose, GazeFeatures
from drowsy.fatigue import FatigueEngine, DriverStateMachine
from drowsy.alerts import AlertManager
from drowsy.data import FatigueEventPayload, Severity
from drowsy.config import AppConfig
from drowsy.sync import init_db, save_event

def make_frame(ts, ear=0.28, mar=0.2, pitch=0, yaw=0, tq=0.95):
    eye = EyeFeatures(ear_mean=ear, ear_left=ear, ear_right=ear, eyes_closed=ear<0.20, closure_confidence=1.0 if ear<0.20 else 0.0)
    mouth = MouthFeatures(mar=mar, is_yawning=mar>0.55, mouth_open_ratio=mar)
    hp = HeadPose(pitch=pitch, yaw=yaw, roll=0, abnormal=abs(pitch)>25 or abs(yaw)>30)
    gaze = GazeFeatures(yaw=yaw, pitch=pitch, forward_prob=1.0 - min(1, abs(yaw)/60))
    return PerceptionFrame(face_present=True, face_confidence=0.95, landmark_confidence=0.95, tracking_quality=tq, eye=eye, mouth=mouth, head_pose=hp, gaze=gaze, timestamp_ms=ts)

def run(scenario="demo", backend_sync=False):
    cfg = AppConfig()
    engine = FatigueEngine(thresholds=cfg.fatigue, temporal=cfg.temporal, perception_cfg=cfg.perception)
    sm = DriverStateMachine(thresholds=cfg.fatigue)
    alerts = AlertManager()
    db = init_db("sqlite:///drowsy_local.db")

    # scenario timeline: 30s
    ts = 0
    events = []
    current_event_start = None
    max_score_in_event = 0
    eye_closure_flag = yawning_flag = head_flag = False

    # Build deterministic trace:
    # 0-8s normal, 8-10s prolonged eye closure + yawns, 10-12s head nod, 12-18s recovery
    for i in range(450):  # 45s @100ms
        t_sec = ts/1000
        ear, mar, pitch = 0.30, 0.2, 0
        if 8 <= t_sec < 10.5:
            ear = 0.06  # eyes closed prolonged
            if 8.5 < t_sec < 9.5: mar = 0.70  # yawn 1s
        if 9.8 <= t_sec < 11:
            pitch = 30  # head down
        if 10.2 <= t_sec < 11.2:
            mar = 0.75  # second yawn 1s
        f = make_frame(ts, ear=ear, mar=mar, pitch=pitch)
        score, snap = engine.update(f)
        state = sm.step(score, ts)
        if alerts._active.value != state.value:
            can = engine.can_alert(ts)
            alerts.handle_state(state.value, ts, can)
            if state.value in ("FATIGUE","HIGH_RISK") and can:
                engine.mark_alert(ts)

        # event bookkeeping (§19)
        if state.value in ("FATIGUE","HIGH_RISK"):
            if current_event_start is None:
                current_event_start = ts
                max_score_in_event = score
            max_score_in_event = max(max_score_in_event, score)
            if snap.eye_closure: eye_closure_flag=True
            if snap.yawn_count>0: yawning_flag=True
            if snap.head_abnormal_ratio>0.2: head_flag=True
        else:
            if current_event_start is not None:
                # close event on recovery
                dur = ts - current_event_start
                sev = Severity.HIGH if max_score_in_event>=76 else Severity.MEDIUM if max_score_in_event>=56 else Severity.LOW
                payload = FatigueEventPayload(
                    deviceId=cfg.device_id, vehicleId=cfg.vehicle_id,
                    timestampStart=datetime.fromtimestamp((current_event_start)/1000, tz=timezone.utc).isoformat(),
                    timestampEnd=datetime.fromtimestamp(ts/1000, tz=timezone.utc).isoformat(),
                    durationMs=dur, severity=sev, maxFatigueScore=max_score_in_event,
                    eyeClosure=eye_closure_flag, yawning=yawning_flag, headPoseAbnormal=head_flag,
                    alertTriggered=True, recovered=True,
                )
                save_event(db, payload)
                events.append(payload)
                print(f"[EVENT] {sev.value} score={max_score_in_event} dur={dur}ms")
                current_event_start=None; eye_closure_flag=yawning_flag=head_flag=False; max_score_in_event=0

        if i%50==0:
            print(f"{t_sec:5.1f}s score={score:3d} state={state.value:10s} perclos={snap.perclos:.2f} max_closure={snap.max_closure_ms} yawns={snap.yawn_count} head_ab={snap.head_abnormal_ratio:.2f} alerts={alerts._active.value}")

        ts+=100

    print(f"\nDone. Events logged: {len(events)} Alerts: {len(alerts.history)}")
    for e in events:
        print(e.model_dump_json(indent=2))
    return events

if __name__ == "__main__":
    run()
