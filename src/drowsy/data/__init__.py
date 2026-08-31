"""Data models: local event model + DB entities. Mirrors §19-20, §22."""
from __future__ import annotations
import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field
from sqlmodel import SQLModel, Field as SQLField

# ---- Enums ----
class Severity(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"

class DriverState(str, Enum):
    NORMAL = "NORMAL"
    ATTENTION = "ATTENTION"
    FATIGUE = "FATIGUE"
    HIGH_RISK = "HIGH_RISK"

# ---- Pydantic event (wire format) ----
class Gps(BaseModel):
    lat: float = 0.0
    lng: float = 0.0

class FatigueEventPayload(BaseModel):
    eventId: str = Field(default_factory=lambda: str(uuid.uuid4()))
    deviceId: str
    vehicleId: str
    timestampStart: str  # ISO8601
    timestampEnd: str
    durationMs: int
    severity: Severity
    maxFatigueScore: int = Field(ge=0, le=100)
    eyeClosure: bool = False
    yawning: bool = False
    headPoseAbnormal: bool = False
    alertTriggered: bool = False
    recovered: bool = False
    gps: Gps = Field(default_factory=Gps)
    trackingQuality: float = 1.0

class DeviceRegister(BaseModel):
    deviceId: str
    vehicleId: str
    name: Optional[str] = None

class VehicleRegister(BaseModel):
    vehicleId: str
    name: Optional[str] = None
    fleetId: Optional[str] = None

# ---- SQLModel tables (§20, §22) ----
class Device(SQLModel, table=True):
    device_id: str = SQLField(primary_key=True)
    vehicle_id: str = SQLField(index=True)
    name: Optional[str] = None
    created_at: datetime = SQLField(default_factory=lambda: datetime.now(timezone.utc))

class Vehicle(SQLModel, table=True):
    vehicle_id: str = SQLField(primary_key=True)
    name: Optional[str] = None
    fleet_id: Optional[str] = None
    created_at: datetime = SQLField(default_factory=lambda: datetime.now(timezone.utc))
    last_score: int = 0
    last_state: str = DriverState.NORMAL.value
    last_seen: Optional[datetime] = None

class FatigueEvent(SQLModel, table=True):
    __tablename__ = "fatigue_events"
    event_id: str = SQLField(primary_key=True)
    device_id: str = SQLField(index=True)
    vehicle_id: str = SQLField(index=True, foreign_key="vehicle.vehicle_id")
    timestamp_start: datetime
    timestamp_end: datetime
    duration_ms: int
    severity: str = SQLField(index=True)
    max_fatigue_score: int
    eye_closure: bool = False
    yawning: bool = False
    head_pose_abnormal: bool = False
    alert_triggered: bool = False
    recovered: bool = False
    gps_lat: float = 0.0
    gps_lng: float = 0.0
    tracking_quality: float = 1.0
    synced: bool = SQLField(default=False, index=True)

class AlertEvent(SQLModel, table=True):
    __tablename__ = "alert_events"
    alert_id: str = SQLField(primary_key=True, default_factory=lambda: str(uuid.uuid4()))
    event_id: Optional[str] = SQLField(default=None, foreign_key="fatigue_events.event_id")
    device_id: str
    vehicle_id: str
    timestamp: datetime = SQLField(default_factory=lambda: datetime.now(timezone.utc))
    alert_type: str  # ATTENTION / FATIGUE / HIGH_RISK
    score: int

class SyncQueue(SQLModel, table=True):
    __tablename__ = "sync_queue"
    queue_id: str = SQLField(primary_key=True, default_factory=lambda: str(uuid.uuid4()))
    event_id: str = SQLField(foreign_key="fatigue_events.event_id")
    attempts: int = 0
    created_at: datetime = SQLField(default_factory=lambda: datetime.now(timezone.utc))
    last_attempt: Optional[datetime] = None

class AppSettings(SQLModel, table=True):
    __tablename__ = "app_settings"
    key: str = SQLField(primary_key=True)
    value: str

class Calibration(SQLModel, table=True):
    calibration_id: str = SQLField(primary_key=True, default_factory=lambda: str(uuid.uuid4()))
    device_id: str
    baseline_pitch: float = 0.0
    baseline_yaw: float = 0.0
    baseline_roll: float = 0.0
    created_at: datetime = SQLField(default_factory=lambda: datetime.now(timezone.utc))

def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()

def payload_to_row(p: FatigueEventPayload) -> FatigueEvent:
    return FatigueEvent(
        event_id=p.eventId,
        device_id=p.deviceId,
        vehicle_id=p.vehicleId,
        timestamp_start=datetime.fromisoformat(p.timestampStart),
        timestamp_end=datetime.fromisoformat(p.timestampEnd),
        duration_ms=p.durationMs,
        severity=p.severity.value,
        max_fatigue_score=p.maxFatigueScore,
        eye_closure=p.eyeClosure,
        yawning=p.yawning,
        head_pose_abnormal=p.headPoseAbnormal,
        alert_triggered=p.alertTriggered,
        recovered=p.recovered,
        gps_lat=p.gps.lat,
        gps_lng=p.gps.lng,
        tracking_quality=p.trackingQuality,
    )
