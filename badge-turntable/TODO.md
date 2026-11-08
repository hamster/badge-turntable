# TODO Items

- [x] Fix NullPointerException in `RingDetector` that causes remote live preview to fail.
  OpenCV 5 returns the detector's `ids` as a 1xN row (vectors became 1-D Mats) instead of
  4.x's Nx1 column, so `ids.get(i, 0)` was null for every marker after the first. `read()` now
  reads ids and corners by element count, whatever the shape. `analyze()` turns a failed read
  into an empty reading, so listeners and the remote preview's frame tap still get every frame.
  Each consumer is guarded on its own, and the warning is logged at most every 5 s.
- [x] Tag detection speed and reliability (Nov 7):
  - Search only the ring zone (the box on the Capture screen, plus 5% margin) instead of the
    whole frame. While fewer than 3 markers are found, the whole frame is scanned every 500 ms,
    so a ring outside the box still shows. `adb shell setprop log.tag.RingFullFrame VERBOSE`
    turns the crop off for comparison. OpenCV 5.0 finds nothing in a submat view, so the crop
    is copied out first.
  - `adaptiveThreshConstant` 7 → 2, and ArUco3 detection on. With the black band under room
    light, the default constant thresholded most tiles away. Replaying 15 phone frames offline
    gave 2.3 markers a frame and never 8; with the new settings, 11 a frame, every frame 8+,
    and no misread ids.
  - On the phone, both versions now fit every frame with 10-11 markers. Median ms/frame
    (crop / full): 5.0.0.1 23 / 33, 4.14.0 21 / 26. With the crop, analysis keeps up with
    30 fps on 5.0.
- [ ] Optional: OpenCV 4.14.0 is 16 KB-page aligned too (both `libopencv_java4.so` and
  `libc++_shared.so`; 4.11 failed only on `libc++_shared.so`). It's slightly faster than 5.0
  and has the older, well-trodden API, but 5.0 now works well enough that there's no pressing
  reason to switch.
- [ ] Check the remote web editor's preview end to end with the fixed build.
- [ ] Side bars: run a stills capture with them on (with the table still) and check the four
  web copies. The spin path is checked on the phone; the stills path is only unit-tested.
- [ ] Editor (badges repo): draw the side bars on the preview, drag them, and send
  `POST /api/camera {"bars": …}` on release (Remote-API.md, camera settings).
- [ ] Show the app's version in the app (from `BuildConfig`/the package info, e.g. "1.0 (2)"),
  in the info area with a link or button to the source,
  https://github.com/hamster/badge-turntable. `GET /api/info` already reports `appVersion`.
- [ ] USB-C IR dongle: get the turntable driven through a plug-in IR emitter, for phones
  without a built-in blaster (the OnePlus 9 Pro has none; the 13 does). When it arrives, find
  out how it's driven: many are a USB audio device that turns a waveform into IR (play the NEC
  carrier as audio), others USB HID or a vendor serial protocol (usb-serial-for-android is
  already planned for IR learning). Then put it behind `IrTransmitter`, so `TurntableController`
  doesn't care which one sends, and say which one is in use in Settings → IR.
- [ ] Release signing: the 1.0 key is `~/.android/badge-turntable-release.jks`, with its
  passwords in `~/.gradle/gradle.properties` (`BT_RELEASE_*`). Back both up somewhere safe:
  every future release must be signed with that key, or phones can't update without
  uninstalling.
- [ ] Optional: build releases for arm64 only (`ndk { abiFilters 'arm64-v8a' }` for release).
  The 1.0 APK is 212 MB, mostly OpenCV for four ABIs; arm64 alone would be about a quarter of
  that. Every target phone is arm64, and the WebRTC bridge already is.
