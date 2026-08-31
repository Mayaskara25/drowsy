"""Local DB helper + sync queue (§19-20, §24). SQLite via sqlmodel."""
from __future__ import annotations
from datetime import datetime, timezone
from typing import List
from sqlmodel import SQLModel, create_engine, Session, select

from ..data import FatigueEvent, SyncQueue, Vehicle, Device, AlertEvent, FatigueEventPayload, payload_to_row

def init_db(url: str = "sqlite:///drowsy.db"):
    engine = create_engine(url, echo=False)
    SQLModel.metadata.create_all(engine)
    return engine

def save_event(engine, payload: FatigueEventPayload) -> FatigueEvent:
    row = payload_to_row(payload)
    with Session(engine) as s:
        # ensure vehicle exists
        v = s.get(Vehicle, payload.vehicleId)
        if not v:
            v = Vehicle(vehicle_id=payload.vehicleId, name=payload.vehicleId, last_score=payload.maxFatigueScore, last_state=payload.severity.value)
            s.add(v)
        else:
            v.last_score = payload.maxFatigueScore
            v.last_state = payload.severity.value
            v.last_seen = datetime.now(timezone.utc)
            s.add(v)
        s.add(row)
        # enqueue for sync
        sq = SyncQueue(event_id=row.event_id)
        s.add(sq)
        s.commit()
        s.refresh(row)
        return row

def get_unsynced(engine) -> List[FatigueEvent]:
    with Session(engine) as s:
        q = select(FatigueEvent).where(FatigueEvent.synced == False)
        return list(s.exec(q).all())

def mark_synced(engine, event_id: str):
    with Session(engine) as s:
        row = s.get(FatigueEvent, event_id)
        if row:
            row.synced = True
            s.add(row)
            # remove from queue
            qs = s.exec(select(SyncQueue).where(SyncQueue.event_id == event_id)).all()
            for q in qs: s.delete(q)
            s.commit()

async def sync_pending(engine, backend_url: str) -> int:
    """Push unsynced events to backend. Returns count synced."""
    import httpx
    pending = get_unsynced(engine)
    if not pending: return 0
    # batch endpoint
    payloads = []
    for r in pending:
        payloads.append({
            "eventId": r.event_id,
            "deviceId": r.device_id,
            "vehicleId": r.vehicle_id,
            "timestampStart": r.timestamp_start.isoformat(),
            "timestampEnd": r.timestamp_end.isoformat(),
            "durationMs": r.duration_ms,
            "severity": r.severity,
            "maxFatigueScore": r.max_fatigue_score,
            "eyeClosure": r.eye_closure,
            "yawning": r.yawning,
            "headPoseAbnormal": r.head_pose_abnormal,
            "alertTriggered": r.alert_triggered,
            "recovered": r.recovered,
            "gps": {"lat": r.gps_lat, "lng": r.gps_lng},
            "trackingQuality": r.tracking_quality,
        })
    try:
        async with httpx.AsyncClient(timeout=5) as c:
            resp = await c.post(f"{backend_url}/api/events/batch", json=payloads)
            resp.raise_for_status()
            for r in pending:
                mark_synced(engine, r.event_id)
            return len(pending)
    except Exception as e:
        # offline — keep queued
        return 0
