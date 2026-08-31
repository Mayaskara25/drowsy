# Testing — §25

## Matrix

| Area | Test | File | Expectation |
|------|------|------|-------------|
| Perception (§25) | EAR open vs closed | `tests/test_perception.py:4` | open EAR > closed EAR, closed <0.20 |
|  | MAR yawn vs closed | `:9` | yawn MAR > closed, >0.5 |
|  | Mock no-face | `:13` | `face_present=False` |
| Fatigue engine (§25) | Normal blink no fatigue | `tests/test_fatigue.py:7` | 200ms blink in 5s → score <31 |
|  | Sustained closure triggers | `:17` | 2s continuous closed → `max_closure ≥1500`, score ≥30 |
|  | PERCLOS high score | `:33` | 50% perclos → perclos >0.3, score >30 |
|  | Poor tracking dampens | `:44` | same snapshot tq 0.2 vs 0.9 → score dampened |
|  | Hysteresis | `:54` | FATIGUE stays at 55, drops only when ≤51 (`fatigue - h`) |
|  | Yawn in window | `:63` | 3×1s yawns → `yawn_count ≥2` |
| Backend / system | Ingest + list + summary | `tests/test_backend.py:6` | POST events → GET vehicle/events → summary |
| Offline/system | Sync queue | `src/drowsy/sync:32` | `sync_pending` returns 0 when offline, batches on return |

Run: `uv run pytest -q` (10 passed), `uv run pytest -v` for detail. `tests/conftest.py:1` adds `src/` and project root to `sys.path` so `from drowsy.…` and `from backend.main` both resolve.

## System tests (§25, manual)

Camera disconnect/reconnect, app restart, internet loss/recovery, GPS unavailable (§24 — `LocationProvider.get()` returns `GpsFix(has_fix=False)`), low battery, obstruction, background/foreground are validated via:

* `scripts/simulate.py` — offline trace with `tracking_quality < 0.35` variant (dampens, not triggers) and with mock camera returning `None` frames (pipeline stays in NORMAL, no exception).
* Manual backend check:

```bash
uv run uvicorn backend.main:app --port 8000 &
curl -s http://localhost:8000/api/health  # → {"ok": true}
# kill network (or stop backend), run simulate → drowsy_local.db grows
# restart backend, sync:
uv run python -c "
import asyncio
from drowsy.sync import init_db, sync_pending
asyncio.run(sync_pending(init_db('sqlite:///drowsy_local.db'), 'http://localhost:8000'))
"
curl -s http://localhost:8000/api/vehicles/DEMO-001/events | jq .
```

## Simulate — the demo trace

`scripts/simulate.py:34` builds a 450-frame (45s @100 ms) trace:

* 0–8s normal (EAR 0.30)
* 8–10.5s prolonged closure (EAR 0.06) + yawn#1 8.5–9.5s MAR 0.70
* 9.8–11s head-down pitch 30°
* 10.2–11.2s yawn#2 MAR 0.75
* → `FatigueEngine` + `DriverStateMachine` → `AlertManager` (cooldown 8s) → `FatigueEvent` at ~9.4–38.7s `MEDIUM 73` → recovery.

Expected stdout:

```
 0.0s score=  0 state=NORMAL     perclos=0.00 max_closure=0 yawns=0 head_ab=0.00 alerts=NONE
10.0s score= 65 state=FATIGUE    perclos=0.21 max_closure=2000 yawns=1 head_ab=0.03 alerts=FATIGUE
[EVENT] MEDIUM score=73 dur=29300ms
40.0s score= 14 state=NORMAL     perclos=0.02 max_closure=400 yawns=1 head_ab=0.03 alerts=NONE
Done. Events logged: 1 Alerts: 2
```

Single blink (200 ms) or single yawn (<800 ms) does **not** fire. Repeated signals + head pose raise confidence, as required §11.

## What is not yet hardware-tested

`§26 L` low-light/NIR (§32) — needs UVC IR camera (720p, 15–30 FPS, IR illumination). The pipeline is hardware-agnostic: same `CameraSource` + `DriverPerceptionEngine` once the camera delivers frames.
