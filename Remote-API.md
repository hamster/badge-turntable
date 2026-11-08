# Badge Turntable — Remote API

API version **1** · Nov 7, 2026 (adds [Sessions](#sessions), the shared [capture target](#capture-target), `POST /api/camera/open`, the server on every screen, job `source`, `target` and `stoppedBy`, and web copies only). This is the contract between the Badge Turntable app (the server, on the phone) and the web editor's Turntable panel ([edit/turntable.js](https://github.com/hamster/badges/blob/main/edit/turntable.js) in the badges repo, the client). The background, the security reasoning and the user-facing behaviour are in [Spec.md § Remote control from the editor](Spec.md#remote-control-from-the-editor). Where the two disagree about the wire format, this document wins.

A mock phone that implements all of it is in [badge-turntable/remote-mock/](badge-turntable/remote-mock/README.md). It's the reference when this text is unclear, and the editor panel is tested against it.

Contents: [Conventions](#conventions) · [Errors](#errors) · [CORS](#cors) · [Pairing and tokens](#pairing-and-tokens) · [Endpoints](#endpoints) · [Jobs](#jobs) · [Shots](#shots) · [Sessions](#sessions) · [WebSocket `/api/live`](#websocket-apilive) · [Discovery](#discovery) · [Implementation notes for the app](#implementation-notes-for-the-app)

## Conventions

- **Base URL:** `http://<host>:8737/api/`. The port is set in **Settings → Remote control**. `<host>` is the mDNS name (`badge-turntable.local`) or the Wi-Fi IP address.
- **Bodies** are JSON (`Content-Type: application/json; charset=utf-8`), both ways. Request bodies are small (under 16 KB). Unknown fields in a request are ignored. Clients must ignore unknown fields in a response, so new fields can be added without a version bump.
- **Names** in JSON are `camelCase`. Enum values are `lower_snake_case` strings. A turntable role is `TurntableProfile.Role.name().toLowerCase()`, so `STEP_90` is `"step_90"`.
- **Times** are ISO 8601 UTC strings with milliseconds (`"2026-11-04T18:22:05.120Z"`). Frame times, which come from the camera clock, are integer **microseconds** (`frameUs`), so they stay exact in a JavaScript number.
- **Angles** are degrees, as floats. `ringDeg` is the raw ring reading in [0, 360). `tableDeg` is the table angle relative to the calibrated zero, going forward, in [0, 360). It's `null` when the table isn't calibrated or the ring can't be read.
- **IDs** (`deviceId`, job, prompt and shot IDs) are opaque strings. Don't parse them.
- **Absent versus null:** optional values that don't apply are `null`, not missing, unless this document says otherwise.
- **Version:** `GET /api/info` returns `"api": 1`. Adding fields or endpoints doesn't change it. Removing or changing the meaning of something does. The editor refuses to control a phone whose `api` it doesn't know, and says so.

## Errors

Every error is an HTTP status with this body:

```json
{ "error": "busy", "message": "Calibration is running. Stop it first.", "jobId": "j-7" }
```

`message` is a sentence the editor can show as is. Some errors add fields (listed below).

| Status | `error` | When | Extra fields |
| --- | --- | --- | --- |
| 400 | `bad_request` | Malformed JSON, missing or out-of-range field | `field` |
| 401 | `unauthorized` | No token, or the token isn't valid (revoked, or the app's data was cleared) | |
| 401 | `bad_code` | Wrong or expired pairing code | `attemptsLeft` |
| 404 | `not_found` | Unknown path, shot ID, session ID, session file or job | |
| 405 | `method_not_allowed` | Known path, wrong method | |
| 409 | `busy` | A job is running and this request needs the camera or the turntable, or would change files the job is writing. `message` says who is running what ("The phone is capturing Mk9. Wait for it, or stop it.") | `jobId`, `source`, `target` (as in the [Job object](#job-object)) |
| 409 | `session_open` | The phone has a session open that this editor hasn't joined, and it asked to capture (join it or start a new set with [`POST /api/target`](#capture-target) first); or it asked to delete the session that's the capture target | `sessionId` |
| 409 | `not_calibrated` | Auto capture or a closed-loop move without a valid calibration | |
| 409 | `no_job` | Answering or aborting when no job is running | |
| 409 | `prompt_gone` | The prompt was already answered (from the phone or another tab), or it isn't the current prompt | |
| 409 | `ring_lost` | The encoder ring isn't seen, and the request needs it (Set front; calibration fails with it too) | |
| 409 | `not_ready` | The shot is still being processed (a spin video while it's transcoding) | |
| 422 | `unsupported` | The phone or the profile can't do it: a role the remote doesn't have, a camera setting the lens doesn't offer, no IR blaster | `field` |
| 429 | `pairing_locked` | Five wrong codes in a row | `retryAfterMs` |
| 500 | `internal` | Anything unexpected. `message` carries the exception text | |
| 503 | `camera_unavailable` | The camera isn't open: the phone isn't on its Capture screen ("Open the Camera screen on the phone"), the camera is still starting, or another app has it | |
| 507 | `storage_low` | Less than 500 MB free, so a capture won't start | `freeBytes` |

## CORS

The editor runs on another origin, so every response to a request with an `Origin` header, including errors and the `OPTIONS` preflight, carries CORS headers:

```
Access-Control-Allow-Origin: <the request's Origin, echoed>
Vary: Origin
Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type
Access-Control-Expose-Headers: Content-Disposition, X-Shot-Filename
Access-Control-Max-Age: 600
```

- **Any origin:** the phone doesn't check `Origin`. The pairing code (shown only on the phone) and the token are what keep other pages out. An allow-list of editor origins only got in the way, for example with a local copy of the editor.
- **Preflight:** answer `OPTIONS` on any `/api/` path with `204` and the headers above, without checking the token. If the preflight has `Access-Control-Request-Private-Network: true`, also send `Access-Control-Allow-Private-Network: true`. That header belongs to the older Private Network Access scheme, which Chrome replaced with Local Network Access, but it's harmless and keeps older Chrome builds working.
- **No `Origin` header** (curl, scripts): no CORS headers. The token still applies.

## Pairing and tokens

- While remote control is on, the remote-control banner (on every screen of the app) shows a **6-digit pairing code**. It changes every 2 minutes and right after a successful pairing. Codes are random (`SecureRandom`), not sequential.
- `POST /api/pair` swaps the current code for a **token**: 128 random bits as 32 lowercase hex characters. The phone stores tokens with the client name and time paired, and lists them in **Settings → Remote control → Paired PCs**, where each can be revoked.
- After **5 wrong codes in a row**, pairing is locked for 60 s (`429 pairing_locked`). A correct code resets the count.
- Every endpoint except `GET /api/info`, `POST /api/pair` and `OPTIONS` needs `Authorization: Bearer <token>`. Compare tokens in constant time.
- The WebSocket can't send headers, so its first message carries the token (see [WebSocket](#websocket-apilive)).

## Endpoints

The server runs while the app is in the foreground, on any screen. Endpoints marked **Cam** need the phone's Capture screen open, and answer `503 camera_unavailable` until it is (`cameraOpen` in [Status](#get-apistatus) says which). The rest work from any screen.

| Method and path | Auth | Purpose | Response |
| --- | --- | --- | --- |
| `GET /api/info` | No | Who is this phone | `200` [Info](#get-apiinfo) |
| `POST /api/pair` | No | Pair with the code on screen | `200 {token, deviceId, name}` |
| `DELETE /api/pair` | Yes | Forget this client's token (the editor's **Forget**) | `204` |
| `GET /api/status` | Yes | Everything the panel shows, at once | `200` [Status](#get-apistatus) |
| `GET /api/camera` | Cam | Camera capabilities and current settings | `200 {capabilities, settings}` |
| `POST /api/camera` | Cam | Change settings (partial) | `200 {settings}` |
| `POST /api/camera/focus` | Cam | Tap to focus and meter at a point | `200 {settings}` |
| `POST /api/turntable/press` | Yes | Send one button's IR code | `200 {role}` |
| `POST /api/turntable/move` | Cam | Closed-loop move, or home | `202 {job}` |
| `POST /api/turntable/front` | Cam | Set front: make the table's position now the front | `200 {frontRingDeg}` |
| `POST /api/calibrate` | Cam | Run calibration | `202 {job}` |
| `POST /api/capture` | Cam | Turntable set, or an extra set | `202 {job}` |
| `POST /api/extra` | Cam | One extra still | `202 {job}` |
| `GET /api/jobs/current` | Yes | The running job, or the last one | `200 {job}` (`job` may be `null`) |
| `POST /api/jobs/current/answer` | Yes | Answer the job's prompt | `200 {job}` |
| `DELETE /api/jobs/current` | Yes | Stop the job | `200 {job}` |
| `GET /api/shots` | Yes | The shots the phone holds | `200 {shots: [Shot…]}` |
| `GET /api/shots/{id}` | Yes | A shot's file | `200` bytes |
| `DELETE /api/shots/{id}` | Yes | Drop one shot | `204` |
| `DELETE /api/shots` | Yes | Drop all shots | `200 {deleted}` |
| `GET /api/session.json` | Yes | The capture record of the held shots (kept for older editors) | `200` [Session record](#get-apisessionjson) |
| `GET /api/target` | Yes | Where captures go | `200 {target}` |
| `POST /api/target` | Yes | Join the phone's session, or start a new set of shots | `200 {target}` |
| `POST /api/camera/open` | Yes | Open the phone's Capture screen | `200 {cameraOpen: true}` |
| `GET /api/sessions` | Yes | The sessions captured on the phone | `200 {sessions: [Session summary…]}` |
| `GET /api/sessions/{id}` | Yes | One session, with its notes and media | `200` [Session](#session-summary-and-session-object) |
| `GET /api/sessions/{id}/media/{filename}` | Yes | One of its files | `200` bytes |
| `DELETE /api/sessions/{id}/media/{filename}` | Yes | Delete one of its files | `204` |
| `DELETE /api/sessions/{id}` | Yes | Delete the whole session | `204` |
| `GET /api/live` | First message | WebSocket: preview, ring, status, job events | `101` |

### `GET /api/info`

```json
{
  "api": 1,
  "app": "Badge Turntable",
  "appVersion": "0.3.0",
  "deviceId": "6f1c0b9e2a7d4c35",
  "name": "badge-turntable",
  "model": "OnePlus CPH2749",
  "port": 8737,
  "addresses": ["192.168.10.23"],
  "paired": true,
  "busy": false
}
```

- `deviceId` is random, made on first launch and kept in preferences. The editor uses it to recognise the phone after its IP address or name changes.
- `name` is the mDNS host name without `.local`.
- `addresses` are the phone's current IPv4 addresses on Wi-Fi. The editor remembers them as a fallback.
- `paired` is `true` if the request carried a valid token, `false` if it carried one that isn't valid, and `null` if it carried none. It's how the editor notices a revoked token without a 401.
- `busy` is `true` while a job runs.

### `POST /api/pair`

Request: `{"code": "482913", "clientName": "Edge on HAMSTER-PC"}`. `clientName` is up to 64 characters, shown in Paired PCs and in the phone's "Controlled from …" banner.

Response: `{"token": "9b2e…(32 hex)", "deviceId": "6f1c0b9e2a7d4c35", "name": "badge-turntable"}`.

Errors: `400 bad_request` (code isn't 6 digits), `401 bad_code` with `attemptsLeft`, `429 pairing_locked` with `retryAfterMs`.

### `GET /api/status`

```json
{
  "clients": [{ "clientName": "Edge on HAMSTER-PC", "since": "2026-11-04T18:20:11.000Z" }],
  "cameraOpen": true,
  "target": { "kind": "session", "sessionId": "0b6f3c2e-5d1a-4e7b-9a0c-2f4d6e8a1b3c", "name": "Mk9", "setBy": "phone", "editorJoined": false },
  "sessions": { "count": 3, "bytes": 48123456 },
  "turntable": {
    "profileId": "jayegt-5.74",
    "profileName": "JAYEGT 5.74 in",
    "irAvailable": true,
    "roles": ["start_stop", "turn_left", "turn_right", "speed_up", "speed_down",
              "continuous_mode", "intermittent_mode", "step_90", "set_origin", "return_to_origin"],
    "frontRingDeg": 123.1
  },
  "calibration": {
    "state": "valid",
    "reason": null,
    "calibratedAt": "2026-11-04T17:58:40.000Z",
    "degPerSec": 24.1,
    "stopOvershootDeg": 3.2,
    "startLatencyMs": 180,
    "step90Deg": 90.4,
    "directionSign": 1,
    "markerCount": 45
  },
  "ring": { "ok": true, "ringDeg": 123.4, "tableDeg": 0.3, "frontDeg": 0.3, "markers": 11, "rmsPx": 0.8, "frameUs": 81234567890 },
  "camera": { "lens": "2", "zoom": 1.0, "…": "same object as GET /api/camera settings" },
  "job": null,
  "shots": { "count": 5, "bytes": 14123456 },
  "storage": { "freeBytes": 51234567890 }
}
```

- `clients` lists the editor tabs with an open WebSocket.
- `cameraOpen` is `true` while the phone's Capture screen is open, so the **Cam** endpoints work. While it's `false`, `ring` is `{"ok": false, …}` with the angles `null`, and `camera` is `null`.
- `target` is where captures go, from the phone or the editor ([Capture target](#capture-target)). When it's a session the editor hasn't joined (`editorJoined: false`), the editor asks its user to join it or start a new set before capturing.
- `sessions` counts the sessions on the phone and their total size.
- `turntable.roles` lists the roles the current profile has a code for, in `Role` enum order. These are the editor's Test buttons. `irAvailable` is `false` when the phone has no IR blaster or the profile is manual-only. In that case `roles` is empty and every IR command returns `422 unsupported`.
- `turntable.frontRingDeg` is the ring angle set with [Set front](#post-apiturntablefront), or `null` if none has been set.
- `calibration.state` is `valid`, `missing` (never calibrated) or `stale` (the profile or marker count changed since; `reason` says which). The other calibration fields are `null` unless the state is `valid` or `stale`. `step90Deg` is `null` when the remote has no `step_90`.
- `ring` is the latest reading. `frontDeg` is how far the table is from the front, going forward, in (-180, 180]; `null` without a front. When the ring isn't seen: `ok: false`, the angles are `null`, and `markers` is 0.
- `job` is the running job ([Job](#job-object)) or `null`.

### `GET /api/camera` and `POST /api/camera`

`GET` returns:

```json
{
  "capabilities": {
    "lenses": [
      { "id": "0", "label": "Back (logical)", "focalLengthMm": 5.59, "minFocusCm": 10 },
      { "id": "2", "label": "Main", "focalLengthMm": 5.59, "minFocusCm": 10 },
      { "id": "3", "label": "Ultrawide / macro", "focalLengthMm": 2.31, "minFocusCm": 3.5 },
      { "id": "4", "label": "Telephoto", "focalLengthMm": 12.19, "minFocusCm": 40 }
    ],
    "zoom": { "min": 1.0, "max": 10.0 },
    "ev": { "min": -12, "max": 12, "stepEv": 0.1667 },
    "iso": { "min": 100, "max": 6400 },
    "shutterNs": { "min": 10000, "max": 1000000000 },
    "focusDiopters": { "min": 0.0, "max": 10.0 },
    "whiteBalance": ["auto", "daylight", "cloudy", "incandescent", "fluorescent", "kelvin"],
    "kelvin": { "min": 2000, "max": 10000 },
    "bars": { "max": 0.4 }
  },
  "settings": {
    "lens": "2",
    "zoom": 1.0,
    "exposureMode": "auto",
    "ev": 0,
    "iso": 200,
    "shutterNs": 16666666,
    "focusMode": "auto",
    "focusDiopters": 0.0,
    "whiteBalance": "auto",
    "kelvin": 5000,
    "aeLock": false,
    "awbLock": false,
    "afLock": false,
    "focusPoint": null,
    "bars": { "enabled": false, "left": 0.0, "right": 0.0 }
  }
}
```

- **Capabilities** come from the capabilities probe for the **selected lens**, so they change when `lens` changes. `iso`, `shutterNs` and `focusDiopters` are `null` when the lens lacks `MANUAL_SENSOR`, and `kelvin` is `null` (and `"kelvin"` is missing from `whiteBalance`) when it lacks `MANUAL_POST_PROCESSING`. Only list lenses the app can actually bind.
- **`ev`** is the exposure compensation **index** (CameraX `ExposureState`), and `stepEv` is the EV per index step. The editor shows `ev × stepEv`.
- **`exposureMode`**: `auto` uses `ev`. `manual` uses `iso` and `shutterNs`. In auto mode, `iso` and `shutterNs` report the last values the camera chose (from the capture result) if known, otherwise the last manual values.
- **`focusMode`**: `auto` is continuous AF, and `afLock` holds the current focus. `manual` uses `focusDiopters` (0 is infinity, larger is closer).
- **`focusPoint`**: `{"x", "y"}` while autofocus is held on a point clicked in the editor ([`POST /api/camera/focus`](#post-apicamerafocus)), otherwise `null`. Read-only: `POST /api/camera` ignores it. It goes back to `null` when focus is cleared, when `focusMode` becomes `manual`, `afLock` is turned on or `lens` changes, and when a capture starts (the sequence focuses on the centre itself).
- **`whiteBalance`**: one of the listed modes. `kelvin` is used only when the mode is `kelvin`.
- **`bars`**: the [side bars](Spec.md#side-bars). Past them, the views and the spin are painted over with the background just inside each bar, so the light box's edges don't show. `left` and `right` are how far each bar comes in from its edge, as a fraction of the **upright frame's** width (the frame as the preview shows it), from 0 to `capabilities.bars.max`. So the kept part runs from `left` to `1 − right`. `enabled` turns them on and off. The positions are kept while they're off. Extra shots never get them.
  - The editor should draw them on its preview the way the phone does: shade past each bar, with a line at `left` and at `1 − right`, and let the user drag the lines. On release, send `POST /api/camera` with `{"bars": {"left": …, "right": …}}`. Sending only what changed is fine: `{"bars": {"enabled": true}}` or `{"bars": {"right": 0.12}}`.
  - The preview frames aren't painted over (the ring needs the whole width), so the shading is the editor's to draw.
  - Changing only the bars doesn't touch the camera, so it's cheap. Even so, send once on release rather than on every move, so other tabs and the phone aren't flooded with `camera` messages.
  - A capture reads the bars when it starts and uses them throughout. The capture record in [`session.json`](#get-apisessionjson) holds the values used.
- **`POST /api/camera`** takes any subset of `settings`, applies it, saves it to the light box profile (as changes made on the phone are), and returns the full `{settings}`. Changing `lens` rebinds the camera, which takes about a second. The response comes after the rebind, and capabilities may differ afterwards, so the editor fetches `GET /api/camera` again.
  - Values out of range: `400 bad_request` with `field`. Settings the lens doesn't support: `422 unsupported` with `field`. Nothing is applied when either is returned. For `bars`, `field` names the part that's wrong: `bars`, `bars.enabled`, `bars.left` or `bars.right`.
  - While a `calibrate`, `capture` or `extra` job runs: `409 busy`. AE, AF and AWB are locked during a sequence, so all the shots match.
- Changes from any source (the phone's own controls, another tab) are pushed to every WebSocket as a `camera` message.

### `POST /api/camera/focus`

Request: `{"x": 0.42, "y": 0.61}`, normalised to the **upright preview frame** (the frame as displayed after the header's rotation), with (0, 0) at the top left. The phone runs a `FocusMeteringAction` with **`FLAG_AF` only** at that point, with auto-cancel disabled, so focus stays there until it's cleared. Exposure isn't metered there, for the same reason as the phone's tap (it made the picture jump). It sets `focusMode` to `auto`, turns `afLock` off, and sets `focusPoint`.

`{"clear": true}` cancels it (`cancelFocusAndMetering()`), so continuous AF returns, and sets `focusPoint` to `null`.

Response: `{settings}`, also pushed as a `camera` message. `409 busy` during a `calibrate`, `capture` or `extra` job. This is mainly for extra shots of a detail: a capture sequence replaces the point with its own centre focus.

### `POST /api/turntable/press`

Request: `{"role": "step_90"}`. Sends that button's IR code once, the same as a Test button in Settings. Returns `200 {"role": "step_90"}` once the code has been transmitted, without waiting for the table to move. No ring check.

Errors: `400 bad_request` (unknown role), `422 unsupported` (the profile has no code for it, or no IR), `409 busy` while any job runs.

### `POST /api/turntable/move`

A closed-loop move, run as a [job](#jobs) of kind `move`. One of:

| Body | Meaning |
| --- | --- |
| `{"by": 90}` | Forward by that many degrees (-360 to 360; negative uses Turn Left if the remote has it, otherwise `422 unsupported`) |
| `{"to": 180}` | To that table angle, going forward |
| `{"home": true}` | Home as described in the spec's Positioning section: Return to Origin, check against the ring, fall back to a ring move plus Set Origin |

Needs a valid calibration (`409 not_calibrated`), except `home` when the remote has `return_to_origin`, which still works but can't be checked. A failure raises a `move_failed` prompt ([prompts](#prompts)) that offers `retry` and `stop`. The job's `result` is `{"tableDeg": 90.6, "ringDeg": 213.7}`.

### `POST /api/turntable/front`

Body: `{}`. **Set front**, the same as the button on the phone's Capture screen: the table's position now, read from the ring, becomes the front. Returns `200 {"frontRingDeg": 123.1}` straight away (it isn't a job).

- Captures start at the front, turning there first if the table is more than 2° away (auto) or asking for it with a `position` prompt (manual).
- With a calibration, the front also becomes the calibration's zero (so `tableDeg` reads 0 there), and the phone presses Set Origin, so homing after a capture returns to it.
- The front is kept until it's set again, across restarts.

Errors: `409 busy` while any job runs, `409 ring_lost` when the ring isn't seen.

### `POST /api/calibrate`

Body: `{}`. Runs `Calibrator` as a job of kind `calibrate`. Progress text comes from the calibrator's status messages. `result` is the new `calibration` object, the same shape as in Status. A failure (such as the step not measuring about 90°) ends the job `failed` with the calibrator's message.

### `POST /api/capture`

```json
{ "stills": true, "video": true, "moving": true, "auto": true, "setLabel": null }
```

- `stills`: take the four views. Default `true`.
- `video`: record the spin. Default `true`. At least one of `stills` and `video` must be `true`, or `400 bad_request` with `field: "stills"`.
- `moving`: `true` for [moving shots](Spec.md#moving-shots) (the table turns and the stills are taken as the ring passes each view; needs the ring), `false` for [by hand](Spec.md#by-hand) (a `position` prompt for each view; no ring, IR or turntable needed). Missing: the phone's **Moving shots** switch, the same as a capture started on the phone.
- `auto`: moving shots only: `true` has the phone start the table, set its speed, stop it and return it to the front with IR; `false` leaves starting and stopping it to the user. Ignored by hand. Default `true`. `422 unsupported` with `field: "auto"` when the phone can't drive the table.
- `setLabel`: `null` (or missing, or empty) for the main turntable set, or a label such as `"With SAOs installed"` for an [extra set](Spec.md#extra-shots).

Prechecks, in order: `503 camera_unavailable`, `409 busy`, `409 not_calibrated` (moving shots with `auto` only), `507 storage_low`. Then `202 {job}` with kind `capture`, whose `params` show the `moving` and `auto` actually used. **Write** keeps the files as remote [shots](#shots) (or in the joined session) instead of writing a badge folder.

- **Moving shots:** the table turns continuously and the stills are taken as the ring passes each angle, then the spin is recorded. With `auto: false` there are no prompts: `progress.text` asks the user to start the table ("Start the turntable (slowest speed is sharpest)"), the phone waits up to 2 minutes for steady rotation, and then it says when the table can be stopped. A ring that can't be read fails the job with `ring_lost`.
- **By hand:** a `position` prompt for each view (`answers` `ready`, `stop`; `ready` takes it after the countdown). Then, if `video`, a `position` prompt with `view: "spin"` and `stage: "start"` (`answers` `ready` to record, `skip` to skip the video), and once recording, another with `stage: "recording"` (`ready` keeps the clip, `stop` discards it). The table is never driven.

With `stills: false`: by hand, it's the two spin prompts; with moving shots and `auto`, the phone starts the table at the video speed, records a turn and stops it. `progress.of` counts only the steps asked for: 4 views if `stills`, 1 spin if `video`, and 1 for going back to the front ("Returning to zero…") with moving shots and `auto`.

The editor can offer the same choices as the phone: the two modes, and stills, spin or both.

`result`: `{"complete": true, "shotIds": ["s-11", "s-12", "s-13", "s-14", "s-15"]}`. `complete` is `false` when the user chose `stop` partway, in which case the job state is still `done`, and the shots taken are kept.

### `POST /api/extra`

```json
{ "label": "Lanyard", "delayMs": 2000 }
```

One still with the current camera settings. The table doesn't move. `label` is required (1–60 characters). `delayMs` is the countdown and defaults to the phone's **Capture countdown** setting (0–10000). Kind `extra`, `result` `{"shotIds": ["s-16"]}`.

Remote mode doesn't unlock or restore the camera around an extra shot the way the phone UI does. The user reframes from the editor's camera controls first, and those changes are saved to the light box profile like any other.

## Jobs

Calibration, captures, extra shots and moves are **jobs**. Only one runs at a time. Requests that need the camera or turntable get `409 busy` while one runs. These still work during a job: all `GET`s, `answer`, `DELETE /api/jobs/current`, `DELETE /api/shots/{id}` for shots not in the running job, session deletes for sessions the job isn't writing into, and `POST /api/camera` during a `move`.

Jobs come from the phone's own buttons or from the editor (their **source**), and captures go into the shared [capture target](#capture-target), fixed when the job starts. There's only one camera and turntable, so whichever starts first runs, and the other side gets `409 busy` naming it. Either side can stop or answer any job. See [Phone and editor at once](Spec.md#phone-and-editor-at-once).

### Capture target

`GET /api/target` returns `{"target": {kind, sessionId, name, setBy, editorJoined}}`, the same object as in [Status](#get-apistatus):

| `kind` | Captures go into |
| --- | --- |
| `session` | That session on the phone (`sessionId`, `name`) |
| `shots` | The editor's unnamed set of [shots](#shots) |
| `none` | Nothing yet. The editor's first capture makes it `shots`; the phone's Capture shows its session picker |

`setBy` is who chose it (`phone`, `editor`, or `null` for `none`). `editorJoined` is `true` when the editor chose it or joined it: only then do the editor's captures go into a session. While it's `false`, `POST /api/capture` and `POST /api/extra` answer `409 session_open`.

`POST /api/target` changes it:

| Body | Meaning |
| --- | --- |
| `{"session": "<id>"}` | Join that session (normally the one the phone has open): `editorJoined` becomes `true`, and both sides' captures go there |
| `{"shots": true}` | Start a new set: the phone's session is closed (it's already saved) and the editor's shots become the target, for the phone's captures too |

Errors: `409 busy` while a job runs, `404 not_found` for an unknown session, `400 bad_request` for anything else. Every change, from either side, is pushed as a `target` message, with `by` and the `previous` target, followed by `status`. When the phone opens another session, `editorJoined` is `false` again.

### `POST /api/camera/open`

Body: `{}`. Brings the phone's Capture screen to the front (the app is in the foreground, since the server only runs then) and waits up to 10 s for its camera. Returns `200 {"cameraOpen": true}` straight away if it's already open, `503 camera_unavailable` if it didn't open in time (for example while the phone asks for camera access).

### Job object

```json
{
  "id": "j-7",
  "kind": "capture",
  "source": "editor",
  "target": { "kind": "shots", "sessionId": null, "name": null },
  "params": { "video": true, "auto": true, "setLabel": null },
  "state": "waiting",
  "progress": { "step": 2, "of": 6, "text": "Moving to 90°…" },
  "prompt": {
    "id": "p-3",
    "kind": "move_failed",
    "text": "The table didn't move. Check the IR path, then retry.",
    "answers": ["retry", "manual", "stop"],
    "view": "side-090",
    "targetDeg": 90,
    "targetTableDeg": 123.0,
    "toleranceDeg": 2.0,
    "reason": "no_movement"
  },
  "startedAt": "2026-11-04T18:22:05.120Z",
  "endedAt": null,
  "result": null,
  "error": null,
  "stoppedBy": null,
  "shotIds": ["s-11"]
}
```

| `state` | Meaning |
| --- | --- |
| `running` | Working. `prompt` is `null` |
| `waiting` | Waiting for an answer to `prompt` |
| `done` | Finished. `result` is set |
| `failed` | Stopped by an error. `error` is `{error, message}` using the codes in [Errors](#errors) plus `move_failed`, `ring_lost` and `calibration_failed` |
| `aborted` | Stopped by `DELETE /api/jobs/current`, by Stop on the phone, or because the phone left its Capture screen or the app went to the background |

- `source` is `phone` (started with the phone's buttons) or `editor` (started through this API, from any tab).
- `target` is where the job's files go: `{"kind": "shots"}` for the editor's shots, `{"kind": "session", "sessionId": "…", "name": "Mk9"}` for a phone session, or `{"kind": "none"}` for jobs that make no files (calibration, moves). Fields that don't apply are `null`.
- `stoppedBy` is `phone`, `editor` or `null`, set when the job was stopped, so each side can say who stopped it.
- `progress.step` and `progress.of` are optional (`null`) for jobs without a natural count. `text` is always set while running.
- For a capture, the steps are one per view, then the spin if `video` is on, then homing. So a full set with video is 6 steps.
- `shotIds` lists the shots this job has produced so far. A job writing into a session produces no shots: `shotIds` stays empty, and `filenames` lists the session files written so far (it's `[]` for other jobs).
- `GET /api/jobs/current` returns the running job, or else the last finished one (so a tab that reconnects can see how it ended), or `null` if there hasn't been one since the app started.

### Prompts

| `kind` | When | `answers` | Extra fields |
| --- | --- | --- | --- |
| `move_failed` | The table didn't move, the ring was lost, it kept turning when it needed to be at rest, or it missed the target | `retry`, `stop` (older phones also offered `manual`) | `view`, `targetDeg`, `targetTableDeg`, `toleranceDeg`, `reason` (`no_movement`, `ring_lost`, `turning`, `missed_target`) |
| `position` | By hand: the user has to turn the table (or the badge) to a view and press Capture; or, for the spin, `stage: "start"` asks whether to record it and `stage: "recording"` waits for the end of the recording | `ready`, `stop`; for `stage: "start"`, `ready`, `skip` | `view`, `targetDeg`, `targetTableDeg`, `toleranceDeg`, `stage` (spin only: `start` or `recording`) |
| `spin_done` | Manual spin without the ring: recording, and the phone can't tell when a full turn has gone by | `done`, `stop` | `view` |

- `view` is the shot's file stem (`side-090`, or `with-saos-installed-side-090` in an extra set). It's `null` for a plain move.
- `targetDeg` is the view's nominal angle, measured from where the capture started (so 90 for `side-090`). `null` for the spin and for moves.
- `targetTableDeg` is the same target as a table angle, comparable with `ring.tableDeg`, so the editor can show a dial of where the table is against where it should be. `null` when that isn't known (not calibrated, ring not seen at the start, or the spin).
- `toleranceDeg` is the positioning tolerance (default 2).

**The spin by hand** follows [Seamless spin loop](Spec.md#seamless-spin-loop), and the prompts carry the user through it:

1. `position` with `view: "spin"`, `stage: "start"`, all three angles `null`, and `answers` `ready`, `skip`. Text: "Press Record spin to record the spin video, or Skip video". `skip` ends the capture with the views kept (`complete: true`). Recording starts after `ready`, not before.
2. `position` with `view: "spin"`, `stage: "recording"`, and `answers` `ready`, `stop`. Text: "Recording: turn the table (or the badge) once round, then press Stop". `ready` stops the recording and keeps it. `stop` throws it away (`complete: false`).
3. With the ring, the clip is cut to exactly one turn; without it, the whole clip is kept, up to 30 s. Then the job carries on with encoding, and `progress.text` is "Encoding the spin video…".

On the phone, the big button follows the prompt: **Capture** for a view, **Record spin** at `stage: "start"`, **Stop** at `stage: "recording"`. The small one is **Stop**, **Skip video** or **Discard**.

`POST /api/jobs/current/answer` with `{"promptId": "p-3", "answer": "retry"}`:

- The **first answer wins**, whether it comes from the phone or any editor tab. A late or stale answer gets `409 prompt_gone`. An answer not in `answers` gets `400 bad_request`.
- `stop` ends the job early. For a capture it's `done` with `complete: false`. For a move or calibration it's `aborted`.
- The response is the job after the answer is applied.

On the phone, prompts are the existing dialogs, and the phone dismisses its dialog when the editor answers first.

### Stopping

`DELETE /api/jobs/current` aborts the running job: stop the table (`stop()`), stop any recording, keep the shots already taken, and home if the phone was driving the table (`auto`). Returns the job, which may still be `running` for a moment while it winds down; the final state arrives as a `job` message. `409 no_job` if nothing is running.

Losing the editor doesn't stop a job. If the phone drops off the network mid-capture, the job carries on and **Stop** on the phone still works. If Stop is pressed in the editor while it can't reach the phone, the editor holds the request and sends `DELETE /api/jobs/current` straight after reconnecting, but only if `GET /api/jobs/current` still shows the same job `id` running or waiting. (The phone leaving its Capture screen is different: the job is aborted, as the `aborted` state above says. If the app went to the background, the server stops too.)

## Shots

When the [capture target](#capture-target) is `shots`, the phone holds captures as **one unnamed set of shots**: no name or notes. The editor decides where they go. Like sessions, only web copies are kept: `original` is always `null`.

### Shot object

```json
{
  "id": "s-12",
  "kind": "view",
  "setLabel": null,
  "view": "side-090",
  "label": null,
  "filename": "side-090.jpg",
  "caption": "Side (90°)",
  "nominalDeg": 90,
  "actualDeg": 90.6,
  "takenAt": "2026-11-04T18:22:31.040Z",
  "jobId": "j-7",
  "ready": true,
  "web":      { "mime": "image/jpeg", "bytes": 812345,  "width": 2400, "height": 1800, "filename": "side-090.jpg" },
  "original": null,
  "durationMs": null
}
```

| `kind` | `filename` | `caption` |
| --- | --- | --- |
| `view` | `front.jpg`, `side-090.jpg`, `back.jpg`, `side-270.jpg` | `Front`, `Side (90°)`, `Back`, `Side (270°)` |
| `view` in an extra set | `<set>-front.jpg`, … where `<set>` is `Slugs.kebab(setLabel)` | `With SAOs installed (front)`, … (`View.caption(setLabel)`) |
| `spin` | `spin.mp4` | `Spin` |
| `spin` in an extra set | `<set>-spin.mp4` | `With SAOs installed (spin)` |
| `extra` | `Slugs.extraShotName(label, held filenames)`: `lanyard.jpg`, `lanyard-2.jpg` | the label |

- **Re-shooting replaces.** A new `view` or `spin` shot whose `filename` matches a held shot replaces it: the old shot is deleted, and the new one gets a new `id`. Extra shots never replace; they're numbered.
- **Order.** `GET /api/shots` returns shots in the standard order: the main set's views (front, 90, back, 270), the main spin, then each extra set (views, then spin) in the order the sets were first shot, then extra shots by time. The editor keeps this order.
- `nominalDeg` and `actualDeg` are measured from where the table was when the capture started (the front view, as the user framed it), so `front` is 0. They're `null` for extra shots and spins, and `actualDeg` is `null` when the ring wasn't read.
- `ready` is `false` while a spin video is transcoding. It turns `true` with a `shot` message on the WebSocket.
- `web` describes the file that goes in the badge folder: a 2400 px JPEG at quality 85, or the transcoded MP4. `width` and `height` may be `null` for videos. `durationMs` is set for spins.
- `original` is always `null`: the phone deletes the full-size still or source video once the web copy is made (Spec.md, Output). The field stays so older editors don't break.
- Shots survive the editor disconnecting and the app being restarted. They're kept in the app's storage until deleted.

### `GET /api/shots/{id}`

Returns the file with `Content-Type`, `Content-Length`, `Content-Disposition: attachment; filename="<name>"` and `X-Shot-Filename: <name>`.

| `?variant=` | File |
| --- | --- |
| `web` (default) | The web copy |
| `original` | Always `404 not_found`: the phone keeps only the web copy |
| `thumb` | A JPEG about 320 px on the long edge, quality 75. For a spin, a frame from its start. Available as soon as the shot exists, even while `ready` is `false` |

`409 not_ready` for `web` of a spin that's still transcoding. No `Range` support is needed: the editor downloads whole files.

### `DELETE /api/shots/{id}` and `DELETE /api/shots`

Delete one shot (`204`), or all of them (`200 {"deleted": 5}`). `DELETE /api/shots` while a job is running is `409 busy`. Each removal is pushed as a `shot_removed` message.

### `GET /api/session.json`

The capture record of the held shots: the jobs that made them, the calibration and the camera settings. Older editors saved it to `_capture/` with **Also save originals**; with no originals on the phone, the editor no longer needs it, and it's kept so they don't break:

```json
{
  "api": 1,
  "device": { "deviceId": "6f1c0b9e2a7d4c35", "model": "OnePlus CPH2749", "appVersion": "0.3.0" },
  "turntable": { "profileId": "jayegt-5.74", "markerCount": 45 },
  "calibration": { "…": "as in Status, or null" },
  "jobs": [
    {
      "id": "j-7", "kind": "capture", "params": { "video": true, "auto": true, "setLabel": null },
      "startedAt": "…", "endedAt": "…", "state": "done",
      "camera": { "…": "settings in use, as in GET /api/camera" },
      "shotIds": ["s-11", "s-12"]
    }
  ],
  "shots": [ { "…": "every held shot, as in GET /api/shots" } ]
}
```

`jobs` lists the jobs that produced the held shots, oldest first.

## Sessions

Sessions are the badges captured with the phone on its own: a name, optional notes and the files captured for them, kept in the phone's `_badges/` and `_capture/` folders (see [Spec.md § Sessions](Spec.md#sessions)). The editor lists them, pulls one in to work on, and deletes what it no longer needs. It doesn't rename them or change their notes. These endpoints work from any screen; the Capture screen doesn't need to be open.

### Session summary and Session object

`GET /api/sessions` returns `{"sessions": […]}`, newest first, each a summary:

```json
{
  "id": "0b6f3c2e-5d1a-4e7b-9a0c-2f4d6e8a1b3c",
  "name": "Mk9",
  "createdAt": "2026-11-07T15:02:11.000Z",
  "updatedAt": "2026-11-07T15:20:47.500Z",
  "open": true,
  "busy": false,
  "hasNotes": true,
  "mediaCount": 6,
  "bytes": 14123456,
  "cover": "front.jpg"
}
```

- `id` is opaque and never changes, even when the session is renamed.
- `updatedAt` changes on every save, capture or delete. The editor can compare it to tell whether a session it pulled in has changed since.
- `open` is `true` for the session open on the phone's Capture screen. `busy` is `true` while a job is writing into it.
- `bytes` counts the session's files (web copies and spins).
- `cover` is the filename of the card thumbnail (`front.jpg`, else the first photo), or `null` when there are no photos. Fetch it with `?variant=thumb`.

`GET /api/sessions/{id}` returns the summary's fields plus:

```json
{
  "notes": "Has a hidden game.\n\nHold both buttons at boot.",
  "media": [
    {
      "filename": "side-090.jpg",
      "kind": "view",
      "setLabel": null,
      "view": "side-090",
      "label": null,
      "caption": "Side (90°)",
      "nominalDeg": 90,
      "actualDeg": 90.6,
      "takenAt": "2026-11-07T15:10:31.040Z",
      "ready": true,
      "web":      { "mime": "image/jpeg", "bytes": 812345,  "width": 2400, "height": 1800, "filename": "side-090.jpg" },
      "original": null,
      "durationMs": null
    }
  ]
}
```

- `notes` is the body of the session's `index.md` (what the phone's Notes field shows), or `""`.
- Each `media` entry has the [Shot object](#shot-object)'s fields without `id` and `jobId`. `filename` is its key within the session. They're in the same [standard order](#shot-object) as shots, so the editor can add them the way it adds shots. `original` is always `null`, and `ready` always `true`: a spin is added to its session only once it's transcoded.

### `GET /api/sessions/{id}/media/{filename}`

The same as [`GET /api/shots/{id}`](#get-apishotsid): `?variant=web` (default) or `thumb` (made the first time it's asked for), the same headers, and `original` is `404`. `filename` is URL-encoded. `404 not_found` for an unknown session or file.

### `DELETE /api/sessions/{id}/media/{filename}` and `DELETE /api/sessions/{id}`

- **One file:** removes it and its thumbnail, drops it from `session.json`, and returns `204`. `409 busy` while a job is writing into this session.
- **The whole session:** removes its folder and returns `204`. `409 session_open` (with `sessionId`) while it's the [capture target](#capture-target): the phone moves on first. `409 busy` while a job is writing into it.
- The phone confirms nothing: the editor asks the user first. A Session screen showing the session on the phone reloads, or closes with a note if the session went.

Each change is pushed as a `session` or `session_removed` message.

## WebSocket `/api/live`

`ws://<host>:8737/api/live`. One socket per editor tab. Several can be open at once, and they all get the same messages.

### Opening

1. The client's **first message**, within 5 s, must be `{"type": "auth", "token": "<token>"}`.
2. The phone replies `{"type": "hello", "api": 1, "deviceId": "…", "name": "…"}`, then a `status` message straight away, then the regular stream.

Close codes from the phone: `4401` bad token, `4408` no auth message within 5 s, `4409` too many clients (more than 4), `1001` the server is stopping (app backgrounded or remote control turned off). Moving between the app's screens doesn't close the socket; the Capture screen opening or closing shows up as `cameraOpen` in the next `status`. The editor reconnects with backoff (1, 2, 4… up to 15 s), except after `4401`.

The phone sends a WebSocket ping every 15 s and closes a socket that hasn't answered within 30 s. Browsers answer pings on their own.

The `status` message doubles as the heartbeat: **send it about every 500 ms even when nothing has changed.** Browsers can't see pings, so the editor treats 4 s with no message at all as a lost connection: it closes the socket, shows the phone as out of contact and reconnects.

### Phone → editor: JSON text messages

Every message has a `type`.

| `type` | When | Body |
| --- | --- | --- |
| `hello` | Once, after auth | `api`, `deviceId`, `name` |
| `status` | About every 500 ms, and on any change to job, calibration or shot count | The [Status](#get-apistatus) object's fields, flattened into the message |
| `ring` | Every ring reading, at most 5 per second, while the Capture screen is open | See below |
| `job` | Every change to the current job: started, progress, prompt raised or answered, finished | `job`: the [Job object](#job-object) |
| `shot` | A shot was added, or changed (a spin became `ready`) | `shot`: the [Shot object](#shot-object) |
| `shot_removed` | A shot was deleted or replaced | `id` |
| `session` | A session was created, saved, captured into, had a file deleted, or was opened or closed on the Capture screen | `session`: the [Session summary](#session-summary-and-session-object) |
| `session_removed` | A session was deleted (from the phone or the API) | `id` |
| `target` | The [capture target](#capture-target) changed, from the phone or a tab | `target`, `previous` (both as in Status), `by` (`phone` or `editor`) |
| `camera` | Settings changed, from anywhere | `settings` |
| `countdown` | During an extra shot's or manual still's countdown, once per second | `remainingMs` |
| `notice` | Something for the user that isn't a job event (for example "Pairing code changed") | `level` (`info`, `warn`), `text` |

`ring` message:

```json
{
  "type": "ring",
  "frameUs": 81234567890,
  "ok": true,
  "ringDeg": 123.4,
  "tableDeg": 0.3,
  "frontDeg": 0.3,
  "markers": [ { "id": 14, "x": 0.512, "y": 0.774 }, { "id": 15, "x": 0.561, "y": 0.772 } ],
  "rmsPx": 0.8,
  "axis": { "x1": 0.50, "y1": 0.10, "x2": 0.50, "y2": 0.95 }
}
```

`markers` are the detected marker centres, and `axis` is the turntable's axis line, both in the upright preview frame's normalised coordinates (as for focus). When the ring isn't read, `ok` is `false`, the angles are `null`, `markers` is empty and `axis` is the last known axis or `null`.

### Phone → editor: binary preview frames

Each binary message is one preview frame: a 16-byte header, then a complete JPEG.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | Magic, ASCII `BTPV` |
| 4 | 1 | Header version, `1` |
| 5 | 1 | Rotation: clockwise quarter turns (0–3) to display the JPEG upright |
| 6 | 2 | Reserved, 0 |
| 8 | 8 | `frameUs`, signed 64-bit big-endian: the camera timestamp in microseconds (the same clock as `ring.frameUs`) |
| 16 | … | JPEG |

- Frames come from the `ImageAnalysis` frames the ring detector already gets, scaled so the **upright** frame's long edge is the requested `width`, and JPEG-encoded at quality 70. The phone may rotate the image itself and send rotation 0.
- Defaults after `hello`: on, 8 fps, 640 px. Frames only flow while the Capture screen is open; the socket's preview setting is kept while it's closed.
- **Backpressure:** keep at most one frame waiting per socket. If one is still waiting when a newer frame is ready, the newer one replaces it. Never queue preview frames behind each other.

### Editor → phone

| Message | Meaning |
| --- | --- |
| `{"type": "auth", "token": "…"}` | Must be first |
| `{"type": "preview", "enabled": true, "fps": 8, "width": 640}` | Change this socket's preview. All fields optional. `fps` 1–15, `width` 160–1280. `enabled: false` pauses it (the editor does this while its tab is hidden) |

Unknown messages are ignored.

## Discovery

- **mDNS host:** the app's mDNS responder answers A queries for `<name>.local` (default `badge-turntable`).
- **mDNS service:** `_badge-turntable._tcp.local.` on the server port, with TXT records `api=1`, `id=<deviceId>`, `path=/api/`. The editor can't browse it (web pages can't do mDNS), but other tools can.
- **The editor probes** `GET /api/info` on `badge-turntable.local:8737`, on each remembered device's name and last IP address, and on any address the user added, all at once, with a 1.5 s timeout each. Phones that answer are listed, and results with the same `deviceId` are merged.

## Implementation notes for the app

These aren't part of the wire format, but they're what the editor expects. They're implemented in the app's `remote`, `jobs` and `rig` packages.

- **Server lifetime:** start the server, mDNS and the Wi-Fi `MulticastLock` when the app comes to the foreground with remote control on, and stop them when it goes to the background: `ProcessLifecycleOwner` (`androidx.lifecycle:lifecycle-process`) `ON_START` and `ON_STOP`, so moving between screens and dialogs doesn't drop the editor. Turning remote control on or off in Settings starts or stops it straight away. Stopping closes every WebSocket with `1001`. The server and the job runner live in the `Rig` (application scope); the Capture screen attaches the camera, ring and sequencer when it starts and detaches them when it stops, aborting any job.
- **Camera not open:** while no Capture screen is attached, the **Cam** endpoints answer `503 camera_unavailable` before any other check, and `status` has `cameraOpen: false`.
- **Sessions:** the API reads and deletes through `SessionStore`, the same as the phone's screens, on a background thread. Holding the store's lock around each delete, and checking the running job's target first, keeps a delete from racing a capture into the same session.
- **Threads:** the server handles each connection on its own thread. Jobs run on the job runner's single thread. Camera calls go through `CameraController`, which already marshals to the main thread. Hold one lock around "is a job running / start a job", so two requests can't both start one.
- **Prompts:** `CaptureSequencer.Listener.onMoveFailed` and `awaitManualPosition` block on `Job.ask`, which raises a prompt, pushes a `job` message, and returns whichever side answers first (`CompletableFuture.complete` returns `false` for the loser, which maps to `409 prompt_gone`).
- **Status messages:** `Listener.onStatus(text)` becomes `progress.text`, and `onStep(step, of, text)` sets `progress.step` and `progress.of`.
- **Where captures go:** the sequencer writes through a `CaptureTarget`: `SessionTarget` for a session (`sessions/<id>/`), or `RemoteShots`, whose folders are `files/remote/web/`, `files/remote/scratch/` and `files/remote/thumbs/`, with a `shots.json` index, so shots survive a restart. Full-size stills and source videos go to a scratch file and are deleted once the web copy is made. A job's target is the `Station`'s capture target when it starts (`Station.targetFor(source)`), so switching can't split a job.
- **Rotation and coordinates:** the analysis frames arrive in sensor orientation. The app rotates each preview frame upright as it scales it, and sends rotation 0. Ring overlay and focus coordinates are in the upright frame, so rotate the detector's points (and un-rotate focus points before building a `MeteringPoint`).
- **Preview encoding:** encode on the analysis thread only when at least one socket wants a frame and its last frame has gone, and only at the requested fps. Reuse one `ByteArrayOutputStream`.
- **Large files:** stream shot files with their length, so a 10 MB video isn't loaded into memory.
- **Testing without the editor:** `curl -s http://<phone>:8737/api/info`, then pair with `curl -s -X POST -H 'Content-Type: application/json' -d '{"code":"123456","clientName":"curl"}' http://<phone>:8737/api/pair`.
