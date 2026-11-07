# Badge Turntable (Android)

The capture app described in [../Spec.md](../Spec.md). A OnePlus 13 on a tripod drives an IR turntable inside a light box and reads the [encoder ring](../encoder-ring/README.md) to know the table's angle. It photographs each badge at 0°, 90°, 180° and 270°, records a spin video, and produces a repo-ready badge folder. That session reaches the PC through the web editor ([remote control](../Spec.md#remote-control-from-the-editor)), which builds the badge folder.

Java 17, Views/XML with Material 3, minSdk 31, targetSdk 36. Package `org.snurkle.badgeturntable`.

## Handoff (Nov 7, 2026)

Where things stand, for picking this up in a new session. The spec is the source of truth for behaviour; where this README and the spec disagree, the spec wins.

### State

- **Nov 7, 2026 (later): generic sessions, sessions over the API, shared capture target, web copies only.** Built and tested (`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, 56 tests, no lint errors) and in the mock; not yet tried on the phone. Spec phase 7.
  - **Storage:** `sessions/<id>/` (session.json and the web copies), named by ID: no con, year, slug or badge ID, and no `index.md` on the phone (`IndexMdWriter`, `BadgeIds`, `KnownBadgeIds`, the bundled template and its Gradle copy task are gone). Full-size stills and source videos go to a scratch file and are deleted once the web copy is made, for sessions and the editor's shots alike. Sessions in the old `museum/` folder aren't read; delete them from the phone's app storage, or reinstall.
  - **`rig.Station`** (one per app, `App.station()`): the job runner, `SessionStore`, `RemoteShots`, the shared capture target, and the `RemoteServer`, which now runs whenever the app is in the foreground (`ProcessLifecycleOwner`). The Capture screen's `Rig` attaches while it's started; without it, camera endpoints answer `503`, and an idle rig answers IR presses and turntable status.
  - **Capture target:** none, a session, or the editor's shots. Capture with none opens the session picker (`SessionPicker`), which only opens the session. The editor joins the phone's session or starts a new set (`POST /api/target`); its captures get `409 session_open` until it does. Jobs carry `source`, `target`, `stoppedBy` and `filenames`, and busy messages say who's doing what.
  - **API:** `/api/sessions` (list, detail, files with thumbnails, deletes), `/api/target`, `POST /api/camera/open`, `session`, `session_removed` and `target` messages, and `original` always `null`.
  - **Screens:** the Session screen's strip deletes on long-press and updates live; the Sessions list updates live and shows the open session; a remote-control banner on every screen (`RemoteBanner`).
  - The editor side (badges repo) isn't done: **Open camera on phone**, the join prompt, **Phone sessions**, and dropping **Also save originals**.
- **Nov 7, 2026: Samba removed, media review, 4:3 video.** Built and tested, not yet tried on the phone:
  - Samba sync is gone: `SmbClient`, `SmbSettings`, `SyncWorker`, `SecretStore`, the smbj and WorkManager dependencies, the Samba settings, and the Sync button, status and menu items.
  - The Session screen shows the captured photos and spin video as a strip of thumbnails; tap one to open it in the phone's viewer (through a `FileProvider`).
  - Video is recorded at 4:3 (`Recorder.setAspectRatio`, FHD at 4:3 is 1440×1080), and the analysis asks for 4:3 at 1600×1200, so the preview keeps its shape when the camera switches to video for the spin.
- **Builds and runs.** `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes: 55 tests, no lint errors (36 warnings, almost all newer library versions, OpenCV's native libraries not being 16 KB aligned, and the fixed landscape orientation). There's no `gradle` on this PC's PATH, so build from Studio until the wrapper is added (see [Build setup](#build-setup)). Installed and used on a **OnePlus 9 Pro** (adb serial `25e933f`, no IR blaster); IR moves were tested earlier on the **OnePlus 13**.
- **Moved to its own repo** (Nov 7, 2026): this repo holds what was `apps/` in [hamster/badges](https://github.com/hamster/badges), with its history. The editor's Turntable panel (`edit/turntable.js`, `edit/turntable.css`) and `_template/index.md` stay in the site repo; keep a badges checkout beside this one (`../badges` from the repo root) for the mock's editor server.
- **Verified on the 9 Pro:**
  - Remote control over Wi-Fi from the PC: `badge-turntable.local` resolves; pairing, status, camera settings, the WebSocket (status, ring, about 7 preview frames a second at 640 px), an extra-shot job, shot download and `session.json` all work. Not yet tried from the editor itself.
  - The Capture screen layout, framing guides, tag boxes, zoom, tap to focus, and Back without a crash.
  - `MarkerFilter`: 3 bad detections dropped in 191 frames, angle within 0.65°.
  - Photo spin (earlier session): stills within 2.1° of target at 8 s/rev.
- **Written, built and tested, not yet tried on the phone** (the last round):
  - Capture screen: the address and code centred across the top (clear of Android's camera indicator), the ring reading as two fixed lines in the left column, and **Set front** below it.
  - **Set front** (`Rig.setFront`): stores the ring angle as the front. Captures start there; with a calibration it becomes the zero, and Set Origin is pressed so homing returns to it. API: `POST /api/turntable/front`, plus `turntable.frontRingDeg` and `ring.frontDeg` in status.
  - Sessions list: long-press for Delete.
  - Badge screen: **Save** stays greyed until there's a name, with the reason beside it. **Automatic turntable** is greyed, with the reason, when there's no IR blaster or no remote profile.
  - Remote control accepts any origin; the pairing code and token are the protection. The allow-list and its setting are gone.
  - The editor's switch to manual exposure starts from the ISO and shutter auto exposure was using.
- **Done since, not yet tried against the phone** (Nov 5, later):
  - The editor has a **Set front** button and shows the front offset and where the front is set. The mock implements `POST /api/turntable/front`, the new status fields, and captures starting from the front (turning there first), and it accepts any origin (`--allow-origin` is gone). Checked against the mock with curl, not yet in the editor.
  - `Spec.md` and `Remote-API.md` cover Set front, manual Samba sync, any origin, and the ring exposure note.
  - **Keepalive:** the editor now drops a link after 4 s with no message. The app's 500 ms status tick already serves as the heartbeat. A WebSocket whose text queue backs up (a dead connection the writer is stuck on) is now dropped, rather than queueing forever.
  - The app catches up with the editor's API changes: `POST /api/capture` takes `stills` (spin-only is `{stills: false, video: true}`: turn to the front, set the video speed, record a turn, home). The editor's focus click is focus only (`FLAG_AF`), held until `{"clear": true}`, and the camera settings report `focusPoint`. The point is cleared by manual focus, AF lock, a lens change or rebind, a capture's centre lock, or a tap on the phone, and each change sends a `camera` message.
  - Lint cleanups: locale-safe formatting and lower-casing (file captions, `.ir` keys), a plural for the file count, an unused colour and a string clash with androidx removed.
- **Editor and API, Nov 5 (later), tested against the mock only:**
  - The editor's Capture box has four buttons: **Auto capture** (`{stills, video, auto: true}`; shown only with IR, enabled once calibrated), and by hand **Capture both** (`{stills: true, video: true, auto: false}`), **Capture stills** and **Capture spin**. **Main views** or **Extra set** picks `setLabel`. The checkboxes are gone. The app needed no change for these requests.
  - Camera controls: lens, zoom, **Brightness** (EV) and a focus line under the preview, the rest folded into **More camera settings**, with the locks called **Hold**. A clicked focus point shows as a ring with **Back to auto**.
  - When the phone stops answering (4 s of silence), the panel says it has lost contact, disables the controls, and holds a **Stop** to send when the phone is back, if the same job is still running. The mock's `/mock/offline?ms=` simulates that.
  - New in `Spec.md`: [Seamless spin loop](../Spec.md#seamless-spin-loop). New in `Remote-API.md`: the manual spin's prompts (a `position` prompt to start the table, then `spin_done` with **Done** when there's no ring), the notices "Spin recorded. You can stop the table." and "Spin not kept. You can stop the table.", and homing only when `auto` (matching `CaptureSequencer`).

### Next

1. **Try the last round on the phone** (the list above).
2. **End to end with the editor:** pair, preview, Set front, capture, *Add all to badge*. Check that the link holds through a long capture (the 4 s silence timeout).
3. **Seamless spin loop** ([../Spec.md#seamless-spin-loop](../Spec.md#seamless-spin-loop)) in `CaptureSequencer.recordSpin` and `AngleLog`. The auto path is close; the by-hand path isn't:
   - By hand, ask first ("Start the table turning with its remote…, then press Ready once it's turning steadily") and only then start recording. Today recording starts before the "Spin the table, then tap Ready after a full turn" prompt, so the start-up is in the clip.
   - With the ring, wait for a steady speed, then stop by itself after the lead-in, 360° and the overlap, with `progress.text` counting ("Recording the spin: 210° of 375°"). Without the ring, raise the `spin_done` prompt (**Done**, **Stop**).
   - `oneRevolution()` should start at the first reading after a 0.5 s lead-in at steady speed, not at the first reading. Warn if the speed varied by more than 10% across the cut.
   - Without the ring, find the loop point by matching small greyscale thumbnails against the frame at the end of the lead-in, instead of keeping the whole clip.
   - Send the notices in `Remote-API.md`, and make sure the table has stopped before homing.
   - Steady means the last second's speed is within 5% of the last 3 seconds'; wait up to 10 s, then record anyway with a "may not loop cleanly" notice. This applies to every path: the stop-at-each-view auto path (`recordSpin` with `drive`, not turning) currently starts recording *before* `table.start()`, so the start-up is in the clip, and it doesn't set the video speed. The spin path and the new spin-only path already set the speed and record while turning, but don't check steadiness after `setSpeed`.
   - Frame matching without the ring: a 64×48 greyscale thumbnail per analysis frame; the end is the best match at least 75% of the way through; if no good match, keep the whole clip (capped at 30 s) and say so.
4. **Match the mock after "Switch to manual":** once the user picks manual partway through a stop-at-each-view capture, `runViews` doesn't pass that on, so the spin video still starts the table by IR. The mock asks the user to start the table instead. Return the manual switch from `runViews` and pass `drive = false` to `recordSpin`.
5. **IR:** the Iguana sends codes (`python ../ir-tools/iguana_ir.py send app/src/main/assets/profiles/jayegt-5.74.ir Turn_right`), but on Nov 5 the table didn't respond: check it's on and that the Iguana points at the receiver on the back of the base. Then measure the speed buttons (do −Speed/+Speed work while turning, and do they stop or wrap at the ends?), and run a full spin capture with the PC starting and stopping the table. Then the IR-driven path, including `setSpeed`, on the OnePlus 13.
6. **Later, optional:** dependency upgrades (CameraX 1.6, Media3 1.11, OpenCV 4.11 or later for 16 KB pages) each need a test on the phone.

### Known problem: ring reading and exposure

In the dim bench light, auto exposure at EV 0 pushes ISO to 600–700, and no tags are read. The band is mostly white tag borders with thin black gaps, and once bright, the white blooms over the gaps. Tags read at about EV −2 (the slider's −12). The fix is the light box's light; until then use EV −2 or manual exposure. Tried and taken out, because none clearly helped for the time it cost (up to 150 ms a frame against 30–50): CLAHE before detection, more threshold window sizes, a lower threshold constant, and metering exposure on the ring area.

### Working on this PC

- **adb:** `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe -s 25e933f` (an emulator is often connected too). The phone's Wi-Fi address changes (DHCP: `.106`, `.116`, `.120` so far); `badge-turntable.local` finds it.
- **Ring trace:** `adb shell setprop log.tag.RingTrace DEBUG` logs each reading; `VERBOSE` adds raw detections and ignored markers, and a `few` line when fewer than three markers are kept. Set it back to `INFO` afterwards.
- **The PC's shell has hung** at times: new processes (PowerShell, python, adb) time out while `cmd` built-ins still run, and edits to files outside this folder (and to `build.gradle`) timed out, then landed minutes later. If that happens, check what actually changed before retrying.
- **Launching:** `CaptureActivity` isn't exported, so start `.ui.MainActivity`; with remote control on, it opens Camera itself.
- **Pairing from curl:** see the end of `../Remote-API.md`. The code is on the Capture screen's top line.

### Build setup

Android Studio's Upgrade Assistant has already moved the project to **AGP 9.5.0 and Gradle 9.6.0**, and those changes are in the working tree:
- **Plugin version:** `libs.versions.toml` sets `agp = "9.5.0"`.
- **Gradle:** `gradle-wrapper.properties` points at Gradle 9.6.0.
- **Toolchain:** `settings.gradle` adds the Foojay toolchain resolver, and `gradle/gradle-daemon-jvm.properties` pins a JetBrains JDK 21 for the Gradle daemon.
- **Compatibility flags:** `gradle.properties` gains the flags that keep pre-AGP-9 behaviour, such as `android.newDsl=false`, `android.builtInKotlin=false` and `android.uniquePackageNames=false`. They can be retired one at a time later.

The code itself targets Java 17 (`compileOptions` in [app/build.gradle](app/build.gradle)).

The Gradle wrapper jar and the `gradlew` scripts aren't in the repo yet. Let Studio create them, or run `gradle wrapper` once. Then commit them so `./gradlew test` works from a terminal.

The app no longer bundles the site's `_template/index.md`: the editor builds `index.md`.

### Hardware facts (already settled)

| Item | State |
| --- | --- |
| Turntable | JAYEGT 5.74 in. Behaviour of every button is in the spec's JAYEGT table and [../ir-tools/README.md](../ir-tools/README.md#jayegt-574-in-remote) |
| IR codes | NEC, address 0x00, captured with an IguanaIR on the PC and bundled in `app/src/main/assets/profiles/jayegt-5.74.ir`. Replaying them from the PC moves the table. The step is `Step_90` (0x46); the 45/90/180 buttons are swing settings and map to no role |
| Moves | `TurntableController.start()` presses Turn Right (continuous, known direction), `stop()` presses On/Pause. Homing uses Back to Set, and falls back to the ring plus Set Origin if the table has lost its origin |
| Encoder ring | Printed and fitted: a black band (inverted markers), 45 markers at 8°, 9.5 mm tall, generated at 460.9 mm. The app's default marker count is 45, and the detector reads black or white bands |
| Angle fit | Perspective-corrected (`AngleFit`), after `MarkerFilter` drops strays and misread IDs. In simulation about 0.1° typical at light-box distances. The analysis frame is 1600×1200 on both phones; frame the ring at least about 3 px/mm |

## Code map

| Package | What's in it |
| --- | --- |
| `ui` | `MainActivity` (sessions list with delete, and the **Camera** button), `SessionEditActivity` (name, notes, options, the media strip: view and delete), `MediaAdapter`, `SessionAdapter`, `CaptureActivity` (preview, controls, Set front, the target button, job progress and prompts), `SessionPicker` (open a session, new session, shots for the editor, close), `RemoteBanner` (the remote-control line on every screen), `FramingOverlay` (guides, ring zone, marker boxes, axis line), `SettingsActivity` (remote control and paired PCs, turntable and IR tests, capture), `ProbeActivity` (capabilities report) |
| `rig` | `Station` (one per app: job runner, sessions, editor shots, the shared capture target, and the remote server's lifecycle), `Rig` (the Capture screen's camera, ring, turntable and sequencer, attached to the Station while it's open). The phone's buttons and the remote API both start jobs through the Rig, with the same prechecks |
| `jobs` | `Job` and `JobRunner` (one job at a time: progress, prompts where the first answer wins, Stop by interrupt), `ApiException` (the API's error codes), `Json` helpers |
| `remote` | Remote control ([../Remote-API.md](../Remote-API.md)): `HttpServer` and `WebSocket` (built in, `java.net` only), `RemoteApi` (the routes), `RemoteServer` (lifecycle, WebSocket clients, status, ring and preview streams), `Pairing`, `MdnsResponder` (`badge-turntable.local`), `PreviewEncoder` (analysis frame to upright JPEG), `RemoteShots` (the unnamed shots held for the editor), `SessionMedia` (sessions' files as the API shows them) |
| `camera` | `CameraController` (CameraX binding, lens choice, Camera2 manual controls, capabilities, blocking capture and record), `CameraSettings` (the light box profile, as the API names it), `CapabilitiesProbe` |
| `ring` | `RingDetector` (CameraX analyzer, OpenCV ArUco, normal and inverted markers), `MarkerFilter` (drops detections off the band, out of order, or with impossible jumps), `AngleFit` (linear fit with outlier rejection, refined with a perspective model), `RingConfig`, `RingReading` |
| `ir` | `IrTransmitter` (ConsumerIrManager), `NecEncoder` (NEC, NECext, Samsung32), `FlipperIrParser`, `ButtonNames` (button name to role; steps need an explicit `Step_` name), `TurntableProfile`, `Presets` (JAYEGT 5.74 in) |
| `motion` | `TurntableController` (start/stop, closed-loop moves, verified homing), `Calibrator` (spin, speed, direction, overshoot, step size check, zero, set origin), `Calibration`, `MoveFailedException` |
| `capture` | `CaptureSequencer` (spin or stop-at-each-view captures, auto and manual, spin video, extra shots; blocking, on the job thread), `RingHistory` (shooting on the move), `CaptureTarget` with `SessionTarget` (a badge folder) or the editor's shots, `View` (the four views and their file names) |
| `media` | `WebImages` (2400 px web copies), `SpinTranscoder` (Media3: trim to one revolution, 1080 px, 24 fps, HDR to SDR, at most 10 MB), `Thumbs` (320 px thumbnails), `AngleLog` |
| `session` | `Session` (name, notes, options, media), `SessionJson`, `SessionStore` (`sessions/<id>/`, scratch, thumbnails, change listeners, `update` under a lock), `Slugs` (file names) |

Related tools in this repo:
- [../ir-tools/](../ir-tools/README.md): `iguana_ir.py` captures and replays remote codes on the PC.
- [../encoder-ring/](../encoder-ring/README.md): the ring generator (paper SVG or ZPL labels, `--invert` for black bands).

## Not done yet

See [Next](#next) for the immediate work. Beyond that:
- **Remote control gaps:** Kelvin white balance (reported as unsupported), backward moves (`by` below 0 is refused), and the mDNS responder doesn't check for name clashes.
- Manual-mode angle dial (current against target). The status line shows the instruction only.
- A focus lock and manual focus on the phone (the editor has both).
- Custom turntable profile editor, USB IR learning, encoder-ring command scan, and the camera IR-learning spike (spec phase 8).
- The editor's side of Nov 7's API: **Open camera on phone**, the join-or-new prompt, the **Phone sessions** list, and dropping **Also save originals** (badges repo, `edit/turntable.js`).
- Speeding up spin videos longer than 30 s (not needed at the JAYEGT's 15 s/rev).
