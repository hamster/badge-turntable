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
