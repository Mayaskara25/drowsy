"""Fatigue pipeline: temporal aggregation (§9) + V1 rule engine (§10-11) + state machine (§16)."""
from __future__ import annotations
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Optional, List
import time

from ..config import FatigueThresholds, TemporalConfig, PerceptionConfig
from ..perception import PerceptionFrame
from ..data import DriverState

# ---- Temporal window ----
@dataclass
class TemporalSnapshot:
    perclos: float  # proportion eyes closed in window
    max_closure_ms: int
    mean_ear: float
    blink_count: int
    yawn_count: int
    head_abnormal_ratio: float  # fraction of window head abnormal
    gaze_off_ratio: float
    tracking_quality_mean: float
    eye_closure: bool  # currently eyes closed
    prolonged_closure: bool

@dataclass
class TemporalFeatureBuffer:
    config: TemporalConfig = field(default_factory=TemporalConfig)
    pconfig: PerceptionConfig = field(default_factory=PerceptionConfig)
    frames: Deque[PerceptionFrame] = field(default_factory=deque)
    # internal: track current closure streak
    _closure_start_ms: Optional[int] = None
    _yawn_start_ms: Optional[int] = None
    _yawn_events: Deque[int] = field(default_factory=deque)  # timestamps

    def push(self, f: PerceptionFrame):
        self.frames.append(f)
        # prune by time window
        cutoff = f.timestamp_ms - int(self.config.window_s * 1000)
        while self.frames and self.frames[0].timestamp_ms < cutoff:
            self.frames.popleft()
        # track yawns in window
        if f.mouth and f.mouth.is_yawning:
            if self._yawn_start_ms is None:
                self._yawn_start_ms = f.timestamp_ms
        else:
            if self._yawn_start_ms is not None:
                dur = f.timestamp_ms - self._yawn_start_ms
                if dur >= self.pconfig.mar_yawn_min_duration_ms:
                    self._yawn_events.append(self._yawn_start_ms)
                self._yawn_start_ms = None
        # prune yawns outside window
        while self._yawn_events and self._yawn_events[0] < cutoff:
            self._yawn_events.popleft()

        # closure tracking
        if f.eye and f.eye.eyes_closed and f.face_present:
            if self._closure_start_ms is None:
                self._closure_start_ms = f.timestamp_ms
        else:
            self._closure_start_ms = None

    def snapshot(self, now_ms: int) -> TemporalSnapshot:
        if not self.frames:
            return TemporalSnapshot(0,0,0,0,0,0,0,0,False,False)
        total = len(self.frames)
        closed = sum(1 for fr in self.frames if fr.eye and fr.eye.eyes_closed and fr.face_present)
        perclos = closed / total if total else 0.0
        ears = [fr.eye.ear_mean for fr in self.frames if fr.eye]
        mean_ear = sum(ears)/len(ears) if ears else 0.0
        # max closure: longest contiguous closed streak in window
        max_closure = 0
        cur = 0
        cur_start = None
        for fr in self.frames:
            if fr.eye and fr.eye.eyes_closed and fr.face_present:
                if cur_start is None:
                    cur_start = fr.timestamp_ms
                cur = fr.timestamp_ms - cur_start
                max_closure = max(max_closure, cur)
            else:
                cur_start = None
                cur = 0
        # if currently closing, extend to now
        if self._closure_start_ms is not None:
            cur_dur = now_ms - self._closure_start_ms
            max_closure = max(max_closure, cur_dur)
        # blink count: count transitions open->closed->open with duration < prolonged threshold
        blink_count = 0
        in_closure = False
        closure_start = None
        for fr in self.frames:
            closed_now = bool(fr.eye and fr.eye.eyes_closed and fr.face_present)
            if closed_now and not in_closure:
                in_closure = True
                closure_start = fr.timestamp_ms
            elif not closed_now and in_closure:
                dur = fr.timestamp_ms - (closure_start or fr.timestamp_ms)
                if dur < self.pconfig.eye_closure_min_ms:
                    # will still count? Actually short blink
                    pass
                # count every closure as blink candidate; prolonged handled separately
                blink_count += 1
                in_closure = False
        head_ab = sum(1 for fr in self.frames if fr.head_pose and fr.head_pose.abnormal) / total
        gaze_off = sum(1 for fr in self.frames if fr.gaze and fr.gaze.forward_prob < 0.5) / total
        tq = sum(fr.tracking_quality for fr in self.frames) / total
        currently_closed = bool(self.frames[-1].eye and self.frames[-1].eye.eyes_closed) if self.frames else False
        prolonged = max_closure >= self.pconfig.eye_closure_min_ms
        yawn_count = len(self._yawn_events)
        # if currently yawning long enough, count it
        if self._yawn_start_ms is not None and (now_ms - self._yawn_start_ms) >= self.pconfig.mar_yawn_min_duration_ms:
            yawn_count += 1
        return TemporalSnapshot(
            perclos=perclos, max_closure_ms=max_closure, mean_ear=mean_ear,
            blink_count=blink_count, yawn_count=yawn_count,
            head_abnormal_ratio=head_ab, gaze_off_ratio=gaze_off,
            tracking_quality_mean=tq, eye_closure=currently_closed, prolonged_closure=prolonged
        )


# ---- Fatigue scoring (§10-11) ----
def score_snapshot(s: TemporalSnapshot, th: FatigueThresholds) -> int:
    """Weighted 0-100 score. Tracking-quality gated."""
    # component scores 0-100
    perclos_score = min(100, (s.perclos / th.perclos_high) * 100) if th.perclos_high > 0 else 0
    closure_score = min(100, (s.max_closure_ms / th.closure_high_ms) * 100) if th.closure_high_ms else 0
    yawn_score = min(100, (s.yawn_count / th.yawn_high_count) * 100) if th.yawn_high_count else 0
    head_score = s.head_abnormal_ratio * 100
    gaze_score = s.gaze_off_ratio * 100

    raw = (
        th.w_perclos * perclos_score
        + th.w_max_closure * closure_score
        + th.w_yawn * yawn_score
        + th.w_head_pose * head_score
        + th.w_gaze * gaze_score
    )
    # persistence boost: prolonged closure adds extra
    if s.prolonged_closure and s.max_closure_ms > 1000:
        raw = min(100, raw + 10)
    # tracking gating — poor tracking dampens score (not inflates)
    if s.tracking_quality_mean < th.tracking_quality_gate:
        raw *= (s.tracking_quality_mean / th.tracking_quality_gate)
    return int(max(0, min(100, round(raw))))


@dataclass
class FatigueEngine:
    thresholds: FatigueThresholds = field(default_factory=FatigueThresholds)
    temporal: TemporalConfig = field(default_factory=TemporalConfig)
    perception_cfg: PerceptionConfig = field(default_factory=PerceptionConfig)
    buffer: TemporalFeatureBuffer = field(init=False)
    _last_alert_ms: Optional[int] = None

    def __post_init__(self):
        self.buffer = TemporalFeatureBuffer(config=self.temporal, pconfig=self.perception_cfg)

    def update(self, frame: PerceptionFrame) -> tuple[int, TemporalSnapshot]:
        self.buffer.push(frame)
        snap = self.buffer.snapshot(frame.timestamp_ms)
        score = score_snapshot(snap, self.thresholds)
        return score, snap

    def can_alert(self, now_ms: int) -> bool:
        if self._last_alert_ms is None:
            return True
        return (now_ms - self._last_alert_ms) >= int(self.thresholds.alert_cooldown_s * 1000)

    def mark_alert(self, now_ms: int):
        self._last_alert_ms = now_ms


# ---- Driver State Machine (§16) with hysteresis ----
@dataclass
class DriverStateMachine:
    thresholds: FatigueThresholds = field(default_factory=FatigueThresholds)
    state: DriverState = DriverState.NORMAL
    last_transition_ms: Optional[int] = None
    history: List[tuple[int, DriverState, int]] = field(default_factory=list)  # (ts, state, score)

    def _up_threshold(self, target: DriverState) -> int:
        m = {DriverState.ATTENTION: self.thresholds.attention, DriverState.FATIGUE: self.thresholds.fatigue, DriverState.HIGH_RISK: self.thresholds.high_risk}
        return m[target]

    def step(self, score: int, now_ms: int) -> DriverState:
        prev = self.state
        h = self.thresholds.hysteresis
        # upward transitions
        if self.state == DriverState.NORMAL and score >= self.thresholds.attention:
            self.state = DriverState.ATTENTION
        elif self.state == DriverState.ATTENTION and score >= self.thresholds.fatigue:
            self.state = DriverState.FATIGUE
        elif self.state == DriverState.FATIGUE and score >= self.thresholds.high_risk:
            self.state = DriverState.HIGH_RISK
        elif self.state == DriverState.ATTENTION and score >= self.thresholds.high_risk:
            # skip level if severe
            self.state = DriverState.HIGH_RISK
        elif self.state == DriverState.NORMAL and score >= self.thresholds.fatigue:
            self.state = DriverState.FATIGUE
        # downward with hysteresis
        elif self.state == DriverState.HIGH_RISK and score <= self.thresholds.high_risk - h:
            # need sustained normal: go to FATIGUE first, then gradually down
            self.state = DriverState.FATIGUE if score >= self.thresholds.fatigue - h else (DriverState.ATTENTION if score >= self.thresholds.attention - h else DriverState.NORMAL)
        elif self.state == DriverState.FATIGUE and score <= self.thresholds.fatigue - h:
            self.state = DriverState.ATTENTION if score >= self.thresholds.attention - h else DriverState.NORMAL
        elif self.state == DriverState.ATTENTION and score <= self.thresholds.attention - h:
            self.state = DriverState.NORMAL

        if self.state != prev:
            self.last_transition_ms = now_ms
            self.history.append((now_ms, self.state, score))
        return self.state
