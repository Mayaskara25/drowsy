# Configuration — `src/drowsy/config:1-54`

All thresholds are **prototype engineering values** (§10), not medical or regulatory (§30:13).

## AppConfig

```python
from drowsy.config import AppConfig, DEFAULT_CONFIG
cfg = AppConfig()              # or DEFAULT_CONFIG singleton
cfg.device_id = "DEMO-DEVICE-001"
cfg.vehicle_id = "DEMO-001"
cfg.backend_url = "http://localhost:8000"
```

## PerceptionConfig (`:6-15`)

| Field | Default | Meaning |
|-------|---------|---------|
| `ear_closed_threshold` | `0.20` | `EAR < 0.20 → eyes_closed` (§8) |
| `ear_open_threshold` | `0.25` | hysteresis upper — not yet used, reserved for open/close debouncing |
| `mar_yawn_threshold` | `0.55` | `MAR > 0.55 → yawning candidate` |
| `mar_yawn_min_duration_ms` | `800` | must stay open this long to count as a yawn event |
| `eye_closure_min_ms` | `400` | short blink below this is not "prolonged" |
| `perclos_window_s` | `30.0` | PERCLOS window (also in `TemporalConfig`) |
| `pitch_abnormal_deg` | `25` | head pitch beyond → abnormal |
| `yaw_abnormal_deg` | `30` | yaw beyond → abnormal |
| `roll_abnormal_deg` | `25` | roll beyond → abnormal |

## TemporalConfig (`:17-20`)

| Field | Default | Meaning |
|-------|---------|---------|
| `window_s` | `30.0` | rolling window (§9) |
| `feature_interval_ms` | `100` | expected frame interval (10 Hz) |
| `max_window_size` | `300` | cap = `window_s / interval` |

Buffer prunes by `timestamp_ms`, not count, so irregular FPS still yields a 30s window.

## FatigueThresholds (`:22-42`, §10–11)

| Field | Default | Meaning |
|-------|---------|---------|
| `attention` | `31` | score ≥31 → ATTENTION |
| `fatigue` | `56` | ≥56 → FATIGUE |
| `high_risk` | `76` | ≥76 → HIGH_RISK (0–100 scale §10) |
| `hysteresis` | `5` | drop requires `threshold - h` to step down |
| `alert_cooldown_s` | `8.0` | minimum gap between beeps |
| `tracking_quality_gate` | `0.35` | `tq < gate → raw *= tq/gate` dampening |
| `w_perclos` | `0.35` | PERCLOS weight |
| `w_max_closure` | `0.25` | longest closure weight |
| `w_yawn` | `0.15` | yawn count weight |
| `w_head_pose` | `0.15` | head abnormal ratio weight |
| `w_gaze` | `0.10` | off-road gaze weight |
| `perclos_high` | `0.30` | 30% closed in window = 100 component |
| `closure_high_ms` | `1500` | 1.5s max closure = 100 component |
| `yawn_high_count` | `3` | 3 yawns in window = 100 component |

Weights sum ≈1.0; tune without code changes. All thresholds are configurable via `AppConfig(fatigue=…)` or persisted in `AppSettings` table / Room `AppSettings`.

## Tuning guidance

* Lower `perclos_high` or raise `w_perclos` → more sensitive to eye closure proportion.
* Lower `closure_high_ms` → prolonged closure fires sooner (watch blink false positives — keep `eye_closure_min_ms` 300–500 ms).
* Raise `yawn_high_count` → require more yawns for high risk (single yawn → small increase by design §11).
* `hysteresis` 5 prevents oscillation; increase to 8–10 for slower recovery.
* `alert_cooldown_s` 8 stops chattering; reduce only if intervention requires faster repeat.
* Poor tracking: if `tracking_quality_gate` is too high, dim light will damp too aggressively; too low permits false positives (§11 poor-tracking case).

On Android, expose thresholds in Developer Settings / calibration screen (§18) and store via `DataStore`.
