# Architecture — V1 (§4)

> Lightweight face/landmark perception → engineered temporal features → rule-based fatigue engine → phone audio → local event → cloud sync → fleet dashboard.

## Layers

The pipeline is intentionally split so perception and fatigue evolve independently (§4–§5).

```
                    Camera Frame  (BGR np.ndarray, §14)
                         |
              +-------------------+
              | Face Detection /  |  DriverPerceptionEngine protocol
              | Face Tracking     |  src/drowsy/perception:128
              +---------+---------+
                        |
              +-------------------+
              | Face Landmarks    |  468×2 array (MediaPipe) or mock
              | / Face Mesh       |
              +---------+---------+
                        |
           +------------+-------------+
           |            |             |
        Eyes/EAR     Mouth/MAR    Head Pose/Gaze   src/drowsy/perception:59-112
           |            |             |
           +------------+-------------+
                        |
              Temporal Feature Layer           src/drowsy/fatigue:27-119
              30s rolling window (§9)          TemporalSnapshot
                        |
              +-------------------+
              | V1 Fatigue Engine |  score_snapshot() weighted 0–100 + tracking gate
              | Rule/Score Based  |  src/drowsy/fatigue:123-145
              +---------+---------+
                        |
             Driver State Machine            src/drowsy/fatigue:175-212
             NORMAL→ATTENTION→FATIGUE→HIGH_RISK + hysteresis
                        |
           +------------+-------------+
           |            |             |
         NORMAL     FATIGUE       HIGH RISK
                        |
                 Phone Audio Alert           src/drowsy/alerts:1-72
                 AlertManager + cooldown
                        |
                 FatigueEvent → SQLite → SyncQueue → Backend → Dashboard
                 src/drowsy/data  src/drowsy/sync  backend/main.py  dashboard/
```

## Ownership (§28)

| Agent | Owns | Key interfaces | Current code |
|-------|------|----------------|--------------|
| android-agent | lifecycle, camera, Room, audio, location | `CameraSource`, `AlertManager` | `src/drowsy/camera`, `alerts`, `location`, `sync` |
| perception-agent | face/landmarks, EAR/MAR/head-pose/gaze | `DriverPerceptionEngine` | `src/drowsy/perception` |
| fatigue-agent | temporal buffer, scoring, state machine | `FatigueEngine`, `DriverStateMachine` | `src/drowsy/fatigue`, `config` |
| backend-agent | FastAPI, DB, ingestion, sync | `POST /api/events` | `backend/main.py` |
| dashboard-agent | fleet view | `dashboard/src/index.html` | `dashboard/` |
| testing-agent | unit + integration + offline | `tests/`, `scripts/simulate.py` | `tests/`, `scripts/` |

Cross-boundary contract is `PerceptionFrame` only (AGENTS.md:12). No direct DB access from perception.

## Key decisions

* **No VLM (§6):** VLM needs low latency, offline, Android, deterministic EAR — not a VLM task.
* **No YOLO as core (§7):** `Eye closed` in one frame is not fatigue; need duration/frequency/persistence/yawn/head.
* **Rule-based V1 (§10–12):** weighted temporal score, hysteresis, cooldown, tracking gate. Learned 1D CNN/GRU (§12) seam is `TemporalSnapshot[] → probability`, swapped only after real data exists.
* **Local-first (§24, §30:7-9):** never `await backend` in the detection loop; sync is batch + best-effort.
* **Replaceable camera (§14, §32):** `CameraSource` → `AndroidFrontCameraSource` for dev, `UsbUvcCameraSource` for IR prototype (640×480 or 1280×720, 15–30 FPS, UVC/OTG).

## Data flow

```
Detection                FatiguEventPayload (§19)
  ↓                      { eventId, deviceId, vehicleId, timestampStart/End,
Local DB (SQLModel)       durationMs, severity, maxFatigueScore, eyeClosure,
  ↓                       yawning, headPoseAbnormal, alertTriggered, recovered, gps }
SyncQueue (unsynced=False)
  ↓   sync_pending() — httpx POST /api/events/batch
Backend SQLite (backend.db) — Vehicle/FatigueEvent
  ↓   GET /api/vehicles/{id}/events
Dashboard polling (3s) — cards + timeline
```

## Hardware boundary (§31–32)

```
     ┌─────────────────┐
     │ NIR / IR Camera  │  720p, 15–30 FPS, IR illumination, fixed mount
     └────────┬────────┘
              │
     ┌─────────────────┐
     │ Android Phone   │  Perception + Fatigue + DB + Audio + GPS
     └────────┬────────┘
              ↓
       Phone Speaker   (only intervention, §15)
```

No external speaker/buzzer/Pi/Jetson/CAN/smartwatch/haptics.

## Android mapping (§13)

`src/drowsy/*` is Kotlin-portable. See README Android port map and `docs/pipeline.md` for the Kotlin module correspondence and the `DriverPerceptionEngine` → MediaPipe Face Landmarker wiring.

## Definition of done (§33)

Checklist lives in PLAN(2).md:33. Reference `scripts/simulate.py` proves scoring/state/beep/recovery/DB/sync without hardware; `tests/` covers §25 fatigue-engine matrix; hardware criteria (camera, IR) require device.
