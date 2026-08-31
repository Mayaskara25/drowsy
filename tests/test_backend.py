from fastapi.testclient import TestClient
from backend.main import app
import uuid

client = TestClient(app)

def test_ingest_and_list():
    vid = f"VEH-{uuid.uuid4().hex[:6]}"
    dev = f"DEV-{uuid.uuid4().hex[:6]}"
    client.post("/api/vehicles/register", json={"vehicleId": vid})
    client.post("/api/devices/register", json={"deviceId": dev, "vehicleId": vid})
    payload = {
        "eventId": str(uuid.uuid4()),
        "deviceId": dev,
        "vehicleId": vid,
        "timestampStart": "2026-08-31T10:00:00+00:00",
        "timestampEnd": "2026-08-31T10:00:05+00:00",
        "durationMs": 5000,
        "severity": "HIGH",
        "maxFatigueScore": 88,
        "eyeClosure": True,
        "yawning": True,
        "headPoseAbnormal": True,
        "alertTriggered": True,
        "recovered": True,
        "gps": {"lat": 12.97, "lng": 77.59},
        "trackingQuality": 0.92
    }
    r = client.post("/api/events", json=payload)
    assert r.status_code == 200
    r = client.get(f"/api/vehicles/{vid}/events")
    assert r.status_code == 200
    assert len(r.json()) >= 1
    r = client.get("/api/dashboard/summary")
    assert r.status_code == 200
    assert r.json()["totalVehicles"] >= 1
