# AGENT_EXECUTION.md

## Purpose

This is the execution brief for AI coding agents working on the Commercial Vehicle Safety / Driver Monitoring prototype.

The existing repository already contains a Python reference implementation covering perception abstractions, EAR/MAR/blink/PERCLOS-style features, head pose/gaze, temporal buffering, fatigue scoring, state management, alerts, local persistence, offline sync, FastAPI, dashboard, simulation, and tests.

**Do not throw away that work.**

The next goal is to move from the Python reference implementation to a real Android edge prototype, while keeping Python as the reference/golden implementation.

---

# 1. Target Architecture

```text
                 ESP32-S3 Camera
                       |
                 JPEG / Wi-Fi
                       |
                       v
              +------------------+
              |  Android Phone   |
              |                  |
              | Camera Receiver  |
              |       ↓          |
              | Face Landmarker  |
              |       ↓          |
              | EAR / MAR        |
              | Head Pose        |
              | Gaze             |
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

## Critical architecture decision

The **ESP32-S3 is initially only the camera/transport device**.

Do NOT move the main drowsiness AI onto the ESP32-S3.

Android performs:
- Face detection/tracking
- Face landmarks
- Feature extraction
- Temporal fatigue inference
- State decisions
- Audio alerts
- Local persistence

ESP32-S3 performs:
- Camera capture
- JPEG compression
- Wi-Fi
- Frame transport
- Basic device status

---

# 2. AI Model Decision

## Do not use a VLM

A VLM must not be in the real-time fatigue path.

The system needs low latency, offline operation, predictable behaviour, and continuous facial measurements. A VLM adds unnecessary complexity.

## Do not use YOLO as the core drowsiness model

Drowsiness is primarily a **facial geometry + temporal behaviour** problem.

Use:

```text
Image
 ↓
Lightweight face detection/tracking
 ↓
Face landmarks
 ↓
Eye / mouth / head features
 ↓
Temporal aggregation
 ↓
Fatigue inference
```

## Preferred perception model

Use a lightweight **MediaPipe Face Landmarker or equivalent mobile-friendly landmark model**, subject to Android performance and licensing verification.

Hide the concrete model behind an interface such as:

```kotlin
interface DriverPerceptionEngine {
    fun processFrame(frame: ImageFrame): PerceptionFrame?
}
```

Do not expose MediaPipe-specific classes outside the perception module.

---

# 3. Immediate Priority: Real Android Perception

Do not wait for the ESP32-S3 hardware.

Use the Android phone's own front camera first:

```text
Phone Camera
    ↓
Face Landmarker
    ↓
Real landmarks
    ↓
EAR / MAR / Head Pose
    ↓
Temporal Features
    ↓
Fatigue Engine
```

Later replace only the camera source:

```text
ESP32-S3
    ↓
Wi-Fi
    ↓
NetworkCameraSource
```

The fatigue engine must not change.

---

# 4. Preserve the Python Reference

The Python implementation is the reference behaviour.

Create equivalent Android components rather than rewriting the algorithm unnecessarily.

```text
Python                     Android

PerceptionFrame      →      PerceptionFrame
LandmarkPerception   →      DriverPerceptionEngine
TemporalSnapshot     →      TemporalSnapshot
TemporalBuffer       →      TemporalBuffer
FatigueEngine        →      FatigueEngine
DriverStateMachine   →      DriverStateMachine
AlertManager         →      AlertManager
FatigueEvent         →      FatigueEvent
SyncQueue            →      SyncQueue
```

Use Python as the golden reference for regression comparisons.

---

# 5. Android Project

Recommended stack:

- Kotlin
- Jetpack Compose
- CameraX where appropriate
- Kotlin Coroutines
- Room
- WorkManager
- Android Audio APIs
- Android Location APIs
- Retrofit/OkHttp or equivalent

Logical modules:

```text
android/
├── app/
├── camera/
├── perception/
├── fatigue/
├── alerts/
├── data/
├── sync/
└── ui/
```

Avoid unnecessary architectural complexity.

---

# 6. Camera Abstraction

Create:

```kotlin
interface CameraSource {
    fun start()
    fun stop()
    fun frames(): Flow<CameraFrame>
}
```

Implement:

```text
AndroidFrontCameraSource
UsbUvcCameraSource
NetworkCameraSource
```

First working implementation:

```text
AndroidFrontCameraSource
```

Future ESP32 implementation:

```text
NetworkCameraSource
```

No fatigue code should know the source of the frame.

---

# 7. Real Face Landmarks

Integrate the selected lightweight face-landmark model into Android.

For each usable frame, produce a model-independent perception object containing:

```text
facePresent
faceConfidence
landmarks
landmarkConfidence
trackingQuality
timestamp
```

Where possible expose:

```text
leftEye
rightEye
mouth
nose
faceOrientation
```

Do not leak model-specific objects outside the perception module.

---

# 8. Feature Extraction

Implement real-time features.

## Eye

Required:
- Left-eye EAR
- Right-eye EAR
- Mean EAR
- Eye closure state
- Eye closure duration
- Blink start/end
- Blink duration
- Blink count
- Blink rate
- PERCLOS-style metric

Keep left/right eyes independently available.

## Mouth

Required:
- MAR or equivalent mouth-opening metric
- Mouth-open duration
- Yawn candidate
- Yawn duration
- Yawn count

A single open-mouth frame must not become a yawn.

## Head pose

Estimate:
- Pitch
- Yaw
- Roll

Track persistence and duration of abnormal orientation.

## Gaze

Implement approximate gaze only if sufficiently reliable. It must not block the first working prototype.

## Tracking quality

Always retain:

```text
faceConfidence
landmarkConfidence
trackingQuality
```

Poor tracking should reduce inference confidence, not automatically create fatigue.

---

# 9. Temporal Layer

Never classify drowsiness from individual frames.

Maintain a rolling temporal buffer:

```text
Frame 1
Frame 2
...
Frame N
   ↓
TemporalBuffer
   ↓
TemporalSnapshot
```

Aggregate:
- mean/min EAR
- longest eye closure
- blink count/rate
- PERCLOS
- yawn count/duration
- head-pose duration
- gaze statistics
- tracking quality

Use configurable windows.

---

# 10. V1 Fatigue Engine

Start with the existing **rule/score approach**.

Combine:

```text
PERCLOS
Eye closure
Blink dynamics
Yawning
Head pose
Gaze
Persistence
Tracking quality
```

Weights and thresholds must be configurable.

Example:

```yaml
fatigue:
  perclos_weight: 0.35
  closure_weight: 0.25
  yawn_weight: 0.15
  head_pose_weight: 0.15
  gaze_weight: 0.10

  attention_threshold: 30
  fatigue_threshold: 55
  high_risk_threshold: 75
```

These are prototype engineering parameters, not medical/regulatory thresholds.

---

# 11. Confidence Layer

Separate:

```text
Perception confidence
Fatigue confidence
Decision confidence
```

Example:

```text
Face confidence:       0.97
Landmark confidence:   0.94
Tracking quality:      0.91

Fatigue score:         81
Decision confidence:   0.88
```

Reduce confidence for:
- partial face
- unstable landmarks
- poor lighting
- poor frame quality
- lost tracking

Missing data must not be treated as evidence of fatigue.

---

# 12. Driver State Machine

Maintain:

```text
NORMAL
   ↓
ATTENTION
   ↓
FATIGUE
   ↓
HIGH_RISK
```

Recovery:

```text
HIGH_RISK
   ↓
sustained normal behaviour
   ↓
NORMAL
```

Requirements:
- hysteresis
- persistence
- cooldown
- recovery criteria
- no rapid oscillation

A single blink must never trigger a fatigue state.

---

# 13. Phone Audio Alert

Use the phone's own speaker.

No external speaker, buzzer, or haptic device.

Suggested behaviour:

```text
NORMAL
→ no alert

ATTENTION
→ UI indication

FATIGUE
→ short beep

HIGH_RISK
→ repeated warning beep + strong UI warning
```

Create an `AlertManager` interface:

```kotlin
interface AlertManager {
    fun attention()
    fun fatigueWarning()
    fun highRiskWarning()
    fun stop()
}
```

The fatigue engine requests an alert; it does not directly use Android audio APIs.

---

# 14. Android UI

Keep the UI functional and safety-oriented.

Avoid excessive gradients, unnecessary animations, and visual clutter.

Main screen:

```text
DRIVER SAFETY

[ LIVE CAMERA ]

Status: NORMAL

Fatigue score: 18 / 100

Eyes: Normal
Blinking: Normal
Yawning: No
Head pose: Normal
Tracking: Good

Events today: 2
```

Event screen:

```text
⚠ FATIGUE DETECTED

Fatigue score: 82 / 100

Prolonged eye closure
Yawning detected

ALERT PLAYING
```

---

# 15. Local Storage

Use Room.

Minimum entities:

```text
Device
Vehicle
Trip
FatigueEvent
AlertEvent
Calibration
SyncQueue
AppSettings
```

Core principle:

**Detection and alerts never depend on the network.**

```text
Detection
 ↓
Fatigue
 ↓
Phone alert
 ↓
Local event
 ↓
Sync later
```

---

# 16. Offline-First Behaviour

With no internet:

```text
Camera
 ↓
AI
 ↓
Fatigue
 ↓
Beep
 ↓
Room DB
```

When internet returns:

```text
Room
 ↓
SyncQueue
 ↓
Backend
```

Never block the safety loop on an API request.

---

# 17. ESP32-S3 Camera

When the hardware arrives, use it as:

```text
ESP32-S3
   ↓
Camera sensor
   ↓
JPEG
   ↓
Wi-Fi
   ↓
MJPEG / JPEG endpoint
   ↓
Android
```

Initial firmware responsibilities:
- camera initialization
- frame capture
- JPEG encoding
- Wi-Fi/AP
- HTTP server
- MJPEG stream
- snapshot endpoint
- status endpoint

Suggested endpoints:

```text
GET /stream
GET /snapshot
GET /status
```

Do not implement fatigue AI on the ESP32 for V1.

---

# 18. ESP32-S3 Networking

Support two modes.

## Direct demo mode

Preferred first:

```text
ESP32-S3
    ↓
Wi-Fi AP: DRIVER-CAM
    ↓
Android phone
```

No router required.

## Existing network mode

Later:

```text
ESP32-S3
    ↓
phone hotspot / vehicle Wi-Fi
    ↓
Android
```

Do not let this block development.

---

# 19. ESP32 Image Settings

Start with:

```text
Resolution: 640×480
FPS:        10–20
Format:     JPEG
Quality:    approximately 60–75
```

Then benchmark.

Prioritize stable low latency over maximum resolution.

Android must tolerate dropped frames.

---

# 20. NetworkCameraSource

Implement:

```text
NetworkCameraSource
    ├── MJPEG
    └── JPEG snapshot
```

RTSP is future work.

Support:
- reconnect
- timeout
- malformed frames
- dropped frames
- connection status
- latency measurement

The rest of Android must not care whether the source is:
- phone camera
- USB camera
- ESP32
- future network camera

---

# 21. Performance Instrumentation

Measure:

```text
camera FPS
received FPS
inference FPS
end-to-end latency
CPU usage
memory
battery drain
network bandwidth
dropped frames
tracking confidence
```

Add a development-only overlay:

```text
FPS: 17
Inference: 15 ms
Network: 420 kbps
Face: 0.96
Tracking: 0.93
Fatigue: 22
State: NORMAL
```

Disable it in the clean demo UI.

---

# 22. Python ↔ Android Validation

Given the same perception/feature sequence, compare:

```text
Python reference
       vs
Android implementation
```

Compare:
- EAR
- MAR
- PERCLOS
- closure duration
- yawn detection
- head pose
- fatigue score
- state

Exact floating-point equality is not required. Equivalent behaviour is.

---

# 23. Testing

Extend the existing tests.

## Unit tests

Test:
- EAR
- MAR
- blink detection
- closure duration
- PERCLOS
- yawn detection
- head pose
- fatigue scoring
- hysteresis
- recovery
- confidence handling
- alert cooldown

## Android tests

Test:
- camera lifecycle
- frame processing
- model integration
- state transitions
- audio alerts
- Room persistence
- offline operation
- sync
- reconnect

## Recorded video tests

Create:

```text
tests/recordings/

normal/
blinking/
long_closure/
yawning/
head_down/
looking_away/
glasses/
low_light/
poor_tracking/
```

Use expected outcomes for regression testing where practical.

---

# 24. Development Data Collection

Once the real pipeline works, add a development-only recording mode.

Capture synchronized:

```text
timestamp
frame/video reference
EAR
MAR
PERCLOS
blink rate
closure duration
yawn count
head pose
gaze
tracking quality
fatigue score
state
alert timestamp
recovery timestamp
```

Do not default to uploading continuous video.

Use explicit consent for human recordings.

The purpose is to create data for future model improvement.

---

# 25. Future Learned Temporal Model

Do not make this the first milestone.

After representative data exists:

```text
Temporal feature sequence
        ↓
1D Temporal CNN / GRU / LSTM
        ↓
Fatigue probability
```

Possible deployment:
- TensorFlow Lite
- ONNX Runtime Mobile

Keep the interface:

```text
FatigueInferenceEngine
```

so V1 can use:

```text
RuleBasedFatigueEngine
```

and future versions can use:

```text
TemporalMLFatigueEngine
```

A future hybrid can be:

```text
Rule score
     +
ML probability
     ↓
Decision layer
```

Do not remove the rule system until the learned model is demonstrably better.

---

# 26. Scope Restrictions

Until the core loop works, do NOT add:

- VLM
- YOLO-based drowsiness detection
- lane detection
- blind-spot detection
- AEBS
- ESC
- CAN/OBD
- autonomous vehicle control
- smartwatch integration
- external haptics
- external speaker
- cloud video streaming
- complex fleet management

The prototype's job is to prove:

**real camera → real perception → real fatigue decision → real phone alert → real event**

---

# 27. Agent Responsibilities

## android-agent

Own:
- Android project
- UI
- lifecycle
- camera integration
- local storage
- app integration

## perception-agent

Own:
- face model
- landmarks
- EAR/MAR
- head pose
- gaze
- tracking confidence

## fatigue-agent

Own:
- temporal buffer
- feature aggregation
- fatigue score
- confidence
- state machine
- thresholds

## esp32-agent

Own:
- ESP32-S3 firmware
- camera
- JPEG
- Wi-Fi
- HTTP/MJPEG
- device status

The ESP32 agent can design the protocol now but should not block Android development until hardware arrives.

## backend-agent

Own:
- API
- database
- event ingestion
- sync

## dashboard-agent

Own:
- fleet dashboard
- event history
- status
- timeline

## testing-agent

Own:
- automated tests
- recorded test pipeline
- performance testing
- regression tests

---

# 28. Execution Order

## Milestone 1 — Android camera

```text
Android
 ↓
Phone camera
 ↓
preview
```

## Milestone 2 — Real landmarks

```text
Phone camera
 ↓
Face Landmarker
 ↓
landmarks
```

## Milestone 3 — Real features

```text
landmarks
 ↓
EAR / MAR / head pose
 ↓
live feature overlay
```

## Milestone 4 — Real fatigue

```text
features
 ↓
temporal buffer
 ↓
fatigue score
 ↓
state
```

## Milestone 5 — Real intervention

```text
FATIGUE
 ↓
phone beep
 ↓
recovery
 ↓
beep stops
```

## Milestone 6 — Local persistence

```text
event
 ↓
Room
```

## Milestone 7 — Network camera abstraction

```text
NetworkCameraSource
 ↓
mock MJPEG stream
 ↓
Android perception
```

## Milestone 8 — ESP32-S3

When hardware arrives:

```text
ESP32-S3
 ↓
Wi-Fi
 ↓
MJPEG
 ↓
Android
```

## Milestone 9 — End-to-end demo

```text
ESP32-S3
 ↓
Android
 ↓
Face Landmarker
 ↓
Fatigue
 ↓
Phone beep
 ↓
Event
 ↓
Dashboard
```

---

# 29. Next-Milestone Definition of Done

Before moving to ESP32 hardware, all must work:

- [ ] Android project builds
- [ ] Phone camera produces real frames
- [ ] Face landmark model runs on-device
- [ ] Face tracking works in real time
- [ ] EAR from real landmarks
- [ ] MAR from real landmarks
- [ ] Blink detection
- [ ] Prolonged eye closure
- [ ] PERCLOS-style feature
- [ ] Yawn detection
- [ ] Head pose
- [ ] Tracking confidence
- [ ] Temporal buffer
- [ ] Fatigue score
- [ ] State machine
- [ ] Hysteresis
- [ ] Phone beep
- [ ] Alert recovery
- [ ] Local event storage
- [ ] Offline operation
- [ ] Python reference comparison
- [ ] Basic performance metrics

---

# 30. ESP32-S3 Definition of Done

When hardware arrives:

- [ ] ESP32-S3 camera initializes reliably
- [ ] ESP32-S3 starts Wi-Fi AP
- [ ] Android connects
- [ ] `/snapshot` works
- [ ] `/stream` works
- [ ] Android receives frames
- [ ] Frame latency measured
- [ ] Dropped frames handled
- [ ] Reconnection works
- [ ] Face landmarks work from ESP32 frames
- [ ] Fatigue engine remains unchanged
- [ ] Phone alert works
- [ ] Low-light/IR test completed
- [ ] Complete demo works without internet

---

# 31. Final Principle

The prototype should demonstrate:

> **An inexpensive camera node observes the driver, an Android phone performs local AI inference and temporal fatigue reasoning, the phone immediately warns the driver, and structured safety events can later be synchronized to a fleet platform.**

Optimize for:

**real-time + local + explainable + testable + modular + demonstrable.**
