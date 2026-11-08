# Mock phone for the remote-control API

`mock_phone.py` pretends to be the Badge Turntable app with remote control turned on. It implements every endpoint and WebSocket message in [../../Remote-API.md](../../Remote-API.md), with a simulated turntable, encoder ring and camera, so the editor's Turntable panel can be used and tested without the phone.

It's also the reference for the app: when the API text is unclear, the app should do what the mock does. One exception: the mock always captures with the **Stop at each view** method (a prompt for each view by hand). It doesn't simulate the app's default **Spin** method, where the stills are taken while the table turns. The app's implementation is in `app/src/main/java/org/snurkle/badgeturntable/remote/`.

Needs Python 3.10+ and Pillow (`pip install pillow`). With `ffmpeg` on the PATH, spin videos are real MP4s; without it they're empty files.

## Run it

```
python mock_phone.py
```

- The phone listens on `http://127.0.0.1:8737/api/` and prints its pairing code (it changes every 2 minutes, like the app's).
- It also serves the editor at **http://localhost:4000/badges/edit/**, a stand-in for `jekyll serve` that's just enough for `/edit/`. The editor lives in the [badges](https://github.com/hamster/badges) site repo: the mock serves it from a checkout beside this repo (`../badges`), or from `--site <path>`. Without one, it runs the phone only. Like the app, the mock accepts calls from any origin; the pairing code and token are the protection.
- In the editor, open your repo, expand **Turntable**, and add the phone by address: `127.0.0.1`, port `8737`. Then pair with the printed code.

`--host 0.0.0.0` puts the mock on the LAN, so it can be tried from the published editor on GitHub Pages (which exercises Chrome's Local Network Access prompt).

### With the WebRTC transport

The mock speaks plain HTTP only. For the [WebRTC transport](../../Remote-API.md#webrtc-transport), run the desktop [WebRTC bridge](../webrtc-bridge/README.md) beside it, as the app runs the same bridge in front of its own server:

```
go run ../webrtc-bridge/cmd/webrtc-bridge -udp 8738 -upstream 127.0.0.1:8737 -cert mock-bridge-cert.pem
python mock_phone.py
```

The bridge makes `mock-bridge-cert.pem` on its first run, and the mock reads it to put `webrtc` (the UDP port and the certificate's fingerprint) in Info and Pair. Without it, `webrtc` is `null` and the editor uses plain HTTP. Like the app, the mock marks responses to a request with a valid token with `X-Paired: true`, which is how the bridge knows a connection has authenticated.

## Options

| Option | Effect |
| --- | --- |
| `--code 123456` | Fixed pairing code |
| `--token <hex>` | A pre-paired token, for curl |
| `--calibrated` | Start calibrated |
| `--fail-move N` | The Nth closed-loop move fails with `no_movement` (repeatable) |
| `--ring-blocked` | Start with the ring hidden |
| `--deg-per-sec 24` | Table speed. The default, 90, keeps tests quick; the JAYEGT does about 24 |
| `--sessions 2` | Start with that many sessions "captured on the phone" (four views and a lanyard shot each); the last one is open, so the editor has to join it or start a new set |
| `--camera-closed` | Start as if the phone isn't on its Camera screen: camera endpoints answer `503` until `POST /api/camera/open` or `/mock/camera?open=1` |
| `--rotate 0` | Preview header rotation. The default, 1, sends frames turned on their side, to test that the editor rotates them back |
| `--port`, `--editor-port`, `--name` | As named. `--editor-port 0` turns the editor server off |
| `--site <path>` | The badges site checkout to serve the editor from (default: `badges` beside this repo) |
| `--webrtc-cert <pem>` | The WebRTC bridge's certificate, for `webrtc` in Info and Pair (default: `mock-bridge-cert.pem` here) |
| `--webrtc-port N` | The bridge's UDP port (default: `--port` + 1) |
| `--verbose` | Log every request |

## Test controls

Plain GETs, no auth, not part of the real API. Each returns the mock's state.

| URL | Effect |
| --- | --- |
| `/mock` | Just the state (pairing code, calibration, running job) |
| `/mock/answer?answer=retry` | Answer the current prompt (`retry`, `manual`, `ready`, `done` or `stop`), as if on the phone (tests "first answer wins") |
| `/mock/ring?blocked=1` | Hide the ring (`blocked=0` shows it) |
| `/mock/offline?ms=10000` | Drop off the network for that long, as if Wi-Fi went: requests go unanswered and WebSocket messages are lost, while a running job carries on (`ms=0` comes back) |
| `/mock/fail-move?n=1` | Make the next (or nth from now) closed-loop move fail |
| `/mock/camera?open=0` | Leave the Camera screen on the phone (stops a running job); `open=1` opens it |
| `/mock/session?name=Mk9` | Make a session on the phone and open it, as the user at the phone would (`open=0` to leave it closed) |
| `/mock/open?id=<id>` | Open that session on the phone (the editor has to join it again) |
| `/mock/close` | Close the phone's session |
| `/mock/uncalibrate` | Forget the calibration |
| `/mock/revoke` | Revoke every token (tests the editor's re-pairing) |
| `/mock/reset` | Revoke tokens, forget calibration and the front, drop shots and sessions, clear the target, close sockets |

## Try the API with curl

```
curl -s http://127.0.0.1:8737/api/info
curl -s -X POST -H "Content-Type: application/json" -d "{\"code\":\"123456\",\"clientName\":\"curl\"}" http://127.0.0.1:8737/api/pair
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:8737/api/status
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:8737/api/sessions
curl -s -X POST -H "Authorization: Bearer <token>" -d "{\"shots\":true}" http://127.0.0.1:8737/api/target
```
