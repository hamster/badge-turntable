# Badge Turntable (Android)

The capture app described in [../Spec.md](../Spec.md): a OnePlus 13 on a tripod drives an IR turntable inside a light box, reads the [encoder ring](../encoder-ring/README.md) to know the table's angle, photographs each badge at 0°, 90°, 180° and 270°, records a spin video, and writes a repo-ready badge folder that syncs to a Samba share.

Java 17, Views/XML with Material 3, minSdk 31, targetSdk 36. Package `org.snurkle.badgeturntable`.

## Status: boilerplate, mostly untested

This was written on a machine without Android Studio:
- **Tested:** the plain-Java core: ring angle fit, IR encoding, `.ir` parsing, slugs, badge IDs, `index.md` writing, `session.json`, and the spin-video angle log. These are covered by JUnit tests (16, all passing) compiled and run with a standalone JDK 17.
- **Never compiled:** everything that touches Android, AndroidX, CameraX, Media3, OpenCV or smbj. Expect a round of compile fixes in Android Studio, most likely in the places listed under [Likely fixes](#likely-fixes).

## Getting started on another machine

1. Install Android Studio (with its bundled JDK 17+) and open this folder (`apps/badge-turntable`).
2. The Gradle wrapper jar isn't checked in. When Studio offers to create the wrapper, accept. Or run `gradle wrapper --gradle-version 8.13` once from a terminal with Gradle installed. `gradle/wrapper/gradle-wrapper.properties` already pins 8.13.
3. Let the IDE sync. Library versions are in [gradle/libs.versions.toml](gradle/libs.versions.toml), and Studio's Upgrade Assistant will offer newer ones.
4. Run the unit tests with `./gradlew test` (or right-click `app/src/test` and choose Run Tests).
5. The first build copies the repo's [_template/index.md](../../_template/index.md) into `app/src/main/assets/template/`. It's gitignored, because the repo copy is the source of truth.
6. Plug in the OnePlus 13 with USB debugging on, and run the app.

## Code map

| Package | What's in it |
| --- | --- |
| `ui` | `MainActivity` (sessions list), `SessionEditActivity` (identity, badge ID suggestion, options), `CaptureActivity` (preview, controls, prechecks, dialogs), `SettingsActivity` (Samba, turntable, capture, badge IDs), `ProbeActivity` (capabilities report) |
| `camera` | `CameraController` (CameraX binding, Camera2 manual controls, light box profile, blocking capture and record), `CapabilitiesProbe` |
| `ring` | `RingDetector` (CameraX analyzer, OpenCV ArUco), `AngleFit` (the linear least-squares fit from the ring README, with outlier rejection), `RingConfig`, `RingReading` |
| `ir` | `IrTransmitter` (ConsumerIrManager), `NecEncoder` (NEC, NECext, Samsung32), `FlipperIrParser`, `ButtonNames` (button name to role), `TurntableProfile`, `Presets` (JAYEGT 5.74 in) |
| `motion` | `TurntableController` (closed-loop moves, homing), `Calibrator` (spin, speed, direction, overshoot, step size, zero), `Calibration`, `MoveFailedException` |
| `capture` | `CaptureSequencer` (auto and manual sequences, spin video, extra shots), `View` (the four views and their file names) |
| `media` | `WebImages` (2400 px web copies), `SpinTranscoder` (Media3: trim to one revolution, 1080 px, 24 fps, HDR to SDR, at most 10 MB), `AngleLog` |
| `session` | `Session`, `SessionJson`, `SessionStore` (the `_badges/` and `_capture/` layout), `IndexMdWriter`, `Slugs`, `BadgeIds` |
| `sync` | `SmbClient` (smbj: test, upload via temporary file and rename, badge ID scan), `SyncWorker` (WorkManager), `SmbSettings`, `SecretStore` (Keystore AES-GCM), `KnownBadgeIds` (phone, share and site `badge-ids.json`) |

## First things to do with the phone

1. **Settings → Camera and IR capabilities.** Check the hardware level, `MANUAL_SENSOR` / `MANUAL_POST_PROCESSING`, the lenses an app can see, the timestamp source, and that the IR blaster supports 38 kHz. Paste the report into the spec.
2. **Capture the JAYEGT remote** with a Flipper (or a Pico + TSOP38238) and save it as `app/src/main/assets/profiles/jayegt-5.74.ir`. Name the buttons so `ButtonNames` maps them automatically:

   | Button | Name in the `.ir` file |
   | --- | --- |
   | On/Pause | `On_pause` |
   | Turn Left / Turn Right | `Turn_left` / `Turn_right` |
   | +Speed / −Speed | `Speed_up` / `Speed_down` |
   | Resume Continuous Rotation | `Continuous` |
   | Intermittent Rotation | `Intermittent` |
   | 45° / 90° / 180° | `45` / `90` / `180` |
   | Set Origin Position | `Set_origin` |
   | Back to Set Position | `Back_to_set` |

3. **Print the encoder ring** for the JAYEGT's rim, then set **Settings → Turntable → Encoder ring marker count** to match the count printed on the sheet.
4. **Calibrate** from the capture screen, then try a capture with **Auto turntable** off (manual) before turning it on.

## Not done yet

- Manual-mode angle dial (current against target). The status line shows the instruction only.
- Lens picker (telephoto, macro) and a zoom slider. Zoom is stored but has no control yet.
- Custom turntable profile editor, USB IR learning, encoder-ring command scan, and the camera IR-learning spike (spec phase 7).
- Session screen file list for re-captioning, reordering and deleting files.
- Speeding up spin videos longer than 30 s (not needed at the JAYEGT's 15 s/rev).
- Aborting cleanly when the app is backgrounded mid-sequence. Today the next capture call fails and the error path runs.
- Warning before overwriting a slug that already exists on the share.

## Likely fixes

These are the places most likely to need changes when it first compiles:
- **Media3** (`SpinTranscoder`): `EditedMediaItemSequence.Builder`, `Composition.Builder.setHdrMode` and `FrameDropEffect` have moved between releases. Check them against the Media3 version in the catalog.
- **CameraX** (`CameraController`): the Camera2 interop opt-in annotation, and `ResolutionStrategy` constructor arguments.
- **smbj** (`SmbClient`): `File.rename(String, boolean)` and the `FileAttributes` check.
- **OpenCV** (`RingDetector`): the `Mat(rows, cols, type, ByteBuffer, step)` constructor and `DetectorParameters` setter names in 4.10.
- **Material** (`SessionEditActivity`): `MaterialAutoCompleteTextView.setSimpleItems`.
