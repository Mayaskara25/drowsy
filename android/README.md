# Android — Drowsy V1 (§13, AGENT_EXECUTION.md)

## Milestones (→ Python reference)
```
Phone Camera → Face Landmarker (MediaPipe) → EAR/MAR/HeadPose → TemporalBuffer (30s)
  → FatigueEngine (weighted 0–100) → DriverStateMachine (hysteresis) → Phone Beep → Room → Sync
```

## Build
```bash
# Requires Android Studio Hedgehog + SDK 34
# Model asset: download face_landmarker.task → app/src/main/assets/
# https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Modules
| Module | File | Mirrors Python |
|--------|------|---------------|
| camera | `camera/CameraSource.kt`, `AndroidFrontCameraSource.kt`, `NetworkCameraSource.kt`, `UsbUvcCameraSource.kt` | `src/drowsy/camera` |
| perception | `perception/DriverPerceptionEngine.kt`, `MediaPipeLandmarkerEngine.kt` | `src/drowsy/perception` |
| fatigue | `fatigue/TemporalBuffer.kt`, `FatigueEngine.kt`, `DriverStateMachine.kt` | `src/drowsy/fatigue` |
| alerts | `alerts/AlertManager.kt` | `src/drowsy/alerts` |
| data | `data/Entities.kt`, `Daos.kt`, `AppDatabase.kt` (Room) | `src/drowsy/data` + `sync` |
| sync | `sync/SyncWorker.kt` (WorkManager), `ApiService.kt` | `src/drowsy/sync` |
| location | `location/LocationProvider.kt` | `src/drowsy/location` |
| metrics | `metrics/PerformanceMetrics.kt` | §21 |

Camera swapping is zero-code for fatigue: `AndroidFrontCameraSource` → `NetworkCameraSource("http://192.168.4.1")` for ESP32 (§17-20).

## Key invariants
- Single blink never triggers fatigue (hysteresis + persistence)
- Single open-mouth frame never becomes yawn (800ms gate)
- Poor tracking dampens score, never inflates (§11)
- Detection + beep + DB never depend on network (§16)
- fatigue thresholds are configurable via `FatigueThresholds` / DataStore (§10)

## Tests
```bash
./gradlew :app:testDebugUnitTest          # FatigueParityTest (Python ↔ Kotlin)
./gradlew :app:connectedAndroidTest       # RoomSyncTest
```
Python parity checked against `scripts/simulate.py` deterministic trace (§22).

## ESP32
See `../esp32/` — AP `DRIVER-CAM`, endpoints `/stream` `/snapshot` `/status`.
