# WebRTC bridge

The phone's side of the Remote API's **WebRTC transport** ([../../Remote-API.md § WebRTC transport](../../Remote-API.md#webrtc-transport)), in Go with [Pion](https://github.com/pion/webrtc). After pairing, the web editor reaches the phone over WebRTC data channels instead of plain HTTP, so its https page keeps its padlock and the token, photos and preview are encrypted.

The bridge doesn't know the API. It relays each channel to the app's own HTTP server on loopback:

- **`req`:** one channel per request, relayed as an HTTP request to `http://127.0.0.1:<port>/api/…`. Only the path and query come from the editor, and only paths under `/api/`.
- **`live`:** relayed to the `/api/live` WebSocket, message for message, with a `close` message carrying the WebSocket's close code.

It also enforces the transport's limits: 8 connections not yet authenticated, 10 s to authenticate (shown by the app's `X-Paired: true` header, or `hello` on `live`), and 4 authenticated.

| Path | What |
| --- | --- |
| `relay/` | The transport: ICE-lite on one UDP port, the connection limits, the `req` and `live` relays, the certificate. Tested in `relay_test.go`, with Pion as the browser. |
| `bridge/` | The gomobile-bound API the app calls: `Start(bind, udpPort, upstream, certPath)`, `Fingerprint()`, `Stop()`. In Java it's `org.snurkle.webrtc.bridge.Bridge`, which the app reaches by reflection (`remote.WebRtcTransport`), so it builds and runs without it. |
| `cmd/webrtc-bridge/` | The same as a desktop program, to put in front of the mock phone. |
| `build-aar.ps1` | Builds `../app/libs/webrtc-bridge.aar`. |

## Needs

- **Go 1.27** or later (`go.mod`).
- For the AAR: **gomobile and gobind** (`go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest`, or `go tool gomobile` from this folder), a JDK, and the **Android NDK** (Android Studio → SDK Manager → SDK Tools → NDK (Side by side); built with NDK 30.0.16248370).

## Build the AAR

```
cd webrtc-bridge
.\build-aar.ps1
```

It runs `gomobile bind -target=android/arm64 -androidapi 31 -javapkg org.snurkle.webrtc` with `-ldflags='-s -w -checklinkname=0'` into `../app/libs/webrtc-bridge.aar` (about 4 MB, 16 KB page aligned), which the app picks up with `fileTree(dir: 'libs', include: ['*.aar'])`. The AAR is committed, so the app builds without Go. Rebuild it after changing anything here.

- **arm64 only**, to keep it small. On other ABIs (an x86 emulator) the native library doesn't load, and the app runs with plain HTTP (`webrtc: null` in Info).
- **`-checklinkname=0`:** `github.com/wlynxg/anet`, which Pion uses to list network interfaces on Android 11 and later, reaches into `net` with `//go:linkname`, which Go 1.23+ refuses without the flag.

## Test

```
go test ./...
```

The tests run a fake API and connect with Pion the way the editor does (a made-up ufrag, the phone's answer written by the client): requests, a 5 MB download, a truncated body, close codes, refused paths, a wrong fingerprint, and the connection limits.

## With the mock phone

```
go run ./cmd/webrtc-bridge -udp 8738 -upstream 127.0.0.1:8737 -cert ../remote-mock/mock-bridge-cert.pem
python ../remote-mock/mock_phone.py
```

The bridge makes the certificate on its first run. The mock reads the same PEM for `webrtc` in Info and Pair (`null` until it exists). Add `-bind 127.0.0.1` to keep the bridge off the LAN.

## On the phone

`RemoteServer` starts the bridge after the HTTP server, on the Wi-Fi IPv4 addresses only (not mobile data), with UDP port = HTTP port + 1 and the certificate in the app's files (`webrtc-cert.pem`). It stops it after closing the WebSockets with 1001. Logs go to logcat under `GoLog`.

Tried on the OnePlus 9 Pro on Nov 8, 2026, from headless Edge on `https://hamster.github.io/badges/edit/`: the page stayed secure; connecting took 0.4 s by IP and 0.25 s by `badge-turntable.local` alone; a 10.9 MB spin came over Wi-Fi at 18.5 MB/s, byte for byte (45 MB/s over HTTP); `live` gave hello, status every 500 ms, ring readings and about 7 preview frames a second; a wrong fingerprint was refused; and backgrounding the app sent `close 1001`, after which the editor reconnected.
