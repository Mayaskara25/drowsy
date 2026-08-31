"""Perception layer — DriverPerceptionEngine interface + geometry feature extraction (§5, §8).

MediaPipe / real model is behind the interface; this module provides deterministic
geometry math so fatigue pipeline can be tested without a camera.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Protocol, Optional, List
import math
import numpy as np

# ---- Types ----
@dataclass
class Point2D:
    x: float
    y: float

@dataclass
class EyeFeatures:
    ear_left: float
    ear_right: float
    ear_mean: float
    eyes_closed: bool
    closure_confidence: float

@dataclass
class MouthFeatures:
    mar: float
    is_yawning: bool
    mouth_open_ratio: float

@dataclass
class HeadPose:
    pitch: float
    yaw: float
    roll: float
    abnormal: bool

@dataclass
class GazeFeatures:
    yaw: float
    pitch: float
    forward_prob: float

@dataclass
class PerceptionFrame:
    face_present: bool
    face_confidence: float
    landmark_confidence: float
    tracking_quality: float
    eye: Optional[EyeFeatures] = None
    mouth: Optional[MouthFeatures] = None
    head_pose: Optional[HeadPose] = None
    gaze: Optional[GazeFeatures] = None
    timestamp_ms: int = 0


# ---- Geometry helpers (§8) ----
def eye_aspect_ratio(eye_pts: List[Point2D]) -> float:
    """EAR = (||p1-p5||+||p2-p4||) / (2*||p0-p3||)  — 6 points per eye."""
    if len(eye_pts) != 6:
        raise ValueError("EAR needs 6 eye landmark points")
    def dist(a: Point2D, b: Point2D) -> float:
        return math.hypot(a.x - b.x, a.y - b.y)
    v1 = dist(eye_pts[1], eye_pts[5])
    v2 = dist(eye_pts[2], eye_pts[4])
    h = dist(eye_pts[0], eye_pts[3])
    if h < 1e-6:
        return 0.0
    return (v1 + v2) / (2.0 * h)

def mouth_aspect_ratio(mouth_pts: List[Point2D]) -> float:
    """MAR simplified: vertical mouth opening / horizontal width."""
    if len(mouth_pts) < 4:
        return 0.0
    # expect [left, right, top_inner, bottom_inner] or 8-point set
    # Use first 4 if provided; if 8, use derived
    if len(mouth_pts) >= 8:
        # use inner lip
        h = math.hypot(mouth_pts[0].x - mouth_pts[1].x, mouth_pts[0].y - mouth_pts[1].y)
        v = math.hypot(mouth_pts[2].x - mouth_pts[3].x, mouth_pts[2].y - mouth_pts[3].y)
    else:
        h = math.hypot(mouth_pts[0].x - mouth_pts[1].x, mouth_pts[0].y - mouth_pts[1].y)
        v = math.hypot(mouth_pts[2].x - mouth_pts[3].x, mouth_pts[2].y - mouth_pts[3].y)
    if h < 1e-6:
        return 0.0
    return v / h

def estimate_head_pose(landmarks: np.ndarray, pitch_thresh=25, yaw_thresh=30, roll_thresh=25) -> HeadPose:
    """Very lightweight head-pose from 468-point landmarks: use nose tip vs eye centers.
    For prototype determinism, falls back to simple heuristic if landmarks incomplete.
    Replace with solvePnP for production."""
    # landmarks: (N,2) normalized
    try:
        # Use indices ~ MediaPipe: nose 1, left eye 33, right eye 263, chin 152
        nose = landmarks[1] if len(landmarks) > 263 else landmarks[0]
        left_eye = landmarks[33] if len(landmarks) > 33 else landmarks[0]
        right_eye = landmarks[263] if len(landmarks) > 263 else landmarks[0]
        eye_center = (left_eye + right_eye) / 2
        dx = float(nose[0] - eye_center[0])
        dy = float(nose[1] - eye_center[1])
        yaw = float(np.degrees(np.arctan2(dx, 0.15)))  # scale heuristic
        pitch = float(np.degrees(np.arctan2(dy, 0.12)))
        # roll from eye line
        roll = float(np.degrees(np.arctan2(right_eye[1]-left_eye[1], right_eye[0]-left_eye[0])))
        pitch = max(-60, min(60, pitch))
        yaw = max(-60, min(60, yaw))
        roll = max(-45, min(45, roll))
        abnormal = abs(pitch) > pitch_thresh or abs(yaw) > yaw_thresh or abs(roll) > roll_thresh
        return HeadPose(pitch=pitch, yaw=yaw, roll=roll, abnormal=abnormal)
    except Exception:
        return HeadPose(pitch=0, yaw=0, roll=0, abnormal=False)


# ---- Engine interface (§5.1) ----
class DriverPerceptionEngine(Protocol):
    def detect_face(self, frame: np.ndarray) -> bool: ...
    def track_face(self, frame: np.ndarray) -> bool: ...
    def get_landmarks(self, frame: np.ndarray) -> Optional[np.ndarray]: ...
    def get_eye_features(self, landmarks: np.ndarray) -> EyeFeatures: ...
    def get_mouth_features(self, landmarks: np.ndarray) -> MouthFeatures: ...
    def get_head_pose(self, landmarks: np.ndarray) -> HeadPose: ...
    def get_gaze(self, landmarks: np.ndarray) -> GazeFeatures: ...
    def process(self, frame: np.ndarray, timestamp_ms: int) -> PerceptionFrame: ...


class MockPerceptionEngine:
    """Deterministic mock — inject EAR/MAR/head values for testing without camera.
    Also usable as fallback when MediaPipe not installed."""
    def __init__(self, ear_closed_threshold: float = 0.20, mar_yawn_threshold: float = 0.55):
        self.ear_closed_threshold = ear_closed_threshold
        self.mar_yawn_threshold = mar_yawn_threshold
        self._next: Optional[PerceptionFrame] = None

    def inject(self, frame: PerceptionFrame):
        self._next = frame

    def process(self, frame: np.ndarray, timestamp_ms: int) -> PerceptionFrame:
        if self._next is not None:
            f = self._next
            f.timestamp_ms = timestamp_ms
            return f
        # default: no face
        return PerceptionFrame(face_present=False, face_confidence=0, landmark_confidence=0, tracking_quality=0, timestamp_ms=timestamp_ms)

    # helpers to build synthetic landmarks → features
    @staticmethod
    def synthetic_eye_features(ear: float, thresh: float = 0.20) -> EyeFeatures:
        closed = ear < thresh
        return EyeFeatures(ear_left=ear, ear_right=ear, ear_mean=ear, eyes_closed=closed, closure_confidence=1.0 if closed else 0.0)

    @staticmethod
    def synthetic_mouth_features(mar: float, thresh: float = 0.55) -> MouthFeatures:
        return MouthFeatures(mar=mar, is_yawning=mar > thresh, mouth_open_ratio=mar)

    @staticmethod
    def synthetic_head_pose(pitch=0, yaw=0, roll=0) -> HeadPose:
        abnormal = abs(pitch) > 25 or abs(yaw) > 30 or abs(roll) > 25
        return HeadPose(pitch=pitch, yaw=yaw, roll=roll, abnormal=abnormal)


class LandmarkPerceptionEngine:
    """Real geometry engine: expects landmarks array (N,2). No model inside — caller supplies landmarks."""
    def __init__(self, ear_closed_threshold: float = 0.20, mar_yawn_threshold: float = 0.55):
        self.ear_closed_threshold = ear_closed_threshold
        self.mar_yawn_threshold = mar_yawn_threshold

    def get_eye_features(self, landmarks: np.ndarray) -> EyeFeatures:
        # MediaPipe indices: left eye 33,160,158,133,153,144 ; right eye 362,385,387,263,373,380
        # Simplified: if landmarks has those indices, compute real EAR, else fallback
        try:
            if len(landmarks) >= 468:
                left_idx = [33,160,158,133,153,144]
                right_idx = [362,385,387,263,373,380]
                def pts(idx): return [Point2D(float(landmarks[i,0]), float(landmarks[i,1])) for i in idx]
                ear_l = eye_aspect_ratio(pts(left_idx))
                ear_r = eye_aspect_ratio(pts(right_idx))
                ear_m = (ear_l + ear_r)/2
                closed = ear_m < self.ear_closed_threshold
                return EyeFeatures(ear_left=ear_l, ear_right=ear_r, ear_mean=ear_m, eyes_closed=closed, closure_confidence=1.0 if closed else 0.0)
        except Exception:
            pass
        # fallback: cannot compute
        return EyeFeatures(ear_left=0, ear_right=0, ear_mean=0, eyes_closed=False, closure_confidence=0)

    def get_mouth_features(self, landmarks: np.ndarray) -> MouthFeatures:
        try:
            if len(landmarks) >= 468:
                # outer: 61 left, 291 right, 13 top, 14 bottom (simplified)
                h = math.hypot(float(landmarks[61,0]-landmarks[291,0]), float(landmarks[61,1]-landmarks[291,1]))
                v = math.hypot(float(landmarks[13,0]-landmarks[14,0]), float(landmarks[13,1]-landmarks[14,1]))
                mar = (v / h) if h > 1e-6 else 0.0
                return MouthFeatures(mar=mar, is_yawning=mar > self.mar_yawn_threshold, mouth_open_ratio=mar)
        except Exception:
            pass
        return MouthFeatures(mar=0, is_yawning=False, mouth_open_ratio=0)

    def get_head_pose(self, landmarks: np.ndarray) -> HeadPose:
        return estimate_head_pose(landmarks)

    def get_gaze(self, landmarks: np.ndarray) -> GazeFeatures:
        hp = estimate_head_pose(landmarks)
        # coarse forward prob from yaw/pitch
        prob = max(0.0, 1.0 - (abs(hp.yaw)/60 + abs(hp.pitch)/60))
        return GazeFeatures(yaw=hp.yaw, pitch=hp.pitch, forward_prob=prob)

    def process(self, landmarks: np.ndarray, timestamp_ms: int, face_confidence: float = 0.95) -> PerceptionFrame:
        eye = self.get_eye_features(landmarks)
        mouth = self.get_mouth_features(landmarks)
        hp = self.get_head_pose(landmarks)
        gaze = self.get_gaze(landmarks)
        tq = float(face_confidence)
        return PerceptionFrame(
            face_present=True, face_confidence=face_confidence, landmark_confidence=face_confidence,
            tracking_quality=tq, eye=eye, mouth=mouth, head_pose=hp, gaze=gaze, timestamp_ms=timestamp_ms
        )
