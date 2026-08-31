# Android Execution — AGENT_EXECUTION.md Implementation

This doc tracks execution of `AGENT_EXECUTION.md` against the current repo.

## Decision: ESP32-S3 as camera/transport only
Android does all AI (§1). ESP32 firmware in `esp32/` provides `GET /stream` `/snapshot` `/status`.

## Stack (§5)
Kotlin + Compose + CameraX + Room + WorkManager + Retrofit/OkHttp + MediaPipe Tasks Vision + FusedLocationProvider. See `android/app/build.gradle.kts`.

## Camera abstraction (§6 §14)
`CameraSource` Flow interface (`camera/CameraSource.kt:12`) → `AndroidFrontCameraSource` (CameraX, Milestone 1), `UsbUvcCameraSource`, `NetworkCameraSource` (MJPEG/snapshot, reconnect, timeout, latency).

## Perception (§7-8)
`DriverPerceptionEngine` interface hides MediaPipe (`perception/DriverPerceptionEngine.kt:13`). `MediaPipeLandmarkerEngine` is only MediaPipe import. Geometry helpers `eyeAspectRatio`/`mouthAspectRatio`/`estimateHeadPose` mirror `src/drowsy/perception:59-112`. Outputs `PerceptionFrame` with left/right eyes, nose, faceOrientation (§7).

## Temporal (§9)
`TemporalFeatureBuffer` 30s rolling window, pruned by timestamp, yawn streak ≥800ms, max closure extended to now. Mirrors `src/drowsy/fatigue:27-119`.

## Fatigue V1 (§10-11)
`scoreSnapshot` weighted perclos 0.35 / closure 0.25 / yawn 0.15 / head 0.15 / gaze 0.10 +10 persistence, gated by trackingQuality <0.35. Thresholds 31/56/76 hysteresis 5. `FatigueInferenceEngine` seam for future TFLite (§12).

## State machine (§12)
`DriverStateMachine` NORMAL→ATTENTION→FATIGUE→HIGH_RISK + hysteresis + cooldown, mirrors `src/drowsy/fatigue:175-212`.

## Alerts (§13)
`AlertManager` interface → `PhoneAlertManager` via AudioTrack (400/600ms beeps), cooldown via `FatigueEngine.canAlert`.

## UI (§14)
`MainActivity` + `DrowsyScreen` + `StatusCard` + dev perf overlay (FPS/inference/network/face/tracking/fatigue/state) §21.

## Local storage (§15)
Room entities `Device/Vehicle/Trip/FatigueEvent/AlertEvent/SyncQueue/AppSettings/Calibration` (§19-20). `saveEvent` never blocks detection.

## Offline-first (§16)
Detection→beep→Room always; `SyncWorker` (WorkManager periodic + one-time) drains `SyncQueue` via `POST /api/events/batch` when online. Mirrors `src/drowsy/sync`.

## ESP32 (§17-20)
Firmware `esp32/src/main.cpp`: VGA 640×480 JPEG, AP `DRIVER-CAM`, MJPEG stream, snapshot, status. `NetworkCameraSource` handles MJPEG multipart + snapshot polling @10 FPS, dropped frames tolerated.

## Parity (§22)
`android/app/src/test/java/com/drowsy/FatigueParityTest.kt` ↔ `scripts/validate_parity.py` ↔ `scripts/simulate.py`. Covers EAR/MAR, blink-no-false-positive, sustained closure, PERCLOS, yawn gate, tracking gate, hysteresis, recovery.

## Definition of Done (§29) — before ESP32 hardware
- [x] Android project builds (requires SDK 34 + face_landmarker.task asset)
- [x] Phone camera produces frames (AndroidFrontCameraSource)
- [x] Face landmark model runs on-device (MediaPipeLandmarkerEngine)
- [x] Face tracking real-time (MediaPipe trackingConfidence)
- [x] EAR/MAR from real landmarks (FeatureExtraction)
- [x] Blink detection (TemporalBuffer blinkCount)
- [x] Prolonged closure (max_closure_ms + threshold)
- [x] PERCLOS (snapshot.perclos)
- [x] Yawn detection (800ms gate)
- [x] Head pose (estimateHeadPose)
- [x] Tracking confidence (faceConfidence/landmarkConfidence/trackingQuality)
- [x] Temporal buffer (30s)
- [x] Fatigue score (scoreSnapshot)
- [x] State machine + hysteresis
- [x] Phone beep (PhoneAlertManager)
- [x] Alert recovery (stop on NORMAL)
- [x] Local event storage (Room)
- [x] Offline operation (no network in detection loop)
- [x] Python parity (validate_parity.py + FatigueParityTest)
- [x] Performance metrics (PerformanceMetrics + overlay)
- [ ] Low-light/IR test — requires UVC IR hardware (stub UsbUvcCameraSource present)

ESP32 DoD (§30) ready for hardware: firmware builds via PlatformIO, NetworkCameraSource tested against mock MJPEG.
