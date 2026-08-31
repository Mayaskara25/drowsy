# Drowsy — Commercial Vehicle Safety Platform V1

> **Camera → perception → temporal fatigue estimation → phone beep → event logging → fleet view**

Local-first driver drowsiness system for commercial vehicles. Python reference implementation of [PLAN(2).md](<PLAN(2).md>) (§1–§34). No VLM, no YOLO in the fatigue path. Phone speaker is the only intervention.

[![python 3.13](https://img.shields.io/badge/python-3.13-blue)](pyproject.toml)
[![uv](https://img.shields.io/badge/managed%20with-uv-black)](https://docs.astral.sh/uv/)
[![tests 10 passed](https://img.shields.io/badge/tests-10%20passed-green)](tests/)

---

## Table of contents

- [What this proves](#what-this-proves)
- [Quick start](#quick-start)
- [Architecture](#architecture)
- [Repo layout](#repo-layout)
- [Modules](#modules)
- [Configuration](#configuration)
- [Offline behaviour](#offline-behaviour)
- [Backend API](#backend-api)
- [Dashboard](#dashboard)
- [Verification](#verification)
- [Android port map](#android-port-map)
- [Roadmap & out of scope](#roadmap--out-of-scope)
- [Docs](#docs)

---

## What this proves

One complete loop (§1, §26 demo criteria A–L) without hardware:

```
AndroidFrontCamera / UsbUvc / Network
  → DriverPerceptionEngine (EAR/MAR/head-pose/gaze, §8)
    → 30s TemporalFeatureBuffer (§9)
      → FatigueEngine weighted score 0–100 (§10–11)
        → DriverStateMachine NORMAL→ATTENTION→FATIGUE→HIGH_RISK + hysteresis (§16)
          → AlertManager phone beep (§15)
            → FatigueEvent → SQLite (Room equiv, §19–20) → SyncQueue → FastAPI → Dashboard
```

Single-frame eye-closed ≠ drowsy (§7). The system reasons over **duration, frequency, persistence, multi-signal correlation and tracking quality**.

---

## Quick start

Requires `uv 0.12+`, Python 3.13. No camera, no Android SDK needed for the reference run.

```bash
# install
uv sync --extra dev          # creates .venv, installs fastapi/sqlmodel/numpy/pytest

# tests — perception, fatigue, backend, hysteresis, tracking gate
uv run pytest -q             # 10 passed

# offline end-to-end (no camera, no network) — 45s deterministic trace
uv run python scripts/simulate.py
# 0.0s score=  0 state=NORMAL     perclos=0.00 max_closure=0 yawns=0
# 10.0s score= 65 state=FATIGUE    perclos=0.21 max_closure=2000 yawns=1
# [EVENT] MEDIUM score=73 dur=29300ms
# Done. Events logged: 1 Alerts: 2  → sqlite:///drowsy_local.db

# backend + dashboard on :8000
uv run uvicorn backend.main:app --reload --port 8000
# http://localhost:8000/          fleet dashboard
# http://localhost:8000/docs      Swagger
# http://localhost:8000/api/dashboard/summary

# push the local event to the backend (proves sync queue, §24)
uv run python -c "
import asyncio
from drowsy.sync import init_db, sync_pending
from scripts.simulate import run
run()  # writes to drowsy_local.db
asyncio.run(sync_pending(init_db('sqlite:///drowsy_local.db'), 'http://localhost:8000'))
"
curl -s http://localhost:8000/api/vehicles/DEMO-001/events | jq .
```

> The simulate trace in `scripts/simulate.py:34` is deterministic: 0–8s normal, 8–10.5s prolonged eye closure (EAR 0.06) + 1s yawns at 8.5s/10.2s + head-down pitch 30° at 9.8s, then 30s recovery. Demonstrates temporal reasoning (§9), recovery, hysteresis (§11) and cooldown.

---

## Architecture

See [docs/architecture.md](docs/architecture.md) for the full diagram and data-flow. Key decisions (§4, §6–7, §12, §30):

* **Separation:** perception ↔ fatigue via `PerceptionFrame` only (agents rule §28). Either side can be swapped without touching the other.
* **No VLM / no YOLO** in real-time path — facial geometry + temporal behaviour is the problem, not object detection.
* **Rule-based V1** — weighted scoring with persistence, hysteresis, alert cooldown, tracking gate. Learned Temporal CNN/GRU (§12) is prepared via the `TemporalSnapshot → score` seam, not added prematurely.
* **Local-first** — detection + beep + DB never depend on network. Sync is best-effort batch.
* **Replaceable camera** — `CameraSource` abstraction (§14); phone front camera for dev, UVC IR for low-light prototype.

```
                    Camera Frame
                         |
              +-------------------+
              | Face Detection /  |
              | Face Tracking     |   ← DriverPerceptionEngine protocol
              +---------+---------+
                        |
              +-------------------+
              | Face Landmarks    |
              +---------+---------+
                        |
           +------------+-------------+
           |            |             |
        Eyes/EAR     Mouth/MAR    Head Pose/Gaze   ← src/drowsy/perception
           |            |             |
           +------------+-------------+
                        |
              Temporal Feature Layer           ← TemporalFeatureBuffer 30s
                        |
              +-------------------+
              | V1 Fatigue Engine |  ← weighted score + tracking gate
              +---------+---------+
                        |
             Driver State Machine              ← hysteresis, recovery
                        |
           +------------+-------------+
           |            |             |
         NORMAL     FATIGUE       HIGH RISK
                        |
                 Phone Audio Alert            ← AlertManager
                        |
                 FatigueEvent → DB → Sync → Backend → Dashboard
```

---

## Repo layout

Implements §29 (adapted to a Python reference — `android/` becomes `src/drowsy/` + `backend/`):

```
.
├── src/drowsy/                # core — maps 1:1 to Android Kotlin modules (§13)
│   ├── camera/                # CameraSource abstraction (§14)
│   │   └── __init__.py        # AndroidFrontCameraSource, UsbUvcCameraSource, NetworkCameraSource
│   ├── perception/            # DriverPerceptionEngine + EAR/MAR/head-pose/gaze (§5, §8)
│   │   └── __init__.py        # MockPerceptionEngine, LandmarkPerceptionEngine, PerceptionFrame
│   ├── fatigue/               # TemporalFeatureBuffer + FatigueEngine + DriverStateMachine (§9–11, §16)
│   │   └── __init__.py        # score_snapshot(), TemporalSnapshot
│   ├── alerts/                # AlertManager (§15)
│   ├── data/                  # Pydantic/SQLModel: FatigueEventPayload, Vehicle, Device, … (§19–20, §22)
│   ├── location/              # GPS stub (FixedLocationProvider)
│   ├── sync/                  # SQLite + SyncQueue + sync_pending() (§20, §24)
│   └── config/                # AppConfig, PerceptionConfig, FatigueThresholds (§10–11)
├── backend/
│   └── main.py                # FastAPI — POST /api/events, /api/vehicles, /api/dashboard/summary (§21–22)
├── dashboard/
│   ├── src/index.html         # source
│   └── dist/index.html        # served by backend StaticFiles at /
├── scripts/
│   └── simulate.py            # 45s deterministic end-to-end demo (no camera)
├── tests/
│   ├── test_perception.py     # EAR/MAR open vs closed
│   ├── test_fatigue.py        # blink-no-false-positive, sustained closure, yawn, PERCLOS, tracking gate, hysteresis
│   ├── test_backend.py        # ingest + vehicle/events/summary
│   └── conftest.py            # sys.path for backend/src
├── docs/                      # expanded docs (this update)
├── agents/                    # per-agent notes
├── AGENTS.md                  # ownership table (§28)
├── PLAN(2).md                 # V1 plan (§1–§34)
└── pyproject.toml             # uv project, hatchling wheel packages=["src/drowsy"]
```

---

## Modules

### Camera — `src/drowsy/camera` (§14)

```python
from drowsy.camera import AndroidFrontCameraSource, UsbUvcCameraSource, NetworkCameraSource
cam = AndroidFrontCameraSource(width=640, height=480, fps=15)
cam.open(); frame = cam.read()  # np.ndarray BGR
```

All three satisfy `CameraSource.open()/read()/close()/name`. `UsbUvcCameraSource` / `NetworkCameraSource` use `cv2.VideoCapture` when available; otherwise they fail gracefully so perception tests never block on hardware.

### Perception — `src/drowsy/perception` (§5, §8)

Wire contract (`docs/pipeline.md`):

```python
@dataclass class PerceptionFrame:
    face_present: bool; face_confidence: float; landmark_confidence: float
    tracking_quality: float
    eye: EyeFeatures | None; mouth: MouthFeatures | None
    head_pose: HeadPose | None; gaze: GazeFeatures | None
    timestamp_ms: int
```

Helpers: `eye_aspect_ratio(6 pts)`, `mouth_aspect_ratio(4/8 pts)`, `estimate_head_pose(468×2 landmarks)` (heuristic; replace with solvePnP in production).

Engines:

* `MockPerceptionEngine.inject(PerceptionFrame)` — deterministic frame injection for tests/simulate, also fallback when no model present.
* `LandmarkPerceptionEngine.get_eye_features(landmarks)` — reads MediaPipe indices `[33,160,158,133,153,144]` / `[362,385,387,263,373,380]`, computes real EAR.
* MediaPipe Face Landmarker itself lives behind the `DriverPerceptionEngine` Protocol; the app never hard-codes a model (§5.1).

Poor tracking (`face_confidence`, `tracking_quality < 0.35`) **dampens** the fatigue score rather than firing false events (§11).

### Fatigue — `src/drowsy/fatigue` (§9–11, §16)

Window: `TemporalConfig(window_s=30, max_window_size=300)` at 100 ms. Buffer prunes by timestamp, not count. Computes:

* PERCLOS, `max_closure_ms`, `mean_ear`, `blink_count`, `yawn_count` (MAR > 0.55 for >800 ms), `head_abnormal_ratio`, `gaze_off_ratio`, `tracking_quality_mean`, `prolonged_closure`.

Score (§10):

```python
score = 0.35*perclos + 0.25*closure + 0.15*yawn + 0.15*head + 0.10*gaze
        + 10 if prolonged and max_closure>1000 else 0
        * (tq / 0.35) if tq < gate else 1
```

Thresholds `0–30 NORMAL / 31–55 ATTENTION / 56–75 FATIGUE / 76–100 HIGH_RISK`. `DriverStateMachine` enforces hysteresis (`±5`) and recovery decay — prevents `NORMAL↔FATIGUE` oscillation every few seconds.

### Alerts — `src/drowsy/alerts` (§15)

```python
AlertManager(beep_on_attention=False).handle_state(state, now_ms, can_alert)
# ATTENTION → silent/subtle, FATIGUE → 400ms beep, HIGH_RISK → repeated 600ms beep
# Cooldown: FatigueThresholds.alert_cooldown_s = 8.0
```

Uses `sounddevice` when available, no-ops in headless CI. `stop_alert()` on recovery.

### Data & sync — `src/drowsy/data`, `src/drowsy/sync` (§19–20, §24)

Event (§19):

```json
{"eventId":"...","deviceId":"DEMO-DEVICE-001","vehicleId":"DEMO-001",
 "timestampStart":"...","timestampEnd":"...","durationMs":29300,
 "severity":"MEDIUM","maxFatigueScore":73,
 "eyeClosure":true,"yawning":true,"headPoseAbnormal":false,
 "alertTriggered":true,"recovered":true,"gps":{"lat":0,"lng":0}}
```

Tables: `Vehicle`, `Device`, `FatigueEvent`, `AlertEvent`, `SyncQueue`, `AppSettings`, `Calibration` (Room equivalent). Flow `Detection → Local DB → SyncQueue → Backend when online`. Helpers: `init_db("sqlite:///drowsy_local.db")`, `save_event(engine, payload)`, `get_unsynced(engine)`, `sync_pending(engine, backend_url)` (async batch POST to `/api/events/batch`).

---

## Configuration

All thresholds are **prototype engineering values** (§10), not medical. See [docs/configuration.md](docs/configuration.md) for the full table.

```python
from drowsy.config import AppConfig
cfg = AppConfig()
cfg.perception.ear_closed_threshold = 0.20   # EAR < 0.20 → eyes closed
cfg.perception.mar_yawn_threshold = 0.55    # MAR > 0.55 for 800ms → yawn
cfg.perception.eye_closure_min_ms = 400
cfg.fatigue.attention = 31; cfg.fatigue.fatigue = 56; cfg.fatigue.high_risk = 76
cfg.fatigue.hysteresis = 5; cfg.fatigue.alert_cooldown_s = 8.0
cfg.temporal.window_s = 30.0
```

Weights: `w_perclos 0.35 / w_max_closure 0.25 / w_yawn 0.15 / w_head_pose 0.15 / w_gaze 0.10`. `perclos_high 0.30` → 30% eyes-closed in window = 100 score component.

---

## Offline behaviour

Hard requirement (§24). Detection + beep + DB never block on network.

```
Online:  Camera → AI → Fatigue → beep → Local event → Backend sync
Offline: Camera → AI → Fatigue → beep → Local event (queued)
Return:  SyncQueue → POST /api/events/batch → Dashboard
```

`sync_pending()` returns 0 when offline; pending rows stay `synced=False` and are retried.

---

## Backend API

Small REST surface (§21). Swagger at `http://localhost:8000/docs`.

| Method | Path | Body | Notes |
|--------|------|------|-------|
| POST | `/api/devices/register` | `DeviceRegister{deviceId, vehicleId}` | upserts vehicle |
| POST | `/api/vehicles/register` | `VehicleRegister{vehicleId, name?, fleetId?}` | |
| POST | `/api/events` | `FatigueEventPayload` (§19) | 409 if duplicate `eventId` |
| POST | `/api/events/batch` | `FatigueEventPayload[]` | sync queue drain, skips duplicates |
| GET | `/api/vehicles` | — | `{vehicleId, name, lastState, lastScore, lastSeen}[]` |
| GET | `/api/vehicles/{id}` | — | single vehicle |
| GET | `/api/vehicles/{id}/events?limit=50` | — | newest first |
| GET | `/api/dashboard/summary` | — | `{totalVehicles, active, fatigueEventsToday, highRiskEvents}` |
| GET | `/api/health` | — | `{"ok": true}` |
| GET | `/` | — | dashboard static (`dashboard/dist/`) |

Full schemas: `src/drowsy/data/__init__.py:14`. DB is `sqlite:///backend.db` (swap `DB_URL` for Postgres in production §22).

---

## Dashboard

Fleet view (§23) at `/` — no build step.

* Cards: total vehicles / active / fatigue events today / high-risk.
* Table: vehicles with last state/score/seen → click vehicle loads timeline.
* Recent events: severity badge (LOW/MEDIUM/HIGH), score, duration, signals.
* Auto-refresh every 3s.

`dashboard/src/index.html` is the source; `dashboard/dist/index.html` is served by `backend/main.py:120`.

---

## Verification

### Unit / integration

```bash
uv run pytest -q          # 10 passed
uv run pytest -v          # per-test detail
uv run ruff check src/    # lint (ruff 0.16)
```

Covers (§25): EAR open vs closed, MAR yawn, normal blink does not trigger fatigue, sustained closure increases risk, PERCLOS, poor tracking dampens, yawn count, hysteresis prevents oscillation, alert cooldown, backend ingest/listing.

### System / offline

```bash
uv run python scripts/simulate.py           # proves full loop + recovery
# bind real camera (optional)
uv run python -c "from drowsy.camera import UsbUvcCameraSource; c=UsbUvcCameraSource(0); print(c.open())"
```

Demo acceptance (§26 A–L) is manually verified via the deterministic trace. IR/NIR low-light (§26 L) requires hardware (UVC IR camera at 640×480/720p, 15–30 FPS, §32).

---

## Android port map

`src/drowsy/*` modules are written to be Kotlin-portable (§13). Replace only the I/O bindings:

| Python (reference) | Android (target) |
|--------------------|----------------|
| `camera/AndroidFrontCameraSource` | CameraX + `CameraSource` |
| `perception/LandmarkPerceptionEngine` | MediaPipe Face Landmarker 468 + EAR/MAR |
| `fatigue/*` | same scoring, TFLite if learned model added (§12) |
| `alerts/AlertManager` | `AudioTrack` / `MediaPlayer` + `Vibrator` fallback |
| `data` SQLModel | Room (Vehicle/FatigueEvent/AlertEvent/SyncQueue) |
| `sync/sync_pending` | WorkManager `PeriodicWorkRequest` (retry + batch) |
| `location` | `FusedLocationProviderClient` |
| `config` | `DataStore` / `SharedPreferences` with sliders |

The learned temporal model (§12) seam is `TemporalSnapshot[] → fatigue_probability`. Candidate: 1D Temporal CNN / GRU, exported as TFLite / ONNX Runtime Mobile.

---

## Roadmap & out of scope

Explicitly **not** in V1 (§3): autonomous braking/steering, AEBS/ESC, lane/blind-spot, CAN/OBD, smartwatch, haptic puck, external speaker, custom camera, OEM/certification, large fleet, VLM, cloud video.

Next stages (§27): NIR camera integration (stage 9) → field testing stationary → controlled vehicle. Learned model only after representative field data exists.

---

## Docs

* Plan: [PLAN(2).md](<PLAN(2).md>)
* Ownership: [AGENTS.md](AGENTS.md)
* [docs/architecture.md](docs/architecture.md) — layers, data flow, agent ownership
* [docs/pipeline.md](docs/pipeline.md) — perception → fatigue → alerts → sync (with code pointers)
* [docs/api.md](docs/api.md) — request/response schemas, curl examples
* [docs/configuration.md](docs/configuration.md) — all thresholds + tuning
* [docs/testing.md](docs/testing.md) — covering §25 matrix

---

## License

Prototype — thresholds are engineering values, not regulatory (§30: 13). Do not claim accident reduction (§30: 14).
