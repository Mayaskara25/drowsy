# Agents — ownership (§28)

| Agent | Owns | Key interfaces | Current code | Docs |
|-------|------|----------------|--------------|------|
| android-agent | app lifecycle, camera abstraction, UI, Room, audio, location | `CameraSource`, `AlertManager` | `src/drowsy/camera`, `src/drowsy/alerts`, `src/drowsy/location`, `src/drowsy/sync` | `docs/architecture.md`, `docs/pipeline.md:1` |
| perception-agent | face detection, landmarks, EAR/MAR, head-pose, gaze | `DriverPerceptionEngine` | `src/drowsy/perception` | `docs/pipeline.md:1`, `docs/testing.md` |
| fatigue-agent | temporal buffer, scoring, state machine, thresholds | `FatigueEngine`, `DriverStateMachine` | `src/drowsy/fatigue`, `src/drowsy/config` | `docs/configuration.md`, `docs/pipeline.md:2-3` |
| backend-agent | FastAPI, DB, event ingestion, sync | `POST /api/events`, `/api/vehicles`, `/api/dashboard/summary` | `backend/main.py`, `src/drowsy/data` | `docs/api.md` |
| dashboard-agent | fleet view, timeline, status | `dashboard/src/index.html` | `dashboard/src/index.html` → `dist/` | `docs/architecture.md`, `README Dashboard` |
| testing-agent | unit + integration + offline tests | `tests/`, `scripts/simulate.py` | `tests/`, `scripts/simulate.py` | `docs/testing.md` |

Do not cross-edit without agreement. Perception ↔ fatigue via `PerceptionFrame` only (`src/drowsy/perception:45`).

## Adding a new model

Perception: keep the `DriverPerceptionEngine` Protocol — inject MediaPipe / TFLite behind it, no changes to fatigue.
Fatigue: replace only `fatigue/score_snapshot` with the §12 `TemporalSnapshot[] → probability` model; keep `TemporalFeatureBuffer` and `DriverStateMachine` with hysteresis.

See `docs/architecture.md` for the seam and `docs/pipeline.md` for code pointers.
