# Badge Turntable (Android)

The capture app described in [../Spec.md](../Spec.md). A OnePlus 13 on a tripod drives an IR turntable inside a light box and reads the [encoder ring](../encoder-ring/README.md) to know the table's angle. It photographs each badge at 0°, 90°, 180° and 270°, records a spin video, and produces a repo-ready badge folder. That folder reaches the PC either through the web editor ([remote control](../Spec.md#remote-control-from-the-editor), planned) or through a Samba share.

Java 17, Views/XML with Material 3, minSdk 31, targetSdk 36. Package `org.snurkle.badgeturntable`.

## Start here

The project was written outside Android Studio and has never been built with Gradle. The next job is to get it compiling and onto the phone. The spec is the source of truth for behaviour, and where this README and the spec disagree, the spec wins.

### What has been checked

- **Unit tests:** the plain-Java core has 19 JUnit tests, all passing when compiled and run with a standalone JDK. They cover the ring angle fit (including perspective), IR encoding, `.ir` parsing, the bundled JAYEGT profile, slugs, badge IDs, `index.md` writing, `session.json` and the spin-video angle log.
- **`motion` package:** compiles with `javac` against the Android 36 `android.jar`.
- **`ring` package:** compiles against the real CameraX 1.4.2 and OpenCV 4.10 jars from the Gradle cache, including `RingDetector`. The OpenCV calls it uses (`Mat(rows, cols, type, ByteBuffer, step)`, `ArucoDetector`, `set_minMarkerPerimeterRate`, `set_detectInvertedMarker`) are confirmed in 4.10.
- **Everything else** (UI, `CameraController`, Media3, smbj, WorkManager) has never been compiled. See [Likely fixes](#likely-fixes).

### Build setup

Android Studio's Upgrade Assistant has already moved the project to **AGP 9.5.0 and Gradle 9.6.0**, and those changes are in the working tree:
- **Plugin version:** `libs.versions.toml` sets `agp = "9.5.0"`.
- **Gradle:** `gradle-wrapper.properties` points at Gradle 9.6.0.
- **Toolchain:** `settings.gradle` adds the Foojay toolchain resolver, and `gradle/gradle-daemon-jvm.properties` pins a JetBrains JDK 21 for the Gradle daemon.
- **Compatibility flags:** `gradle.properties` gains the flags that keep pre-AGP-9 behaviour, such as `android.newDsl=false`, `android.builtInKotlin=false` and `android.uniquePackageNames=false`. They can be retired one at a time later.

The code itself targets Java 17 (`compileOptions` in [app/build.gradle](app/build.gradle)).

The Gradle wrapper jar and the `gradlew` scripts aren't in the repo yet. Let Studio create them, or run `gradle wrapper` once. Then commit them so `./gradlew test` works from a terminal.

The first build copies the repo's [_template/index.md](../../_template/index.md) into `app/src/main/assets/template/`. That copy is gitignored, because the repo file is the source of truth.

### Suggested order

1. Sync and build. Fix compile errors, starting with [Likely fixes](#likely-fixes). Keep `./gradlew test` green.
2. Install on the OnePlus 13 (USB debugging on) and run **Settings → Camera and IR capabilities**. Paste the report into the spec, under *Target phone*.
3. Check phone IR: in Settings → Turntable, use the **Test** buttons. `Step_90` should move the table 90° and stop. The receiver is on the **back** of the base, so turn the base round, or bounce the blaster off a reflector (spec: *IR reach*).
4. Check the ring: open the capture screen, and the overlay should box 10 or more markers and show a steady angle. Turn the table by hand: the angle should change smoothly, without jumps.
5. Calibrate, then try a manual capture, then auto.
6. Then [remote control](../Spec.md#remote-control-from-the-editor) (spec phase 2), starting with its spike.

### Hardware facts (already settled)

| Item | State |
| --- | --- |
| Turntable | JAYEGT 5.74 in. Behaviour of every button is in the spec's JAYEGT table and [../ir-tools/README.md](../ir-tools/README.md#jayegt-574-in-remote) |
| IR codes | NEC, address 0x00, captured with an IguanaIR on the PC and bundled in `app/src/main/assets/profiles/jayegt-5.74.ir`. Replaying them from the PC moves the table. The step is `Step_90` (0x46); the 45/90/180 buttons are swing settings and map to no role |
| Moves | `TurntableController.start()` presses Turn Right (continuous, known direction), `stop()` presses On/Pause. Homing uses Back to Set, and falls back to the ring plus Set Origin if the table has lost its origin |
| Encoder ring | Printed and fitted: a black band (inverted markers), 45 markers at 8°, 9.5 mm tall, generated at 460.9 mm. The app's default marker count is 45, and the detector reads black or white bands |
| Angle fit | Perspective-corrected (`AngleFit`). In simulation it was about 0.1° typical at light-box distances. Frame so the rim is at least about 3 px/mm in the 1280×720 analysis frame |

## Code map

| Package | What's in it |
| --- | --- |
| `ui` | `MainActivity` (sessions list), `SessionEditActivity` (identity, badge ID suggestion, options), `CaptureActivity` (preview, controls, prechecks, dialogs), `SettingsActivity` (Samba, turntable, capture, badge IDs), `ProbeActivity` (capabilities report) |
| `camera` | `CameraController` (CameraX binding, Camera2 manual controls, light box profile, blocking capture and record), `CapabilitiesProbe` |
| `ring` | `RingDetector` (CameraX analyzer, OpenCV ArUco, normal and inverted markers), `AngleFit` (linear fit with outlier rejection, refined with a perspective model), `RingConfig`, `RingReading` |
| `ir` | `IrTransmitter` (ConsumerIrManager), `NecEncoder` (NEC, NECext, Samsung32), `FlipperIrParser`, `ButtonNames` (button name to role; steps need an explicit `Step_` name), `TurntableProfile`, `Presets` (JAYEGT 5.74 in) |
| `motion` | `TurntableController` (start/stop, closed-loop moves, verified homing), `Calibrator` (spin, speed, direction, overshoot, step size check, zero, set origin), `Calibration`, `MoveFailedException` |
| `capture` | `CaptureSequencer` (auto and manual sequences, spin video, extra shots), `View` (the four views and their file names) |
| `media` | `WebImages` (2400 px web copies), `SpinTranscoder` (Media3: trim to one revolution, 1080 px, 24 fps, HDR to SDR, at most 10 MB), `AngleLog` |
| `session` | `Session`, `SessionJson`, `SessionStore` (the `_badges/` and `_capture/` layout), `IndexMdWriter`, `Slugs`, `BadgeIds` |
| `sync` | `SmbClient` (smbj: test, upload via temporary file and rename, badge ID scan), `SyncWorker` (WorkManager), `SmbSettings`, `SecretStore` (Keystore AES-GCM), `KnownBadgeIds` (phone, share and site `badge-ids.json`) |

Related tools elsewhere in the repo:
- [../ir-tools/](../ir-tools/README.md): `iguana_ir.py` captures and replays remote codes on the PC.
- [../encoder-ring/](../encoder-ring/README.md): the ring generator (paper SVG or ZPL labels, `--invert` for black bands).

## Not done yet

- **Remote control from the editor** (spec phase 2): server, mDNS name, pairing, preview WebSocket, shots API, and the editor's Turntable panel. Not started.
- Manual-mode angle dial (current against target). The status line shows the instruction only.
- Lens picker (telephoto, macro) and a zoom slider. Zoom is stored but has no control yet.
- Custom turntable profile editor, USB IR learning, encoder-ring command scan, and the camera IR-learning spike (spec phase 8).
- Session screen file list for re-captioning, reordering and deleting files.
- Speeding up spin videos longer than 30 s (not needed at the JAYEGT's 15 s/rev).
- Aborting cleanly when the app is backgrounded mid-sequence. Today the next capture call fails and the error path runs.
- Warning before overwriting a slug that already exists on the share.

## Likely fixes

These are the places most likely to need changes when it first compiles:
- **AGP 9:** the build files were written for AGP 8.10. With the compatibility flags in `gradle.properties` they should still work, but check `app/build.gradle` (the `android {}` block and the template-copy task) if sync complains.
- **Media3** (`SpinTranscoder`): `EditedMediaItemSequence.Builder`, `Composition.Builder.setHdrMode` and `FrameDropEffect` have moved between releases. Check them against the Media3 version in the catalog.
- **CameraX** (`CameraController`): the Camera2 interop opt-in annotation, and `ResolutionStrategy` constructor arguments.
- **smbj** (`SmbClient`): `File.rename(String, boolean)` and the `FileAttributes` check.
- **Material** (`SessionEditActivity`): `MaterialAutoCompleteTextView.setSimpleItems`.
