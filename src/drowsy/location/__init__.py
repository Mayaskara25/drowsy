"""GPS capture — on-device would use Android Location APIs. Stub for prototype."""
from __future__ import annotations
from dataclasses import dataclass

@dataclass
class GpsFix:
    lat: float = 0.0
    lng: float = 0.0
    has_fix: bool = False

class LocationProvider:
    def get(self) -> GpsFix:
        return GpsFix()

class FixedLocationProvider(LocationProvider):
    def __init__(self, lat: float, lng: float):
        self._fix = GpsFix(lat=lat, lng=lng, has_fix=True)
    def get(self) -> GpsFix:
        return self._fix
