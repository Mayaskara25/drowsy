"""AlertManager (§15) — phone speaker beeps. Platform-agnostic: on Android would use AudioTrack."""
from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional
import time

class AlertLevel(str, Enum):
    NONE = "NONE"
    ATTENTION = "ATTENTION"
    FATIGUE = "FATIGUE"
    HIGH_RISK = "HIGH_RISK"

@dataclass
class AlertManager:
    beep_on_attention: bool = False
    _active: AlertLevel = AlertLevel.NONE
    _last_beep_ms: Optional[int] = None
    history: list = field(default_factory=list)

    def play_attention_beep(self, now_ms: int):
        if not self.beep_on_attention: return
        self._active = AlertLevel.ATTENTION
        self._last_beep_ms = now_ms
        self.history.append((now_ms, "ATTENTION_BEEP"))
        self._beep(freq=800, duration_ms=150)

    def play_fatigue_warning(self, now_ms: int):
        self._active = AlertLevel.FATIGUE
        self._last_beep_ms = now_ms
        self.history.append((now_ms, "FATIGUE_BEEP"))
        self._beep(freq=1000, duration_ms=400)

    def play_high_risk_warning(self, now_ms: int):
        self._active = AlertLevel.HIGH_RISK
        self._last_beep_ms = now_ms
        self.history.append((now_ms, "HIGH_RISK_BEEP"))
        # repeated beep
        self._beep(freq=1200, duration_ms=600)
        self._beep(freq=1200, duration_ms=600)

    def stop_alert(self, now_ms: int):
        if self._active != AlertLevel.NONE:
            self.history.append((now_ms, "STOP"))
        self._active = AlertLevel.NONE

    def handle_state(self, state_str: str, now_ms: int, can_alert: bool):
        """Map DriverState → audio action with cooldown gating."""
        if state_str == "HIGH_RISK" and can_alert:
            self.play_high_risk_warning(now_ms)
        elif state_str == "FATIGUE" and can_alert:
            self.play_fatigue_warning(now_ms)
        elif state_str == "ATTENTION":
            if self.beep_on_attention and can_alert:
                self.play_attention_beep(now_ms)
        else:
            # NORMAL — stop
            if state_str == "NORMAL":
                self.stop_alert(now_ms)

    def _beep(self, freq=800, duration_ms=200):
        # Try actual audio; fallback to no-op in headless env
        try:
            import numpy as np, sounddevice as sd
            sr = 44100
            t = np.linspace(0, duration_ms/1000, int(sr*duration_ms/1000), False)
            tone = 0.3 * np.sin(2*np.pi*freq*t)
            sd.play(tone, sr)
            sd.wait()
        except Exception:
            pass  # silent in CI / no audio device
