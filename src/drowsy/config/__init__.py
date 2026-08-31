"""Configurable thresholds & settings — all fatigue thresholds are prototype engineering values."""
from __future__ import annotations
from dataclasses import dataclass, field

@dataclass
class PerceptionConfig:
    ear_closed_threshold: float = 0.20
    ear_open_threshold: float = 0.25  # hysteresis gap
    mar_yawn_threshold: float = 0.55
    mar_yawn_min_duration_ms: int = 800
    eye_closure_min_ms: int = 400  # below this is a normal blink
    perclos_window_s: float = 30.0
    pitch_abnormal_deg: float = 25.0
    yaw_abnormal_deg: float = 30.0
    roll_abnormal_deg: float = 25.0

@dataclass
class TemporalConfig:
    window_s: float = 30.0
    feature_interval_ms: int = 100  # expected frame interval
    max_window_size: int = 300  # window_s / interval

@dataclass
class FatigueThresholds:
    # score 0-100
    attention: int = 31
    fatigue: int = 56
    high_risk: int = 76
    hysteresis: int = 5  # need to drop hysteresis below threshold to recover
    alert_cooldown_s: float = 8.0
    tracking_quality_gate: float = 0.35  # below this, damp score
    # weights (sum ~1.0, tunable)
    w_perclos: float = 0.35
    w_max_closure: float = 0.25
    w_yawn: float = 0.15
    w_head_pose: float = 0.15
    w_gaze: float = 0.10
    # perclos -> score mapping
    perclos_high: float = 0.30  # 30% eyes closed in window = strong signal
    # closure duration -> score
    closure_high_ms: int = 1500
    # yawn
    yawn_high_count: int = 3  # yawns in window

@dataclass
class AppConfig:
    perception: PerceptionConfig = field(default_factory=PerceptionConfig)
    temporal: TemporalConfig = field(default_factory=TemporalConfig)
    fatigue: FatigueThresholds = field(default_factory=FatigueThresholds)
    device_id: str = "DEMO-DEVICE-001"
    vehicle_id: str = "DEMO-001"
    backend_url: str = "http://localhost:8000"

DEFAULT_CONFIG = AppConfig()
