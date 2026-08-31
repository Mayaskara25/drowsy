# Commercial Vehicle Safety Platform --- V1 Software Plan

## 1. Purpose

Build a software-first prototype of the first product described in the
strategy document:

> A driver-facing safety system that observes the driver, estimates
> fatigue locally, warns the driver through the phone itself, and
> records safety events.

For this prototype, **do not build vehicle control, external speakers,
haptic hardware, CAN/OBD integration, wearables, or advanced ADAS
modules**.

The prototype should prove one complete loop:

**Camera → Android app → face/landmark perception → temporal fatigue
estimation → phone beep → event logging → basic fleet view**

The strategy document describes DDAWS / Driver Monitoring as the entry
capability and specifies local-first detection, with the phone acting as
the main compute and UI device. It also describes the long-term goal as
a multi-signal, personalized driver-state prediction system rather than
a simplistic single-frame drowsiness detector.

------------------------------------------------------------------------

# 2. V1 Scope

## Must build

### Android application

-   Live camera input
-   Driver-facing camera preview
-   Face detection/tracking
-   Face landmarks
-   Eye-state estimation
-   Blink / prolonged eye-closure detection
-   Yawning detection
-   Head-pose estimation
-   Temporal fatigue scoring
-   Driver state machine
-   Driver status UI
-   Escalating fatigue states
-   In-app beep / warning sound using the phone speaker
-   Local event logging
-   Local/offline operation
-   GPS/context capture where available
-   Basic settings/calibration

### AI / perception

-   Lightweight on-device face/face-landmark model
-   Feature extraction from facial geometry
-   Temporal aggregation of features
-   V1 rule-based fatigue engine
-   Architecture prepared for replacement by a learned temporal model
    later

### Backend

-   Event ingestion API
-   Vehicle/device registration
-   Event storage
-   Basic dashboard API

### Web dashboard

-   Vehicle list
-   Current/last-known status
-   Fatigue events
-   Event severity
-   Event duration
-   Alert/intervention history
-   Basic event/trip timeline

------------------------------------------------------------------------

# 3. Explicitly Out of Scope for V1

Do NOT implement these unless the core system is already stable:

-   Autonomous braking
-   Autonomous steering
-   AEBS
-   ESC
-   Lane Departure Warning
-   Blind-spot detection
-   Moving-off protection
-   CAN bus integration
-   OBD-II integration
-   Smartwatch integration
-   Haptic puck
-   External Bluetooth speaker
-   Custom camera manufacturing
-   OEM integration
-   Production certification
-   Large-scale fleet deployment
-   Complex multi-driver identification
-   Accident-reduction claims
-   Cloud video streaming/storage
-   VLM-based driver assessment
-   Large general-purpose vision-language models

The phone's own speaker is the only intervention mechanism required for
this prototype.

------------------------------------------------------------------------

# 4. Recommended AI Architecture

## Core decision

**Do not use a VLM as the drowsiness detector.**

**Do not use YOLO as the core fatigue model.**

The recommended architecture is:

``` text
                  Camera Frame
                       |
                       v
             +-------------------+
             | Face Detection /  |
             | Face Tracking     |
             +---------+---------+
                       |
                       v
             +-------------------+
             | Face Landmarks    |
             | / Face Mesh       |
             +---------+---------+
                       |
          +------------+-------------+
          |            |             |
          v            v             v
       Eyes/EAR     Mouth/MAR    Head Pose/Gaze
          |            |             |
          +------------+-------------+
                       |
                       v
             Temporal Feature Layer
                       |
                       v
             +-------------------+
             | V1 Fatigue Engine |
             | Rule/Score Based  |
             +---------+---------+
                       |
                       v
            Driver State Machine
                       |
          +------------+-------------+
          |            |             |
          v            v             v
        NORMAL     FATIGUE       HIGH RISK
                       |
                       v
                Phone Audio Alert
```

The design deliberately separates:

1.  **Perception** --- what the driver's face is doing
2.  **Feature extraction** --- turning perception into measurable
    signals
3.  **Fatigue inference** --- deciding whether behaviour indicates
    fatigue
4.  **Intervention** --- warning the driver
5.  **Event handling** --- storing/synchronizing the event

This separation allows the perception model and fatigue model to evolve
independently.

------------------------------------------------------------------------

# 5. Model Strategy

## 5.1 Face perception model

Use a lightweight **on-device face/face-landmark model**.

The preferred starting point is a **MediaPipe Face Landmarker /
equivalent lightweight landmark model**, provided its Android/device
performance and licensing fit the project.

The model should provide facial landmarks that allow us to derive:

-   Eye geometry
-   Mouth geometry
-   Nose/face geometry
-   Head orientation
-   Approximate gaze direction

The exact model implementation should remain behind a
`DriverPerceptionEngine` interface.

Do not hard-code the application around a specific model.

Conceptual interface:

``` text
DriverPerceptionEngine
    |
    +-- detectFace()
    +-- trackFace()
    +-- getLandmarks()
    +-- getEyeFeatures()
    +-- getMouthFeatures()
    +-- getHeadPose()
    +-- getGaze()
```

------------------------------------------------------------------------

# 6. Why Not VLM?

A VLM is not appropriate for the V1 real-time fatigue loop.

The prototype requires:

-   Low latency
-   Continuous frame processing
-   Offline operation
-   Predictable behaviour
-   Low compute consumption
-   Deterministic feature extraction
-   Android deployment

A VLM adds unnecessary complexity and does not provide a clear advantage
for measuring eye closure, blink dynamics, yawning, or head pose.

VLMs may be useful for future analysis/debugging tools, but they should
not sit in the safety-critical real-time inference path.

------------------------------------------------------------------------

# 7. Why Not YOLO as the Core?

YOLO can be useful for object detection, but fatigue estimation is
primarily a **facial geometry + temporal behaviour** problem.

For example:

``` text
Single frame:
Eye closed
```

does not mean:

``` text
Driver is drowsy
```

The system needs to determine:

``` text
How long were the eyes closed?
How frequently is this happening?
Are both eyes affected?
Is the driver yawning?
Is head pose changing?
Is the behaviour persistent?
```

Therefore YOLO should not be the core drowsiness model.

A YOLO-family detector can be introduced later if it becomes useful for
other platform capabilities such as: - Road objects - Vehicles -
Pedestrians - Perimeter perception - Additional ADAS modules

That is outside V1.

------------------------------------------------------------------------

# 8. Feature Extraction

The first fatigue engine should receive measurable features rather than
raw images.

## Eye features

Calculate:

-   Eye Aspect Ratio (EAR) or equivalent eyelid-opening metric
-   Left/right eye state
-   Eye closure duration
-   Blink duration
-   Blink frequency
-   Proportion of time eyes are closed over a temporal window
    (PERCLOS-style feature)

## Mouth features

Calculate:

-   Mouth opening ratio / MAR-style feature
-   Mouth-open duration
-   Yawn candidate
-   Yawn duration
-   Yawns per time window

## Head-pose features

Calculate:

-   Pitch
-   Yaw
-   Roll
-   Duration of abnormal pose
-   Frequency of downward head movement

## Gaze features

Where reliable:

-   Approximate gaze direction
-   Forward-looking probability
-   Sustained off-road gaze

## Tracking quality

Always calculate:

``` text
face_present
face_confidence
landmark_confidence
tracking_quality
```

Poor tracking should reduce confidence rather than automatically produce
a fatigue event.

------------------------------------------------------------------------

# 9. Temporal Feature Layer

The system must not make fatigue decisions from individual frames.

Maintain rolling temporal windows.

Example:

``` text
30 seconds of perception data
        |
        v
Feature aggregation
        |
        +-- mean eye closure
        +-- max closure duration
        +-- blink statistics
        +-- yawn count
        +-- head-pose duration
        +-- gaze statistics
        +-- tracking quality
        |
        v
Fatigue inference
```

Use configurable rolling windows rather than hard-coding one duration
throughout the application.

------------------------------------------------------------------------

# 10. V1 Fatigue Engine

Start with a **rule-based / weighted temporal scoring engine**.

Do not begin by training a complicated neural fatigue model.

Example conceptual score:

``` text
Eye closure          ─┐
Prolonged closure     │
Blink dynamics        │
Yawning               ├──> Fatigue Score
Head pose             │
Gaze                  │
Persistence           │
Tracking confidence  ─┘
```

Example prototype states:

``` text
0–30    NORMAL
31–55   ATTENTION
56–75   FATIGUE
76–100  HIGH RISK
```

These are **prototype engineering thresholds**, not medical, legal, or
regulatory thresholds.

All thresholds must be configurable.

------------------------------------------------------------------------

# 11. Fatigue Scoring Requirements

The scoring engine should have:

-   Weighted features
-   Temporal persistence
-   Hysteresis
-   Event confirmation
-   Alert cooldown
-   Recovery conditions
-   Tracking-quality gating

For example:

``` text
Short blink
    → no event

Long eye closure
    → risk increases

Single yawn
    → small risk increase

Repeated yawns
    → larger risk increase

Long eye closure + yawning + abnormal head pose
    → high confidence fatigue

Poor face tracking
    → confidence reduced
```

The goal is to prevent:

``` text
NORMAL
FATIGUE
NORMAL
FATIGUE
NORMAL
```

from occurring every few seconds due to normal driver behaviour.

------------------------------------------------------------------------

# 12. Future Learned Temporal Model

Once real-world prototype data exists, the rule engine can be replaced
or supplemented by a small learned temporal model.

Candidate architecture:

``` text
Feature sequence
      |
      v
1D Temporal CNN / GRU / LSTM
      |
      v
Fatigue probability
```

Input:

``` text
[t0 features]
[t1 features]
[t2 features]
...
[tN features]
```

Output:

``` text
fatigue_probability
```

The learned model should remain small enough for real-time Android
inference.

Potential deployment formats:

-   TensorFlow Lite
-   ONNX Runtime Mobile

The exact runtime should be selected based on the final trained model
and Android performance measurements.

Do not add a learned model merely for the sake of using ML. The V1 rule
engine is acceptable until enough representative field data exists.

------------------------------------------------------------------------

# 13. Android Application

## Recommended stack

-   Kotlin
-   Jetpack Compose
-   CameraX where compatible with the selected camera source
-   Kotlin Coroutines
-   Room
-   Android Location APIs
-   Android Audio APIs
-   WorkManager
-   Retrofit/OkHttp

The AI layer must be isolated behind interfaces.

Conceptual modules:

``` text
camera/
perception/
fatigue/
alerts/
data/
location/
sync/
ui/
```

------------------------------------------------------------------------

# 14. Camera Input Abstraction

The software must NOT be tightly coupled to one camera model.

Create:

``` text
CameraSource
    ├── AndroidFrontCameraSource
    ├── UsbUvcCameraSource
    └── NetworkCameraSource
```

For initial software development, the phone's own front camera may be
used.

Once the perception pipeline works, connect the external NIR/IR camera.

This prevents camera procurement from blocking AI/software development.

Target external-camera characteristics:

-   640×480 or 1280×720 minimum
-   approximately 15--30 FPS
-   stable exposure
-   fixed driver-facing mounting
-   IR/NIR sensitivity for low-light testing
-   Android/UVC compatibility where applicable

Do not make 4K, stereo vision, depth sensing, or automotive-grade
imaging a V1 dependency.

------------------------------------------------------------------------

# 15. Phone Intervention

No external buzzer, speaker, or haptic device.

Use the Android phone's own speaker.

Example:

``` text
ATTENTION
    ↓
No sound / subtle UI

FATIGUE
    ↓
Short beep

HIGH RISK
    ↓
Repeated beep + warning
```

Example UI:

``` text
⚠ FATIGUE DETECTED

Please stay alert.

Fatigue score: 82
```

The `AlertManager` should own all intervention logic:

``` text
AlertManager
    |
    +-- playAttentionBeep()
    +-- playFatigueWarning()
    +-- playHighRiskWarning()
    +-- stopAlert()
```

------------------------------------------------------------------------

# 16. Driver State Machine

Implement an explicit state machine:

``` text
                    +---------+
                    | NORMAL  |
                    +----+----+
                         |
                  risk increasing
                         |
                         v
                 +-------+-------+
                 |   ATTENTION   |
                 +-------+-------+
                         |
                  persistent risk
                         |
                         v
                 +-------+-------+
                 |    FATIGUE    |
                 +-------+-------+
                         |
                  severe/persistent
                         |
                         v
                 +-------+-------+
                 |   HIGH RISK   |
                 +---------------+
```

Recovery:

``` text
FATIGUE / HIGH RISK
        |
        | sustained normal behaviour
        v
      NORMAL
```

Each transition should be timestamped and optionally associated with the
fatigue score.

------------------------------------------------------------------------

# 17. Android UI

The UI should resemble a safety instrument rather than a generic
consumer app.

## Main screen

``` text
+----------------------------------+
| DRIVER SAFETY                    |
| Vehicle: DEMO-001                |
+----------------------------------+
|                                  |
|       [ LIVE CAMERA ]            |
|                                  |
|       Driver detected            |
|                                  |
+----------------------------------+
| STATUS                           |
|                                  |
| 🟢 ALERT                         |
|                                  |
| Fatigue score       18 / 100     |
| Eye closure         Normal       |
| Yawning             No           |
| Head pose           Normal       |
| Tracking            Good         |
+----------------------------------+
| Events today: 2                  |
+----------------------------------+
```

During an event:

``` text
+----------------------------------+
| ⚠ FATIGUE DETECTED               |
|                                  |
| Fatigue score       82 / 100     |
| Eye closure         Prolonged    |
| Yawning             Detected     |
|                                  |
| 🔊 ALERT PLAYING                 |
+----------------------------------+
```

------------------------------------------------------------------------

# 18. Calibration

Provide a simple first-run calibration:

``` text
1. Position camera
2. Driver looks forward
3. Detect face
4. Establish baseline
5. Start monitoring
```

Store:

-   baseline head pose
-   expected face region
-   tracking quality
-   optional individual baseline features

Keep calibration simple.

------------------------------------------------------------------------

# 19. Local Event Model

Each event should contain approximately:

``` json
{
  "eventId": "...",
  "deviceId": "...",
  "vehicleId": "...",
  "timestampStart": "...",
  "timestampEnd": "...",
  "durationMs": 4200,
  "severity": "HIGH",
  "maxFatigueScore": 87,
  "eyeClosure": true,
  "yawning": true,
  "headPoseAbnormal": true,
  "alertTriggered": true,
  "recovered": true,
  "gps": {
    "lat": 0,
    "lng": 0
  }
}
```

Do NOT upload continuous driver video in V1.

The primary cloud data product should be **structured event metadata**,
not continuous surveillance footage.

------------------------------------------------------------------------

# 20. Local Database

Use Room.

Suggested tables:

``` text
Device
Vehicle
Driver
Trip
FatigueEvent
AlertEvent
SyncQueue
AppSettings
Calibration
```

Core data flow:

``` text
Detection
   ↓
Local DB
   ↓
Sync Queue
   ↓
Backend when online
```

Never make fatigue detection or phone alerts dependent on a successful
API request.

------------------------------------------------------------------------

# 21. Backend

Keep the backend deliberately small.

## Suggested API

``` text
POST /api/devices/register
POST /api/vehicles/register
POST /api/events
POST /api/events/batch

GET  /api/vehicles
GET  /api/vehicles/{id}
GET  /api/vehicles/{id}/events
GET  /api/dashboard/summary
```

Authentication can remain simple during the prototype stage.

Do not spend time building enterprise IAM before the end-to-end
detection loop works.

------------------------------------------------------------------------

# 22. Database

Use a relational database.

Suggested initial schema:

``` text
vehicles
devices
drivers
trips
fatigue_events
alert_events
```

Useful indexes:

``` text
vehicle_id
timestamp
severity
```

Store event metadata rather than raw camera streams.

------------------------------------------------------------------------

# 23. Fleet Dashboard

Build a simple web dashboard.

## Dashboard summary

``` text
TOTAL VEHICLES
1

ACTIVE
1

FATIGUE EVENTS TODAY
3

HIGH-RISK EVENTS
1
```

## Vehicle page

``` text
Vehicle: DEMO-001

Current status:
FATIGUE / ALERT

Latest score:
82

Last event:
12:42:17

Today's events:
03
```

## Event timeline

``` text
12:10  Normal
12:42  Fatigue
12:42  Alert
12:43  Recovered
13:15  Normal
```

The dashboard is for demonstration and fleet visibility, not production
fleet management.

------------------------------------------------------------------------

# 24. Offline Behaviour

This is a hard requirement.

### Online

``` text
Camera
 ↓
AI
 ↓
Fatigue
 ↓
Phone beep
 ↓
Local event
 ↓
Backend sync
```

### Offline

``` text
Camera
 ↓
AI
 ↓
Fatigue
 ↓
Phone beep
 ↓
Local event
```

### Internet returns

``` text
Local sync queue
       ↓
Backend
       ↓
Dashboard
```

The core safety loop must continue when connectivity disappears.

------------------------------------------------------------------------

# 25. Testing Strategy

## Perception tests

Test:

-   Open eyes
-   Closed eyes
-   Normal blinking
-   Long eye closure
-   Yawning
-   Looking left/right
-   Looking down
-   Head tilt
-   Face partially obscured
-   Glasses if available
-   Daylight
-   Low light
-   Near-darkness with IR

## Fatigue-engine tests

Test:

-   Normal blink does not trigger fatigue
-   Single yawn does not immediately trigger high risk
-   Sustained eye closure increases risk
-   Repeated fatigue signals increase confidence
-   Recovery reduces risk
-   Poor tracking does not create false fatigue events
-   Hysteresis prevents rapid state oscillation
-   Alert cooldown prevents repeated beeps

## System tests

Test:

-   Camera disconnect
-   Camera reconnect
-   App restart
-   Internet loss
-   Internet recovery
-   GPS unavailable
-   Low battery
-   Camera obstruction
-   Background/foreground transitions

------------------------------------------------------------------------

# 26. Demo Acceptance Criteria

The V1 prototype is successful if a live demonstration can show:

### A. Camera

External camera provides a usable driver view.

### B. Perception

The Android app tracks a driver's face and landmarks in real time.

### C. Fatigue signals

The system identifies: - eye closure - blinking - yawning - head pose

### D. Temporal reasoning

The system does not trigger on a single blink/frame.

### E. Fatigue decision

The system moves through defined driver states.

### F. Alert

A confirmed fatigue event causes a beep from the phone.

### G. Recovery

When the driver returns to normal behaviour, the warning stops.

### H. Event logging

The event appears in local history.

### I. Backend

When connected, the event reaches the backend.

### J. Dashboard

The event appears in the fleet dashboard.

### K. Offline operation

Detection + phone alert continue without internet.

### L. Low-light operation

The NIR/IR camera can provide usable facial perception in a controlled
low-light demonstration.

If these work reliably, the prototype demonstrates the central product
concept.

------------------------------------------------------------------------

# 27. Development Order

Build vertically rather than completing entire subsystems in isolation.

## Stage 1 --- Camera + face

``` text
Camera
→ Android
→ face detected
→ landmarks visible
```

## Stage 2 --- Perception

``` text
Landmarks
→ eyes
→ mouth
→ head pose
→ gaze where reliable
```

## Stage 3 --- Feature extraction

``` text
Perception
→ EAR
→ MAR
→ blink statistics
→ eye-closure duration
→ yawn events
→ head-pose statistics
```

## Stage 4 --- Fatigue engine

``` text
Temporal features
→ rule/score engine
→ fatigue score
→ state machine
```

## Stage 5 --- Intervention

``` text
Fatigue state
→ Android beep
→ warning UI
→ recovery
→ stop alert
```

## Stage 6 --- Local persistence

``` text
Fatigue
→ event
→ Room
```

## Stage 7 --- Backend

``` text
Room
→ sync queue
→ API
→ database
```

## Stage 8 --- Dashboard

``` text
Backend
→ dashboard
→ event timeline
```

## Stage 9 --- NIR/IR camera integration

``` text
External camera
→ camera abstraction
→ same perception pipeline
```

## Stage 10 --- Field testing

Test the complete system in a stationary vehicle first, then proceed to
controlled real-vehicle testing.

------------------------------------------------------------------------

# 28. AI-Agent Development Structure

The implementation should be divided into independent workstreams.

``` text
agents/
├── android-agent.md
├── perception-agent.md
├── fatigue-agent.md
├── backend-agent.md
├── dashboard-agent.md
└── testing-agent.md
```

## Agent responsibilities

### Android Agent

Own: - Android project - UI - camera abstraction - lifecycle - local
database - audio - integration interfaces

### Perception Agent

Own: - face detection - face tracking - landmarks - eye features -
mouth/yawn features - head pose - gaze where feasible - on-device
inference integration

### Fatigue Agent

Own: - temporal feature extraction - fatigue scoring - state machine -
thresholds - hysteresis - alert triggering contract

### Backend Agent

Own: - REST API - database - event ingestion - synchronization

### Dashboard Agent

Own: - fleet UI - event history - status - charts/timeline - API
integration

### Testing Agent

Own: - unit tests - perception test cases - fatigue-engine tests -
integration tests - offline/online tests - performance measurements

Agents must use clearly defined interfaces and must not rewrite another
agent's subsystem without agreement.

------------------------------------------------------------------------

# 29. Repository Structure

``` text
commercial-vehicle-safety/
│
├── android/
│   ├── app/
│   ├── camera/
│   ├── perception/
│   ├── fatigue/
│   ├── alerts/
│   └── data/
│
├── backend/
│   ├── api/
│   ├── models/
│   ├── services/
│   └── database/
│
├── dashboard/
│
├── docs/
│
├── tests/
│
├── agents/
│
├── AGENTS.md
└── PLAN.md
```

------------------------------------------------------------------------

# 30. Important Engineering Rules

1.  **Do not use a VLM in the real-time fatigue path.**
2.  **Do not make YOLO the core drowsiness model.**
3.  Keep face perception and fatigue inference as separate modules.
4.  Start with landmarks + engineered temporal features.
5.  Start with a rule-based fatigue engine.
6.  Introduce a learned temporal model only after representative data
    exists.
7.  Keep all inference local to the Android device.
8.  Never require cloud connectivity for detection or alerts.
9.  Do not upload continuous driver video by default.
10. Keep camera input replaceable.
11. Keep all fatigue thresholds configurable.
12. Measure false positives and latency before claiming the system
    works.
13. Treat prototype thresholds as engineering values, not regulatory
    thresholds.
14. Do not claim accident reduction from a small prototype.
15. Do not add advanced ADAS capabilities until the core
    driver-monitoring loop is stable.

------------------------------------------------------------------------

# 31. Prototype Hardware Boundary

For the software milestone, the physical setup is:

``` text
                ┌─────────────────┐
                │ NIR / IR Camera │
                └────────┬────────┘
                         │
                         ▼
                ┌─────────────────┐
                │ Android Phone   │
                │                 │
                │ Perception AI   │
                │ Fatigue Engine  │
                │ UI              │
                │ GPS             │
                │ Database        │
                │ Audio           │
                └─────────────────┘
                         │
                         ▼
                   Phone Speaker
```

No external speaker.

No buzzer.

No Raspberry Pi.

No Jetson.

No CAN adapter.

No smartwatch.

No haptic hardware.

------------------------------------------------------------------------

# 32. NIR/IR Camera Strategy

The external camera is not a software dependency during early
development.

### Development

``` text
Phone front camera
       ↓
Software pipeline
```

### Prototype

``` text
NIR/IR camera
       ↓
CameraSource abstraction
       ↓
Same software pipeline
```

For the inexpensive prototype, prioritize a standard USB/UVC-compatible
IR/night-vision camera.

The camera does not need: - 4K - stereo vision - depth - automotive
certification

Before purchase, verify: - UVC support - Android/OTG compatibility -
standard video output - actual IR/NIR sensitivity - IR illumination if
required - 720p/1080p frame rate - focus distance appropriate for a
driver's face

------------------------------------------------------------------------

# 33. Definition of Done

V1 is considered complete when:

-   [ ] Android app builds and installs
-   [ ] Phone-camera development mode works
-   [ ] External NIR/IR camera input works
-   [ ] Real-time face tracking works
-   [ ] Face landmarks work
-   [ ] Eye state works
-   [ ] Blink statistics work
-   [ ] Prolonged eye closure works
-   [ ] Yawning detection works
-   [ ] Head pose works
-   [ ] Temporal feature extraction works
-   [ ] Fatigue score works
-   [ ] Driver state machine works
-   [ ] Phone beep is triggered
-   [ ] Alert stops after recovery
-   [ ] Events are stored locally
-   [ ] App works without internet
-   [ ] Events sync after connectivity returns
-   [ ] Backend stores events
-   [ ] Dashboard displays events
-   [ ] Low-light/IR camera test completed
-   [ ] False-positive testing completed
-   [ ] End-to-end vehicle demonstration works

------------------------------------------------------------------------

# 34. Final V1 Objective

The first prototype is **not**:

> "An AI model that detects whether someone's eyes are closed."

It is:

> **A local driver-safety application that continuously extracts facial
> and behavioural signals, estimates fatigue over time, intervenes when
> risk becomes significant, and produces structured safety events for
> fleet monitoring.**

The recommended technical path is:

**Lightweight face/landmark perception → engineered temporal features →
V1 rule-based fatigue engine → phone audio intervention → local event
storage → optional cloud synchronization → fleet dashboard.**

This is the minimum architecture that demonstrates the central product
concept while leaving a clean path toward a learned temporal model and
the larger multi-signal Commercial Vehicle Safety Platform.
