# Pipeline — Perception → Fatigue → Alerts → Sync

Code pointers use `path:line`.

## 1. Perception (`src/drowsy/perception:1-216`, §5 §8)

### Types

```python
PerceptionFrame(face_present, face_confidence, landmark_confidence, tracking_quality,
                eye: EyeFeatures | None, mouth: MouthFeatures | None,
                head_pose: HeadPose | None, gaze: GazeFeatures | None, timestamp_ms)
EyeFeatures(ear_left, ear_right, ear_mean, eyes_closed, closure_confidence)
MouthFeatures(mar, is_yawning, mouth_open_ratio)
HeadPose(pitch, yaw, roll, abnormal)
GazeFeatures(yaw, pitch, forward_prob)
```

### Geometry (§8)

* `eye_aspect_ratio(6 pts):59` — `EAR = (|p1-p5|+|p2-p4|) / 2|p0-p3|`.
* `mouth_aspect_ratio(4/8 pts):72` — `MAR = vertical / horizontal` (inner lip if 8 pts).
* `estimate_head_pose(468×2):89` — nose vs eye-center heuristic, `atan2(dx,0.15)` scaled to ±60°, `abnormal` if pitch>25 or yaw>30 or roll>25. Production: replace with `solvePnP`.

### Engines (behind `DriverPerceptionEngine:116` Protocol)

* `MockPerceptionEngine:127` — `inject(PerceptionFrame)` then `process(frame, ts)` returns it. Default: `face_present=False`. Used by `tests/` and `scripts/simulate.py:11`.
* `LandmarkPerceptionEngine:162` — expects `landmarks: (468,2)`. Reads MediaPipe indices left `[33,160,158,133,153,144]` right `[362,385,387,263,373,380]` for EAR, `[61,291,13,14]` for MAR, plus `get_head_pose`/`get_gaze`. `process(landmarks, ts)` returns a filled `PerceptionFrame:207`.
* MediaPipe Face Landmarker itself is injected by the Android app and stays behind the protocol — `src/drowsy/perception` never imports it.

Tracking quality (`face_present/face_confidence/landmark_confidence/tracking_quality`) is always populated; poor values dampen the score later, never trigger events (§8, §11).

## 2. Temporal layer (`src/drowsy/fatigue:13-119`, §9)

`TemporalFeatureBuffer` with `TemporalConfig(window_s=30, max_window_size=300)` at 100 ms.

* `push(frame):36` — appends, prunes `timestamp_ms < cutoff (30s)`, tracks yawn streaks (`is_yawning` >800 ms → `yawn_events`), tracks closure streak `_closure_start_ms`.
* `snapshot(now_ms):63` — returns `TemporalSnapshot`:
  `perclos, max_closure_ms` (longest contiguous closure, extended to `now_ms` if still closed), `mean_ear, blink_count` (transitions), `yawn_count` (events in window + in-progress), `head_abnormal_ratio, gaze_off_ratio, tracking_quality_mean, eye_closure, prolonged_closure`.

Yawn logic prunes by the same 30s cutoff; `yawn_count` includes an in-progress yawn once it exceeds `mar_yawn_min_duration_ms`.

## 3. Fatigue scoring (`src/drowsy/fatigue:122-172`, §10–11)

```python
def score_snapshot(s: TemporalSnapshot, th: FatigueThresholds) -> int:123
```

Components (0–100 each):

* `perclos_score = min(100, perclos / th.perclos_high *100)` — `perclos_high 0.30`.
* `closure_score = min(100, max_closure_ms / th.closure_high_ms *100)` — `closure_high_ms 1500`.
* `yawn_score  = min(100, yawn_count / th.yawn_high_count *100)` — `yawn_high_count 3`.
* `head_score = head_abnormal_ratio *100`, `gaze_score = gaze_off_ratio *100`.

Weighted `raw = 0.35*perclos + 0.25*closure + 0.15*yawn + 0.15*head + 0.10*gaze`, `+10` if `prolonged and max_closure>1000`, gated by `if tq < 0.35: raw *= tq/0.35`, clamped 0–100.

`FatigueEngine:148` wraps buffer + cooldown: `update(frame)->(score, snap):159`, `can_alert(now_ms):165` checks `alert_cooldown_s 8.0`, `mark_alert`.

Thresholds (§10): `0–30 NORMAL / 31–55 ATTENTION / 56–75 FATIGUE / 76–100 HIGH_RISK`. Prototype values only.

`DriverStateMachine:175` enforces hysteresis `h=5`: upward requires `score >= threshold`; downward requires `score <= threshold - h` and steps down through intermediate states (HIGH_RISK→FATIGUE→ATTENTION→NORMAL). `step(score, now_ms):186` returns new `DriverState`, logs `history`.

## 4. Alerts (`src/drowsy/alerts:1-72`, §15)

```python
AlertManager(handle_state(state_str, now_ms, can_alert))
# HIGH_RISK + can → play_high_risk_warning (repeated 600ms 1200Hz beep)
# FATIGUE   + can → play_fatigue_warning  (400ms 1000Hz)
# ATTENTION + can → play_attention_beep   (150ms 800Hz, only if beep_on_attention)
# NORMAL → stop_alert
```

Uses `sounddevice` if present, else no-op (CI-safe). `history` logs `(ts, kind)` for tests. Android: replace `_beep` with `AudioTrack`.

## 5. Sync & storage (`src/drowsy/data:1-143`, `src/drowsy/sync:1-85`, §19–20)

Tables (SQLModel): `Vehicle(vehicle_id PK, last_state/score/seen)`, `Device`, `FatigueEvent(event_id PK, …, synced bool)`, `AlertEvent`, `SyncQueue(queue_id, event_id, attempts)`, `AppSettings`, `Calibration(baseline_pitch/yaw/roll)`.

Event bookkeeping in `scripts/simulate.py:52-78`: when `state in (FATIGUE,HIGH_RISK)` open an event, accumulate `max_score` and flags (`eye_closure, yawning, headPoseAbnormal`); on recovery close it, compute `Severity(HIGH≥76, MEDIUM≥56)`, `FatigueEventPayload` → `save_event(engine, payload)` → row + `SyncQueue`.

`sync_pending(engine, backend_url):32` — `get_unsynced()` → `POST /api/events/batch` via `httpx.AsyncClient` → on 2xx `mark_synced()` (sets `synced=True`, deletes queue). Returns `len(pending)` or `0` on offline (no throw).

## 6. End-to-end trace

`scripts/simulate.py:34` — 450 frames @100 ms (45s):

```
0–8s   ear 0.30 mar 0.2          → NORMAL
8–10.5s ear 0.06 + mar 0.70 8.5–9.5s → yawn#1, perclos↑, max_closure 2–2.4s
9.8–11s pitch 30°                → head_abnormal_ratio↑
10.2–11.2s mar 0.75               → yawn#2
→ score 65–66 FATIGUE, state machine latches, AlertManager beeps (cooldown 8s)
→ event open 9.4s, closes ~38.7s on recovery (30s window decays) severity MEDIUM 73
```

## 7. Seam for learned model (§12)

Keep `LandmarkPerceptionEngine` + `TemporalFeatureBuffer`; replace only `score_snapshot` with:

```
TemporalSnapshot[] (seq t0..tN) → 1D Temporal CNN / GRU → fatigue_probability
```

Export as TFLite / ONNX Runtime Mobile. The current rule engine remains the baseline until field data exists.
