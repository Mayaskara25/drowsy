"""Backend — small REST API (§21-22). Runs on :8000."""
from __future__ import annotations
from datetime import datetime, timezone, date
from typing import List, Optional
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlmodel import SQLModel, create_engine, Session, select
from pydantic import BaseModel

from drowsy.data import FatigueEvent, Vehicle, Device, FatigueEventPayload, DeviceRegister, VehicleRegister, payload_to_row

DB_URL = "sqlite:///backend.db"
engine = create_engine(DB_URL, echo=False)
SQLModel.metadata.create_all(engine)

app = FastAPI(title="Drowsy Fleet Backend", version="0.1.0")
# Restrict CORS in prod via ALLOWED_ORIGINS env; wildcard for local demo
import os as _os
_allowed = [o.strip() for o in _os.getenv("ALLOWED_ORIGINS", "*").split(",") if o.strip()]
app.add_middleware(CORSMiddleware, allow_origins=_allowed, allow_methods=["GET","POST"], allow_headers=["*"])

# ---- helpers ----
def upsert_vehicle(s: Session, vid: str, name: Optional[str]=None, fleet: Optional[str]=None):
    v = s.get(Vehicle, vid)
    if not v:
        v = Vehicle(vehicle_id=vid, name=name or vid, fleet_id=fleet)
        s.add(v)
        s.commit(); s.refresh(v)
    return v

# ---- API (§21) ----
@app.post("/api/devices/register")
def register_device(body: DeviceRegister):
    with Session(engine) as s:
        upsert_vehicle(s, body.vehicleId)
        d = s.get(Device, body.deviceId)
        if not d:
            d = Device(device_id=body.deviceId, vehicle_id=body.vehicleId, name=body.name)
            s.add(d); s.commit(); s.refresh(d)
        return {"ok": True, "deviceId": d.device_id}

@app.post("/api/vehicles/register")
def register_vehicle(body: VehicleRegister):
    with Session(engine) as s:
        v = upsert_vehicle(s, body.vehicleId, body.name, body.fleetId)
        return {"ok": True, "vehicleId": v.vehicle_id}

@app.post("/api/events")
def ingest_event(payload: FatigueEventPayload):
    with Session(engine) as s:
        upsert_vehicle(s, payload.vehicleId)
        row = payload_to_row(payload)
        # upsert by event_id
        existing = s.get(FatigueEvent, row.event_id)
        if existing:
            raise HTTPException(409, "event exists")
        s.add(row)
        # update vehicle last
        v = s.get(Vehicle, payload.vehicleId)
        if v:
            v.last_score = payload.maxFatigueScore
            v.last_state = payload.severity.value
            v.last_seen = datetime.now(timezone.utc)
            s.add(v)
        s.commit()
        return {"ok": True, "eventId": row.event_id}

@app.post("/api/events/batch")
def ingest_batch(payloads: List[FatigueEventPayload]):
    with Session(engine) as s:
        count = 0
        for p in payloads:
            upsert_vehicle(s, p.vehicleId)
            if s.get(FatigueEvent, p.eventId):
                continue
            row = payload_to_row(p)
            s.add(row)
            v = s.get(Vehicle, p.vehicleId)
            if v:
                v.last_score = p.maxFatigueScore
                v.last_state = p.severity.value
                v.last_seen = datetime.now(timezone.utc)
                s.add(v)
            count += 1
        s.commit()
        return {"ok": True, "ingested": count}

@app.get("/api/vehicles")
def list_vehicles(limit: int = 100):
    limit = max(1, min(limit, 200))
    with Session(engine) as s:
        vs = s.exec(select(Vehicle).limit(limit)).all()
        return [{"vehicleId": v.vehicle_id, "name": v.name, "lastState": v.last_state, "lastScore": v.last_score, "lastSeen": v.last_seen.isoformat() if v.last_seen else None} for v in vs]

@app.get("/api/vehicles/{vid}")
def get_vehicle(vid: str):
    with Session(engine) as s:
        v = s.get(Vehicle, vid)
        if not v: raise HTTPException(404, "vehicle not found")
        return {"vehicleId": v.vehicle_id, "name": v.name, "lastState": v.last_state, "lastScore": v.last_score, "lastSeen": v.last_seen.isoformat() if v.last_seen else None}

@app.get("/api/vehicles/{vid}/events")
def vehicle_events(vid: str, limit: int = 50):
    limit = max(1, min(limit, 200))  # cap to prevent OOM
    with Session(engine) as s:
        rows = s.exec(select(FatigueEvent).where(FatigueEvent.vehicle_id==vid).order_by(FatigueEvent.timestamp_start.desc()).limit(limit)).all()
        return [{"eventId": r.event_id, "timestampStart": r.timestamp_start.isoformat(), "timestampEnd": r.timestamp_end.isoformat(), "durationMs": r.duration_ms, "severity": r.severity, "maxFatigueScore": r.max_fatigue_score, "eyeClosure": r.eye_closure, "yawning": r.yawning, "headPoseAbnormal": r.head_pose_abnormal, "alertTriggered": r.alert_triggered, "recovered": r.recovered, "gps": {"lat": r.gps_lat, "lng": r.gps_lng}} for r in rows]

@app.get("/api/dashboard/summary")
def dashboard_summary():
    with Session(engine) as s:
        total = len(s.exec(select(Vehicle)).all())
        all_events = s.exec(select(FatigueEvent)).all()
        # Use UTC date to match event timestamps (stored UTC)
        from datetime import timezone as _tz
        today = datetime.now(_tz.utc).date()
        today_events = [e for e in all_events if e.timestamp_start.date() == today]
        high = [e for e in today_events if e.severity == "HIGH"]
        return {"totalVehicles": total, "active": total, "fatigueEventsToday": len(today_events), "highRiskEvents": len(high)}

@app.get("/api/health")
def health(): return {"ok": True}

# serve dashboard static if built
import pathlib
dash_dist = pathlib.Path(__file__).parent.parent / "dashboard" / "dist"
dash_src = pathlib.Path(__file__).parent.parent / "dashboard" / "src"
if dash_dist.exists():
    app.mount("/", StaticFiles(directory=str(dash_dist), html=True), name="dashboard")
