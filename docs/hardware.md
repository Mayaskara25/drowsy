# Hardware — Clone-and-Go Bring-up (§17–19, §30, §32)

> **Goal:** Anyone cloning the repo on a *different* machine/device can flash an ESP32-S3 and connect *any* Android phone without editing `src/main.cpp`. This doc is the single source for BOM, wiring, flashing, and smoke test. For software-only validation use `scripts/simulate.py` + phone front camera — hardware is optional until M8 (§28).

---

## 1. BOM — what to buy

| Qty | Part | Notes | Tested | Approx |
|-----|------|-------|--------|--------|
| 1 | **ESP32-S3 dev board with PSRAM** | **Required:** S3 + 8 MB PSRAM + USB-OTG. Use one of the 3 supported boards below. | EYE / DevKitC-1 / XIAO Sense | $8–15 |
| 1 | **Camera module** | OV2640 (included on EYE) or OV3660. 640×480 @10–20 FPS, JPEG. | OV2640 | incl. |
| 1 | **USB-C data cable** | Must be data, not charge-only | — | $3 |
| 1 | **5 V 2 A supply** (or laptop USB) | Camera + Wi-Fi draws ~400 mA peak | — | — |
| 1 | **Android phone, Android 8+** | Any phone with front camera; no Play Services required for core loop (GPS degrades to `0,0` if missing) | Pixel 6, Samsung A52 | — |

**Do NOT need:** external speaker/buzzer, Pi/Jetson, CAN adapter, smartwatch, haptics (§30).

---

## 2. Supported boards — pick one, no code edit

We support 3 pinouts via `build_flags` in `esp32/platformio.ini` (select env). **Do not edit `src/main.cpp`** — pins are `#ifdef`'d.

| Env (PlatformIO) | Board | Camera pins (§19) | How to select |
|------------------|-------|-------------------|---------------|
| `esp32-s3-eye` | **Freenove ESP32-S3-EYE / ESP32-S3-CAM** (recommended) | `D0=5 D1=18 D2=19 D3=21 D4=36 D5=39 D6=34 D7=35 XCLK=0 PCLK=22 VSYNC=25 HREF=23 SDA=26 SCL=27 PWDN=32 RST=-1` | `pio run -e esp32-s3-eye -t upload` |
| `esp32-s3-devkitc-1` | Generic ESP32-S3-DevKitC-1 + external OV2640 | Same as EYE (external camera wired as above) | `pio run -e esp32-s3-devkitc-1 -t upload` |
| `esp32-s3-xiao` | Seeed XIAO ESP32S3 Sense (OV2640 on board) | `D0=10 D1=11 D2=12 D3=14 D4=17 ...` (see `esp32/boards/README.md`) | `pio run -e esp32-s3-xiao -t upload` |

If your board is not listed: copy `esp32/boards/eye.h` to `esp32/boards/myboard.h`, set pins, add an env in `platformio.ini` with `-I include/boards`. No `src/main.cpp` change needed.

Driver fix by OS:

| OS | Driver | Check |
|----|--------|-------|
| Linux | `cp210x` / `ch343` auto | `dmesg \| grep ttyUSB` |
| macOS | CP210x VCP from Silicon Labs | `ls /dev/cu.*` |
| Windows | CP210x / CH343 via Device Manager | COM port in `PlatformIO: Device` |

---

## 3. Secrets — clone-safe Wi-Fi

`esp32/src/main.cpp` **does not** hard-code a credential you must change. It includes `esp32/include/secrets.h` if present, else falls back to demo AP for bench testing.

```bash
# first clone — create your local secrets (gitignored, see .gitignore)
cp esp32/include/secrets.h.example esp32/include/secrets.h
# edit AP_SSID / AP_PASS / STA_SSID / STA_PASS — or keep demo for direct AP mode
```

| Mode | `secrets.h` | Result | Phone connects to |
|------|-------------|--------|-------------------|
| **Direct demo (§18, default)** | no file or `USE_STA 0` | ESP32 AP `DRIVER-CAM` (pass `drowsy123` or your custom) — **no router** | `Wi-Fi → DRIVER-CAM` |
| **Existing network / phone hotspot (§18 later)** | `STA_SSID/STA_PASS` set + `USE_STA 1` | ESP32 joins your Wi-Fi/hotspot, AP still up | Same Wi-Fi as ESP32 |
| **Bench CI** | no secrets needed | AP still up | — |

`secrets.h` and `android/app/src/main/assets/config.json` are gitignored — cloning never leaks your creds. Only `*.example` is committed.

---

## 4. Flash — 3 commands, any host

```bash
# 0. prerequisites (any host: Linux/Mac/Win with Python 3.10+)
pip install -U platformio
# or: pipx install platformio

# 1. clone
git clone https://github.com/Mayaskara25/drowsy.git && cd drowsy

# 2. (optional) set your Wi-Fi
cp esp32/include/secrets.h.example esp32/include/secrets.h
# edit nano esp32/include/secrets.h if you want STA mode; else skip

# 3. flash — pick your board env
pio device list          # find /dev/ttyUSB0, /dev/cu.usbserial-*, COM3
pio run -e esp32-s3-eye -t upload --upload-port /dev/ttyUSB0   # or omit --upload-port to auto
pio device monitor -b 115200   # should print: AP DRIVER-CAM at 192.168.4.1  Camera init ok
# Ctrl-C to exit monitor
```

Build cost: ~90 s first time (toolchain download), ~15 s thereafter. The binary is `640×480 @10 FPS, JPEG quality 85 (≈12 on 0–63 scale per §19)`.

**Troubleshooting:**

| Symptom | Fix |
|---------|-----|
| `Camera init failed 0x105` | Wrong board env → pick another (`-e esp32-s3-xiao`). Check PSRAM enabled (`-DBOARD_HAS_PSRAM`). |
| `Failed to connect` | Hold BOOT, tap RESET, `pio run -t upload` again. Try `115200` vs `921600` baud in `platformio.ini`. |
| No AP visible | `pio device monitor` — see `E (wifi) ...` — power supply too weak; use 5 V 2 A brick, not hub. |
| AP visible but `192.168.4.1` not pingable | Phone joined AP but phone's mobile data still routing — disable mobile data, keep Wi-Fi only. |

---

## 5. Android app — 3 commands + 1 curl, any Android

```bash
# 1. backend (on your laptop, same Wi-Fi as phone when testing NetworkCameraSource)
uv sync --extra dev
uv run uvicorn backend.main:app --reload --host 0.0.0.0 --port 8000
# note your laptop IP: ip addr | grep 192.168  → e.g. 192.168.1.23

# 2. Android config (gitignored, per-device)
cp android/app/src/main/assets/config.json.example android/app/src/main/assets/config.json
# edit: { "backend_url": "http://192.168.1.23:8000", "camera_source": "network", "network_camera_url": "http://192.168.4.1" }
# for phone-only demo: { "camera_source": "front" }

# 3. model (30 MB, gitignored, see assets/README.md)
curl -L -o android/app/src/main/assets/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task

# 4. build + install (any Android, no Play Services required for core loop)
./android/gradlew :app:assembleDebug   # or open android/ in Android Studio
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
# grant: Camera + Location (optional — degrades to 0,0 if denied)
```

**Toggle camera without rebuilding (DataStore):** In-app Settings → Camera Source `Front / Network (ESP32) / UVC`. Or `adb shell` edit `config.json` and restart app.

**Switch back to phone-only at any time:** No ESP32 needed — `MonitorViewModel` uses `AndroidFrontCameraSource` by default if `config.json` missing.

---

## 6. Smoke test — prove the chain in 60 s (no backend needed)

```bash
# 1. Phone joins ESP32
# On phone: Wi-Fi → connect to DRIVER-CAM (pass from secrets.h or drowsy123) → no internet warning → stay connected, disable mobile data

# 2. From phone browser (or laptop if STA mode)
curl http://192.168.4.1/status
# {"uptime":42,"width":640,"height":480,"fps":10,"clients":1}

curl http://192.168.4.1/snapshot --output /tmp/snap.jpg && file /tmp/snap.jpg
# /tmp/snap.jpg: JPEG image data, 640x480

# 3. On phone app
# Open Drowsy → [ LIVE CAMERA ] shows stream → Status: NORMAL → cover eyes 2s → Status → FATIGUE → phone beeps → release → recovers to NORMAL → Events today: 1
# Check local DB (offline-first §16): kill Wi-Fi, trigger fatigue again → still beeps + event queued → rejoin Wi-Fi → event appears in backend

# 4. With backend (optional)
curl http://192.168.1.23:8000/api/dashboard/summary | jq .
curl http://192.168.1.23:8000/api/vehicles/DEMO-001/events?limit=3 | jq .
# dashboard at http://192.168.1.23:8000/
```

**Dropped frames / reconnect:** Unplug ESP32 5 s → app shows `Tracking: Poor` then `No face`, no crash; replug → `Driver detected` within 2 s (`NetworkCameraSource` 2 s backoff + `ensureActive` cancellation).

---

## 7. Performance budget (§19, §21)

Default `640×480 @10 FPS, JPEG 85`. Benchmark on your device and tune in `assets/config.json`:

| Setting | `camera/*.kt` / `platformio.ini` | Tradeoff |
|---------|----------------------------------|----------|
| `640×480 @10 FPS` | `AndroidFrontCameraSource(640,480)` / `vTaskDelay(100)` | Baseline, stable latency |
| `1280×720 @15 FPS` | `FRAMESIZE_HD` / `inSampleSize=2` | Better EAR at distance, higher decode 2× |
| `JPEG 60–75` | `cfg.jpeg_quality 12–20` (0–63 scale) | Lower = better quality; 12≈70; 20≈60 |
| `RGB_565 + inSampleSize=1` | `NetworkCameraSource` opts | Half memory vs ARGB_8888 |

Android tolerates dropped frames — prioritize **stable low latency over max resolution**.

---

## 8. Low-light / IR (§32)

V1 software works in daylight with phone front camera. For NIR prototype: any UVC IR camera (720p, 15–30 FPS, IR illumination) appears as `UsbUvcCameraSource` (`/dev/video0` via OTG). No firmware change. Test in near-darkness: IR illuminator on → `faceConfidence >0.6` required; else `trackingQuality` dampens score (§11) — never creates false fatigue.

---

## 9. One-command verification (no hardware)

```bash
uv run pytest -q                          # 10 passed
uv run python scripts/validate_parity.py  # Parity CI PASSED (golden 10s/15s ±8, final NORMAL)
uv run python scripts/simulate.py         # 45s trace → [EVENT] MEDIUM score=73 dur=29300ms
```

---

## 10. Different device checklist (clone → hardware in 10 min)

- [ ] `git clone … && cp esp32/include/secrets.h.example esp32/include/secrets.h` (optional)
- [ ] `pio device list` → correct `/dev/*` + env (`eye` vs `xiao`)
- [ ] `pio run -e <env> -t upload && pio device monitor -b 115200` → `AP DRIVER-CAM at 192.168.4.1`
- [ ] Phone joins AP, `curl http://192.168.4.1/status` → `fps:10 clients:1`
- [ ] `cp android/app/src/main/assets/config.json.example …` + set `backend_url` to laptop IP + `curl` model task
- [ ] `./gradlew :app:assembleDebug && adb install -r …` → `Status: NORMAL` → prolonged closure → beep

No credential is committed; `*.task`, `secrets.h`, `config.json` are gitignored. Every clone follows the same 3 sections above without editing `src/main.cpp` or `MainActivity.kt`.

