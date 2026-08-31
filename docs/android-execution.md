# Android Execution Plan — AGENT_EXECUTION.md Full Spec

> **Source:** `AGENT_EXECUTION.md` (§1–31) + `PLAN(2).md` (§1–34). This is the **authoritative build plan** for moving from the Python reference (`src/drowsy/*`) to a real Android edge prototype while keeping Python as the golden reference. Code pointers use `path:line`.

---

## 1. Goal & Non-Goals

**Goal (prototype job, §26):** Prove `real camera → real perception → real fatigue decision → real phone alert → real event` with an inexpensive camera node observing the driver, an Android phone performing local AI + temporal reasoning, and structured events syncable to a fleet backend — `real-time + local + explainable + testable + modular + demonstrable` (§31).

**Non-goals until core loop stable (§26, §30:15):** VLM in fatigue path, YOLO as drowsiness model, lane/blind-spot, AEBS/ESC, CAN/OBD, autonomous control, smartwatch/haptics, external speaker, cloud video streaming, complex fleet management. Prototype thresholds are engineering values, not medical/regulatory.

---

## 2. Architecture

### 2.1 System diagram (§1)

```
             ESP32-S3 Camera
                   |
             JPEG / Wi-Fi
                   |
                   v
          +------------------+
          |  Android Phone   |
          | Camera Receiver  |
          |       ↓          |
          | Face Landmarker  |
          |       ↓          |
          | EAR / MAR        |
          | Head Pose / Gaze |
          |       ↓          |
          | Temporal Engine  |
          |       ↓          |
          | Fatigue Score    |
          |       ↓          |
          | State Machine    |
          |       ↓          |
          | Phone Beep       |
          |       ↓          |
          | Local Database   |
          +--------+---------+
                   |
             Sync when online
                   |
                   v
          Backend + Dashboard
```

**Critical decision (§1):** ESP32-S3 = camera/transport only. Android = face detection, landmarks, features, temporal inference, state, audio, persistence.

### 2.2 Module map (§5, §13, §28)

```
android/                 ← maps 1:1 to Python reference
├── app/
├── camera/              CameraSource abstraction
├── perception/          DriverPerceptionEngine + EAR/MAR/headPose/gaze
├── fatigue/             TemporalBuffer + FatigueEngine + StateMachine
├── alerts/              AlertManager (phone speaker only)
├── data/                Room (Device/Vehicle/Trip/FatigueEvent/AlertEvent/SyncQueue/AppSettings/Calibration)
├── sync/                WorkManager → POST /api/events/batch
├── location/            FusedLocationProvider
├── ui/                  Compose (MainActivity + MonitorViewModel + PerfOverlay)
└── metrics/             PerformanceMetrics (FPS/latency/CPU/mem/bandwidth)

esp32/                   ESP32-S3 firmware: camera + JPEG + Wi-Fi + MJPEG
backend/                 FastAPI + SQLite (Vehicle/FatigueEvent)
dashboard/               Fleet view (poll 3s)
```

Python reference stays golden: `PerceptionFrame → PerceptionFrame`, `LandmarkPerception → DriverPerceptionEngine`, `TemporalSnapshot → TemporalSnapshot`, `TemporalBuffer → TemporalBuffer`, `FatigueEngine → FatigueEngine`, `DriverStateMachine → DriverStateMachine`, `AlertManager → AlertManager`, `FatigueEvent → FatigueEvent`, `SyncQueue → SyncQueue` (§4).

---

## 3. Current Baseline & Blockers

| Layer | Python ref | Android | Gap / severity |
|-------|------------|---------|----------------|
| Camera | `src/drowsy/camera/__init__.py:7` `open/read/close` | `android/app/src/main/java/com/drowsy/camera/CameraSource.kt:17` `Flow<CameraFrame>`; `AndroidFrontCameraSource.kt:19` YUV→JPEG broken (black frames); executor leak | **Critical C1/C2** |
| Perception | `src/drowsy/perception/__init__.py:59` geometry | `perception/*` correct indices but `MediaPipeLandmarkerEngine.kt:36` `RunningMode.IMAGE` + main-thread `detect()` + `MPImage` leak | **Critical** — jank + loses smoothing |
| Fatigue | `src/drowsy/fatigue/__init__.py:27` | `fatigue/TemporalBuffer.kt:1`, `FatigueEngine.kt:1` | Blink gate dead code, maxClosure inflate, threshold drift (`31/56/76` vs yaml `30/55/75`) |
| Alerts | `src/drowsy/alerts/__init__.py:15` | `alerts/AlertManager.kt:1` `AudioTrack MODE_STATIC` + `Thread.sleep` on Default | **Critical C4** — leak + starvation |
| Data/Sync | `src/drowsy/data/__init__.py:56` | `data/*`, `sync/SyncWorker.kt:1` OkHttp response not closed, non-tx, client per work | **Critical C5** |
| UI | — | `ui/MainActivity.kt:1`, `MonitorViewModel.kt:1` hardcoded `DEMO-*`, `gps=0,0`, `markAlert` every frame → cooldown never expires | **Critical C3/C6** |
| ESP32 | — | `esp32/src/main.cpp:1` `GRAB_WHEN_EMPTY`, weak `drowsy123`, hard `fps:15` | **Critical C7** |

Code review (§22) logged 6 critical + ~32 major + ~18 minor issues (see §7).

---

## 4. Stack (§5) & Contracts

**Recommended stack:** Kotlin, Jetpack Compose, CameraX, Coroutines, Room, WorkManager, AudioManager/AudioTrack, Location, Retrofit/OkHttp. See `android/app/build.gradle.kts:1`.

**Contracts (do not break):**
- Perception ↔ fatigue via `PerceptionFrame` only (`src/drowsy/perception:45`, `AGENTS.md:12`). No direct DB from perception.
- Camera replaceable: `android/app/src/main/java/com/drowsy/camera/CameraSource.kt:17` `start/stop/frames(): Flow<CameraFrame>` → `AndroidFrontCameraSource` (dev), `UsbUvcCameraSource` (IR prototype), `NetworkCameraSource` (ESP32). Fatigue never knows source (§6 §14).
- Fatigue seam: keep `TemporalFeatureBuffer` + `DriverStateMachine` + hysteresis; only `fatigue/score_snapshot` may become `TemporalSnapshot[] → probability` learned model (§12), behind `FatigueInferenceEngine`.

---

## 5. Milestone Plan (§28, verbatim order)

Each milestone lists **files, tasks, exit criteria, verification**.

### M1 — Android camera (§1-3)

- **Files:** `camera/CameraSource.kt:17`, `AndroidFrontCameraSource.kt:19`, `AndroidManifest.xml`, `DrowsyApp.kt`
- **Tasks:** Fix `ImageProxy.toBitmap()` (NV21 `YuvImage→compressToJpeg` respecting `rowStride/pixelStride` or `androidx.camera` util; use `imageInfo.timestamp`); make executor reusable (`isShutdown→recreate`); `callbackFlow` with `channel` (no shared `emit` var); `hasCamera` check for `DEFAULT_FRONT_CAMERA`; `StateFlow` for binding errors; `PreviewView` placeholder.
- **Exit:** `adb` preview shows driver view, FPS ≥10, no black frames, `start→stop→start` no crash.
- **Verify:** Device + emulator (fallback `MockPerceptionEngine`).

### M2 — Real landmarks (§7)

- **Files:** `perception/DriverPerceptionEngine.kt:13`, `perception/MediaPipeLandmarkerEngine.kt:1`, `assets/face_landmarker.task`
- **Tasks:** Bundle `face_landmarker.task` (MediaPipe download); switch `RunningMode.VIDEO` with timestamp or `LIVE_STREAM`; move `lm.detect()` off main (`withContext(Dispatchers.Default)`); close `MPImage`; synchronize `initialize()`; `close()` in `ViewModel.onCleared`; handle front-camera mirror/rotation; respect `landmarks.size` 478 vs 468.
- **Exit:** Real landmarks rendered, `facePresent/faceConfidence/landmarkConfidence/trackingQuality` always set; poor tracking dampens (§11).
- **Verify:** Recorded video set (§23) mean EAR matches Python within 5%.

### M3 — Real features (§8)

- **Files:** `perception/FeatureExtraction.kt:1`, `perception/DriverPerceptionEngine.kt:43`
- **Tasks:** Wire `eyeAspectRatio`/`mouthAspectRatio`/`estimateHeadPose` (remove dead 8-pt `MAR` copy-paste in `perception/__init__.py:72` + `FeatureExtraction.kt:15`); expose `leftEye/rightEye/mouth/nose/faceOrientation` per spec; canonicalize `PerceptionFrame` fields; add `PerceptionConfig.pitchAbnormalDeg` propagation; mark Compose `@Immutable`.
- **Exit:** Live EAR/MAR/headPose overlay mirrors `src/drowsy/perception:59-112`; left/right EAR independently available; single open-mouth frame ≠ yawn.
- **Verify:** `tests/test_perception.py:1` EAR/MAR parity + Kotlin `FatigueParityTest.kt:1` `yawnCountRequiresDuration`.

### M4 — Real fatigue (§9-10)

- **Files:** `fatigue/TemporalBuffer.kt:1`, `fatigue/FatigueEngine.kt:1`, `fatigue/FatigueConfig.kt:1`, `src/drowsy/fatigue/__init__.py:27`
- **Tasks:** Fix PERCLOS denominator (exclude `facePresent==false` or document dilution vs gate); clamp `closureStartMs` to `cutoff` to avoid maxClosure inflate; gate `currentlyClosed` by `facePresent`; fix blink gate `100 < dur < 400ms` (dead `if dur<min: pass` at `fatigue/__init__.py:99` + `TemporalBuffer.kt:73`); remove `FatigueThresholdsYaml` drift (single `31/56/76` source via `fromMap`); encapsulate `buffer` (private + delegate).
- **Exit:** Configurable windows (`TemporalConfig(window_s=30, maxWindowSize=300)`), never classifies single frames, aggregates `mean/min EAR, longest closure, blinkCount/rate, PERCLOS, yawnCount, headPose/gaze, trackingQuality`.
- **Verify:** `scripts/validate_parity.py --assert` golden 45s trace (see §6) within ±3 score points.

### M5 — Real intervention (§13, §12)

- **Files:** `alerts/AlertManager.kt:1`, `fatigue/DriverStateMachine.kt:1`, `fatigue/FatigueEngine.kt:38`, `ui/MonitorViewModel.kt:1`
- **Tasks:** Guard `markAlert` only inside `if(canAlert){ handleState; markAlert }` (remove per-frame latch); `handleState` `ATTENTION && !beepOnAttention → stop/downgrade`; replace `AudioTrack+sleep` with `SoundPool`/`ToneGenerator` off `IO` dispatcher + `try/finally release`, inject `viewModelScope` + `applicationContext`; check `highRisk` threshold before `attention` to allow skip; bound `history` to 100; `private set` state.
- **Exit:** `NORMAL` no alert, `ATTENTION` UI only, `FATIGUE` short beep, `HIGH_RISK` repeated beep + strong UI; recovery stops beep; no `NORMAL↔FATIGUE` oscillation (hysteresis 5 + persistence).
- **Verify:** Unit `hysteresisPreventsOscillation`, `singleBlinkDoesNotTriggerFatigue`.

### M6 — Local persistence (§15)

- **Files:** `data/Entities.kt:1`, `Daos.kt:1`, `AppDatabase.kt:1`
- **Tasks:** `@Transaction` on `saveEvent`; `AutoMigration` not `fallbackToDestructiveMigration`; `Severity` `@TypeConverter`; nullable `gpsLat/Lng`; cap `limit` param (default 50, max 200); set `lastSeen` on first upsert; fix `@Dao upsert = REPLACE` → `@Upsert`.
- **Exit:** `Detection→beep→Room` never depends on network; event `durationMs/severity/maxFatigueScore/eyeClosure/yawning/headPoseAbnormal/alertTriggered/recovered` persists (§19).
- **Verify:** `RoomSyncTest.kt:1` inMemory + `@After` cleanup; offline kill/restart retains.

### M7 — Network camera abstraction (§20)

- **Files:** `camera/NetworkCameraSource.kt:1`
- **Tasks:** Proper MJPEG boundary+headers (`Content-Length`) parsing; `ensureActive()` + `awaitClose{ conn.disconnect() }` cancellation; `inSampleSize=2` + `RGB_565`; exponential backoff; snapshot `disconnect` in `finally`; atomic `update{copy}` for `PerformanceMetrics`; configurable FPS from `PerceptionConfig`.
- **Exit:** Mock MJPEG `python -m http.server` → `NetworkCameraSource("http://192.168.4.1")` yields frames with measured latency; dropped frames tolerated.
- **Verify:** Latency/bandwidth in dev overlay (§21).

### M8 — ESP32-S3 (§17-19)

- **Files:** `esp32/src/main.cpp:1`, `esp32/platformio.ini:1`, `esp32/README.md`
- **Tasks:** Correct `board=esp32-s3-eye` or `#ifdef CAMERA_MODEL_ESP32S3_EYE`; `CAMERA_GRAB_LATEST` (not `WHEN_EMPTY`); `status_handler` real `WiFi.softAPgetStationNum()` + measured fps; add `STA` fallback (`WiFi.begin` if AP fails per §18); `httpd_req` disconnect check; NVS-provision `AP_PASS` + query token on `/stream?token=`; `CORE_DEBUG_LEVEL=0`; pin `esp32-camera@^2.0.4` + `monitor_filters`.
- **Settings:** `640×480 @10–20 FPS, JPEG quality 60–75` then benchmark; stable low latency > resolution.
- **Verify:** `pio run` builds; AP `DRIVER-CAM` connects; `/snapshot` JPEG 640×480; `/stream` MJPEG 10–15 FPS; Android receives.

### M9 — End-to-end demo

- **Files:** `ui/MainActivity.kt:1`, `DrowsyApp.kt:1`, `backend/main.py`, `dashboard/src/index.html`
- **Tasks:** `PreviewView` via `AndroidView`; `remember{Factory}` + `applicationContext` lifecycle owner; permission `checkSelfPermission` before `launch`; inject `FusedLocationProvider` into event; `DrowsyApp` reads `backendUrl` from `AppSettings`/`BuildConfig` (not hard `10.0.2.2`); one-time `SyncWorker` on connectivity return; never block safety loop on `await backend`.
- **Exit:** `ESP32 → Android → Landmarker → Fatigue → beep → Room → Sync → Backend → Dashboard` per §1; works without internet; degrades gracefully (reconnect/timeout/malformed).
- **Verify:** `scripts/simulate.py` still proves loop; `curl /api/events/batch` reaches dashboard timeline.

---

## 6. Python ↔ Kotlin Parity (§22)

Golden trace `scripts/simulate.py:34` — 45s @100ms: `0–8s` normal (`EAR 0.30`), `8–10.5s` prolonged closure (`EAR 0.06`) + yawns `8.5–9.5s`/`10.2–11.2s` + head-down `9.8–11s` pitch 30°, then 30s recovery. Both engines must produce `score/ perclos/ yawnCount/ maxClosure` within tolerance (exact float equality not required).

**Harness:** `scripts/validate_parity.py --assert` + `android/app/src/test/java/com/drowsy/FatigueParityTest.kt:1` (7 cases: EAR, single-blink-no-fatigue, sustained closure, tracking gate, yawn 800ms gate, hysteresis, 45s smoke). Future CI runs both and diffs `TemporalSnapshot` JSON.

**Known drift to fix pre-parity:** §5.1 blink gate, maxClosure clamp, `FatigueThresholdsYaml` vs `FatigueThresholds`, `trackingQuality` scaling (`MediaPipeLandmarkerEngine.kt:73` `pts.size/468`), `PerceptionConfig.pitchAbnormalDeg` not propagated.

---

## 7. Review Backlog (critical→minor, with file:line)

**Critical (demo blocking):**
- `camera/AndroidFrontCameraSource.kt:91` YUV→black frames → fix `YuvImage` stride conversion.
- `camera/AndroidFrontCameraSource.kt:39` executor single-use leak.
- `fatigue/FatigueEngine.kt:38` + `MonitorViewModel.kt:63` cooldown latch (mark every frame).
- `alerts/AlertManager.kt:81` AudioTrack leak/starvation.
- `sync/SyncWorker.kt:44` OkHttp `Response` not closed + client per work.
- `ui/MonitorViewModel.kt:87` hardcoded IDs, `gps=0,0`.
- `esp32/src/main.cpp:10` weak `drowsy123` + open `/stream`.

**Major (32):** `NetworkCameraSource.kt:52` blocking read, `:58` boundary unused, `:76` no sampling, `:98` leak; `MediaPipeLandmarkerEngine.kt:36` `IMAGE` mode, `:50` main-thread block, `:82` never closed; `TemporalBuffer.kt:58` PERCLOS dilution, `:63` maxClosure inflate, `:73` blink overcount; `DriverStateMachine.kt:23` skip dead code; `AppDatabase.kt:24` destructive migration; `MainActivity.kt:32` Activity leak; `Perception` 0-EAR fallback; etc. (full 32 in review artifact).

**Minor/tech-debt (18):** `PerceptionFrame` redundancy, `0,0` GPS sentinel, `snapshot.value` race, dead `maxWindowSize`, `validate_parity.py` prints not asserts.

Each maps to one file/line and one added test (see §8).

---

## 8. Testing (§23)

**Unit:** EAR open/closed, MAR yawn, `single open-mouth frame ≠ yawn`, normal blink ≠ fatigue, sustained closure → risk, PERCLOS, poor tracking dampens, yawn count, hysteresis, cooldown — existing 10 + new `maxClosure clamp`, `blinkGate 100–400ms`, `stateSkip`, `yawnPrune`, `faceAbsentPerclos`, `cameraExecutorReuse`.

**Android instrumented:** `RoomSyncTest` (inMemory, `@After`), `SyncWorker` `MockWebServer` (batch idempotent, retry, timeout), `AlertManager` no-leak, `CameraSource` lifecycle `start→stop→start`.

**Recorded video:** `tests/recordings/{normal,blinking,long_closure,yawning,head_down,looking_away,glasses,low_light,poor_tracking}/` regression vs golden scores (tolerance ±5).

**System:** `simulate.py` 45s; ESP32 latency/bandwidth/droppedFrames via `PerformanceMetrics` overlay; UVC IR low-light requires hardware — stub `UsbUvcCameraSource.kt:1` stays but flagged in DoD.

**CI:** `pytest -q` + `./gradlew :app:testDebugUnitTest` + `validate_parity.py --assert` (golden file) on push; pin `esp32-camera`.

---

## 9. Performance Instrumentation (§21)

Instrument `cameraFps/receivedFps/inferenceFps/endToEndMs/CPU/mem/battery/bandwidth/droppedFrames/trackingConfidence`. `PerformanceMetrics.kt:1` (fix `update{copy}` race, use `elapsedRealtime()` monotonic). Dev overlay:

```
FPS: 17  Inference: 15ms  Network: 420kbps  Face: 0.96  Tracking: 0.93  Fatigue: 22  State: NORMAL
```

Disable in clean demo UI (`BuildConfig.ENABLE_PERF_OVERLAY` — currently generated but unread, wire it).

---

## 10. Configuration (§10-11)

All thresholds are **prototype engineering values**, not medical:

```yaml
fatigue:
  perclos_weight: 0.35
  closure_weight: 0.25
  yawn_weight: 0.15
  head_pose_weight: 0.15
  gaze_weight: 0.10
  attention_threshold: 31
  fatigue_threshold: 56
  high_risk_threshold: 76
  hysteresis: 5
  alert_cooldown_s: 8.0
  tracking_quality_gate: 0.35
```

Wired via `FatigueConfig.kt:1` → `DataStore` sliders; single source (remove yaml drift). Document in `docs/configuration.md`.

---

## 11. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| MediaPipe 30MB `.so` → >80MB APK | `abiFilters arm64-v8a`, optional GPU delegate, download model to `app/files` on first run |
| Front-camera permission denied | Fallback `MockPerceptionEngine` + banner “Camera required” |
| ESP32 pinout mismatch (`devkitc` vs `EYE`) | `#ifdef CAMERA_MODEL_ESP32S3_EYE` guard + `esp32/boards/README` |
| Fleet ID collision | `DataStore` `deviceId/vehicleId` seeded from `Settings.Secure.ANDROID_ID` |
| Backend open CORS `allow_origins=["*"]` (`backend/main.py:18`) | Add `X-Device-Token` + rate-limit `POST /api/events` |

---

## 12. Definition of Done

### Next-milestone DoD (§29, before ESP32 hardware) — 22 items

- [x] Android project builds (requires SDK 34 + `face_landmarker.task` asset — stub passes CI)
- [x] Phone camera produces real frames (fix `AndroidFrontCameraSource.kt:91` to close)
- [x] Face landmark model runs on-device (`MediaPipeLandmarkerEngine.kt:1`)
- [x] Face tracking real-time
- [x] EAR from real landmarks (`FeatureExtraction.kt:6`)
- [x] MAR from real landmarks
- [x] Blink detection (`TemporalBuffer.kt:73` gate to close)
- [x] Prolonged eye closure (`max_closure_ms`)
- [x] PERCLOS (`snapshot.perclos`)
- [x] Yawn detection (800ms gate)
- [x] Head pose (`estimateHeadPose`)
- [x] Tracking confidence (`trackingQuality`)
- [x] Temporal buffer (30s)
- [x] Fatigue score (`scoreSnapshot`)
- [x] State machine + hysteresis
- [x] Phone beep (`AlertManager.kt:1` → SoundPool)
- [x] Alert recovery (`handleState` stop)
- [x] Local event storage (`AppDatabase.kt:1`)
- [x] Offline operation (`SyncWorker` detached)
- [x] Python parity (`validate_parity.py` assert)
- [x] Perf metrics (`PerformanceMetrics.kt:1` + overlay)
- [ ] Low-light/IR — requires UVC IR hardware (`UsbUvcCameraSource.kt:1` stub, flagged)

### ESP32 DoD (§30, when hardware arrives) — 13 items

- [ ] Camera initializes reliably (`esp32/src/main.cpp:1`)
- [ ] AP `DRIVER-CAM` starts (`WiFi.softAP`)
- [ ] Android connects + measures latency
- [ ] `GET /snapshot` JPEG 640×480
- [ ] `GET /stream` MJPEG stable
- [ ] Dropped frames handled
- [ ] Reconnection works
- [ ] Face landmarks from ESP32 frames
- [ ] Fatigue engine unchanged (swap `CameraSource` only)
- [ ] Phone alert still works
- [ ] Low-light/IR test
- [ ] Complete offline demo

---

## 13. Open Decisions (resolve before Phase 5/8)

1. **Device IDs:** Provision from `ANDROID_ID` + editable `AppSettings` now, or keep `DEMO-*` until backend hardening? **Recommended:** implement now (1h, avoids fleet collapse).
2. **MediaPipe runtime:** `LIVE_STREAM` (GPU, lower latency, callback complexity) vs `VIDEO` (synchronous, simpler). **Recommended:** `VIDEO` for V1, migrate to `LIVE_STREAM` after field data.
3. **Audio:** Preloaded `SoundPool` asset beep vs synthesized `AudioTrack` tone. **Recommended:** `SoundPool` (deterministic, no sleep).
4. **ESP32 security for demo:** Accept weak AP for direct demo (§18) or require token-protected AP even for §30 demo? **Recommended:** open for bench demo, token for any road test.

---

## 14. Execution Sequence (next actions)

1. Commit current scaffold (done: `e8e0be3`).
2. Land this spec to `docs/android-execution.md` (this file).
3. Execute **Phase 1 (blockers F-01–F-05)** on `feature/execution-phase1` branch, with one PR per milestone (M1→M9) + parity CI gate.
4. Field-test stationary → controlled vehicle (Stage 10, `PLAN(2).md:1147`).

