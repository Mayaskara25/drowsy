# Assets — Face Landmarker model

This folder must contain `face_landmarker.task` (30 MB) for `MediaPipeLandmarkerEngine` (§5.1, §7).

The file is **not committed** (gitignored via `*.task` is too large). Download on first clone:

```bash
# from project root
mkdir -p android/app/src/main/assets
curl -L -o android/app/src/main/assets/face_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task

# verify (~30 MB, sha256 varies by version)
ls -lh android/app/src/main/assets/face_landmarker.task
```

Without it the app falls back to `MockPerceptionEngine` (emulator, CI) and `MediaPipeLandmarkerEngine.initialize()` returns `false` — no crash, but no real landmarks.

**Version pin:** MediaPipe Tasks Vision `0.10.14` in `app/build.gradle.kts:76`. If you update the library, re-download the matching task file from https://developers.google.com/mediapipe/solutions/vision/face_landmarker.

**Release:** The model is bundled into the APK/AAB (`assets/`). No runtime download needed after build. Keep `BuildConfig.ENABLE_PERF_OVERLAY=false` in `release` for clean demo.
