"""CameraSource abstraction (§14) — replaceable input."""
from __future__ import annotations
from abc import ABC, abstractmethod
from typing import Optional
import numpy as np

class CameraSource(ABC):
    @abstractmethod
    def open(self) -> bool: ...
    @abstractmethod
    def read(self) -> Optional[np.ndarray]: ...  # BGR frame
    @abstractmethod
    def close(self): ...
    @property
    @abstractmethod
    def name(self) -> str: ...

class AndroidFrontCameraSource(CameraSource):
    """Stub — on Android would wrap CameraX. Here returns synthetic frames for dev."""
    def __init__(self, width=640, height=480, fps=15):
        self.width, self.height, self.fps = width, height, fps
        self._opened = False
    @property
    def name(self): return "AndroidFrontCamera"
    def open(self): self._opened = True; return True
    def read(self):
        if not self._opened: return None
        return np.zeros((self.height, self.width, 3), dtype=np.uint8)
    def close(self): self._opened = False

class UsbUvcCameraSource(CameraSource):
    def __init__(self, device_index: int = 0):
        self.device_index = device_index
        self._cap = None
    @property
    def name(self): return f"UsbUvcCamera:{self.device_index}"
    def open(self):
        try:
            import cv2
            self._cap = cv2.VideoCapture(self.device_index)
            return self._cap.isOpened()
        except ImportError:
            return False
    def read(self):
        if self._cap is None: return None
        ok, frame = self._cap.read()
        return frame if ok else None
    def close(self):
        if self._cap: self._cap.release()

class NetworkCameraSource(CameraSource):
    def __init__(self, url: str):
        self.url = url
        self._cap = None
    @property
    def name(self): return f"NetworkCamera:{self.url}"
    def open(self):
        try:
            import cv2
            self._cap = cv2.VideoCapture(self.url)
            return self._cap.isOpened()
        except ImportError:
            return False
    def read(self):
        if self._cap is None: return None
        ok, frame = self._cap.read()
        return frame if ok else None
    def close(self):
        if self._cap: self._cap.release()
