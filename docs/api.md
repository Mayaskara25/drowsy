# Backend API — `backend/main.py:1-123` (§21–22)

Base URL `http://localhost:8000`. Swagger at `/docs`, schema at `/openapi.json`. CORS `*`. DB `sqlite:///backend.db` (`DB_URL:13`, swap for Postgres).

All models in `src/drowsy/data:14-143` (Pydantic + SQLModel).

## Events

### POST /api/devices/register

```json
// request  DeviceRegister
{"deviceId": "DEMO-DEVICE-001", "vehicleId": "DEMO-001", "name": "optional"}
// response
{"ok": true, "deviceId": "DEMO-DEVICE-001"}
```

Upserts `Vehicle` then `Device`. Idempotent.

### POST /api/vehicles/register

```json
{"vehicleId": "DEMO-001", "name": "Truck 1", "fleetId": "fleet-a"}
→ {"ok": true, "vehicleId": "DEMO-001"}
```

### POST /api/events — single event (§19)

```json
// request FatigueEventPayload
{
  "eventId": "uuid",
  "deviceId": "DEMO-DEVICE-001",
  "vehicleId": "DEMO-001",
  "timestampStart": "2026-08-31T10:00:00+00:00",
  "timestampEnd":   "2026-08-31T10:00:29.300+00:00",
  "durationMs": 29300,
  "severity": "MEDIUM",           // LOW | MEDIUM | HIGH
  "maxFatigueScore": 73,          // 0–100
  "eyeClosure": true,
  "yawning": true,
  "headPoseAbnormal": false,
  "alertTriggered": true,
  "recovered": true,
  "gps": {"lat": 0, "lng": 0},
  "trackingQuality": 1.0
}
→ {"ok": true, "eventId": "uuid"}   // 409 if duplicate eventId
```

Side effect: updates `Vehicle.last_state/last_score/last_seen`.

### POST /api/events/batch

```json
// request FatigueEventPayload[]
[ { ... }, { ... } ]
→ {"ok": true, "ingested": 2}
```

Used by `src/drowsy/sync:32 sync_pending()`. Skips duplicates.

### GET /api/vehicles

```json
[{"vehicleId":"DEMO-001","name":"DEMO-001","lastState":"MEDIUM","lastScore":73,"lastSeen":"2026-08-31T06:34:04.684172"}]
```

### GET /api/vehicles/{id}

```json
{"vehicleId":"DEMO-001","name":"DEMO-001","lastState":"MEDIUM","lastScore":73,"lastSeen":"..."}
```

404 if not found.

### GET /api/vehicles/{id}/events?limit=50

```json
[{"eventId":"...","timestampStart":"...","timestampEnd":"...","durationMs":29300,
  "severity":"MEDIUM","maxFatigueScore":73,"eyeClosure":true,"yawning":true,
  "headPoseAbnormal":false,"alertTriggered":true,"recovered":true,"gps":{"lat":0,"lng":0}}]
// ordered timestamp_start DESC
```

### GET /api/dashboard/summary

```json
{"totalVehicles": 3, "active": 3, "fatigueEventsToday": 5, "highRiskEvents": 2}
// today = date.today() in backend timezone; highRisk = severity == "HIGH"
```

### GET /api/health

```json
{"ok": true}
```

### GET /

Serves `dashboard/dist/index.html` via `StaticFiles` mount (`backend/main.py:118-123`) if the directory exists.

## Curl examples

```bash
curl -s http://localhost:8000/api/health
curl -s http://localhost:8000/api/vehicles | jq .
curl -s http://localhost:8000/api/vehicles/DEMO-001/events?limit=5 | jq .

# register + single ingest
curl -s -X POST http://localhost:8000/api/vehicles/register \
  -H 'content-type: application/json' -d '{"vehicleId":"DEMO-001"}'
curl -s -X POST http://localhost:8000/api/events \
  -H 'content-type: application/json' -d @event.json | jq .

# batch from sync queue
uv run python -c "
import asyncio
from drowsy.sync import init_db, sync_pending
asyncio.run(sync_pending(init_db('sqlite:///drowsy_local.db'), 'http://localhost:8000'))
"
```

## DB schema (SQLModel)

`Vehicle(vehicle_id PK, name, fleet_id, last_score, last_state, last_seen)`,
`Device(device_id PK, vehicle_id FK)`,
`FatigueEvent(event_id PK, device_id, vehicle_id FK, timestamp_start/end, duration_ms, severity indexed, max_fatigue_score, eye_closure, yawning, head_pose_abnormal, alert_triggered, recovered, gps_lat/lng, tracking_quality, synced indexed)`,
`AlertEvent`, `SyncQueue`. See `src/drowsy/data:42-95`.
