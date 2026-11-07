# FLOSS Android Microscope

A free/libre open-source computational microscope app for Android. It turns a phone camera mounted over a
specimen into a multi-frame imaging rig: it captures bursts, aligns them, fuses them into a sharper and
less noisy image, optionally reconstructs depth and a 3D surface, and lets you take calibrated micrometer
measurements on the result.

- **License:** GPL-3.0-or-later (see [LICENSE](LICENSE)).
- **Package:** `com.floss.microscope`
- **Min / target SDK:** 26 / 35
- **ABIs:** arm64-v8a, armeabi-v7a, x86_64

## Architecture

The code follows a clean-architecture split:

| Layer | Package | Contents |
|---|---|---|
| Native | `app/src/main/cpp` | C++17 + OpenMP engine: streaming multi-frame arena, sub-pixel super-resolution, Richardson-Lucy deconvolution, MSRCR, demosaicing |
| Domain | `domain/` | Models, repository interfaces, use cases |
| Data | `data/` | Room database, file storage, repository implementations |
| Infrastructure | `infrastructure/` | CameraX, sensors and tremor analysis, OpenCV processing (alignment, fusion, SfM, enhancement), pipeline orchestration, WorkManager worker, artifact export |
| Presentation | `presentation/` | Jetpack Compose (Material 3) screens and view models |
| Diagnostics | `core/logging/` | `FlossLogger`: leveled logging, optional log files, crash capture, exportable bundle |

Dependency injection uses Hilt. Background processing uses WorkManager with `HiltWorkerFactory`.

## Build

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35, NDK 26.1.10909125 and CMake 3.22.1
(both installed through the SDK manager).

```bash
./gradlew :app:assembleDebug
```

On Windows use `gradlew.bat :app:assembleDebug`. The APK is written to
`app/build/outputs/apk/debug/`.

Create `local.properties` with `sdk.dir=/path/to/Android/Sdk` (this file is not committed).

## Debug logging

Verbose logging is on by default in debug builds and off in release builds. You can change it at any
time in **Settings → Diagnostics → Verbose debug logging**.

- Every record goes to logcat under the tag prefix `FLOSS/` (filter with `adb logcat -s "FLOSS/*"`).
- With verbose logging on, records are also written to `files/logs/floss-<timestamp>-<pid>.log` inside the
  app's private storage. The last 10 files are kept.
- **Export debug log** in Settings bundles all retained logs and opens the share sheet, so a bug report
  can be attached to an issue without a computer.
- Uncaught exceptions are written to the log file before the process exits.
- Pipeline stages log their durations and failures with full stack traces.

To collect logs from a connected device:

```bash
adb logcat -s "FLOSS/*:V"
adb shell run-as com.floss.microscope ls files/logs
```

## Contributing

Issues and pull requests are welcome. Please include the exported debug log and device model in bug
reports. Keep changes to the native engine behind the existing JNI signatures in
`NativeCvBridge.kt`, and run `./gradlew :app:assembleDebug` before opening a pull request.
