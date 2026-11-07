#!/usr/bin/env python3
"""Mock Badge Turntable phone.

Implements the remote-control API in ../../Remote-API.md with a simulated
turntable, encoder ring and camera, so the editor's Turntable panel can be
built and tested without the phone. It's also the reference implementation
for the app: when the API text is unclear, do what this does.

Needs Python 3.10+ and Pillow. ffmpeg (optional) makes real spin videos.

    python mock_phone.py                    # phone on :8737, editor on :4000
    python mock_phone.py --code 123456      # fixed pairing code
    python mock_phone.py --fail-move 2      # the 2nd closed-loop move fails
    python mock_phone.py --sessions 2       # two sessions captured on the phone, one open
    python mock_phone.py --camera-closed    # the phone isn't on its Camera screen

Then open http://localhost:4000/badges/edit/ in Chrome or Edge. The editor is
served from a checkout of the badges site repo (hamster/badges), by default
one beside this repo; --site points elsewhere.
"""

import argparse
import base64
import collections
import datetime
import hashlib
import io
import json
import math
import os
import random
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

try:
    from PIL import Image, ImageDraw, ImageEnhance, ImageFont
except ImportError:
    sys.exit("Pillow is needed: pip install pillow")

API_VERSION = 1
APP_VERSION = "mock-1"
ALL_ROLES = ["start_stop", "turn_left", "turn_right", "speed_up", "speed_down", "continuous_mode",
             "intermittent_mode", "step_45", "step_90", "step_180", "set_origin", "return_to_origin"]
JAYEGT_ROLES = ["start_stop", "turn_left", "turn_right", "speed_up", "speed_down", "continuous_mode",
                "intermittent_mode", "step_90", "set_origin", "return_to_origin"]
VIEWS = [(0, "front", "Front"), (90, "side-090", "Side (90°)"), (180, "back", "Back"), (270, "side-270", "Side (270°)")]
MARKERS = 45
MARKER_PITCH = 8.0
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
HERE = os.path.dirname(os.path.abspath(__file__))
# The badges site repo, for the editor server: by default checked out beside this repo.
DEFAULT_SITE_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "badges"))
SITE_ROOT = DEFAULT_SITE_ROOT  # set from --site in main()

LENSES = [
    {"id": "0", "label": "Back (logical)", "focalLengthMm": 5.59, "minFocusCm": 10},
    {"id": "2", "label": "Main", "focalLengthMm": 5.59, "minFocusCm": 10},
    {"id": "3", "label": "Ultrawide / macro", "focalLengthMm": 2.31, "minFocusCm": 3.5},
    {"id": "4", "label": "Telephoto", "focalLengthMm": 12.19, "minFocusCm": 40},
]
LENS_CAPS = {
    "0": {"iso": [100, 6400]}, "2": {"iso": [100, 6400]},
    "3": {"iso": [100, 6200]}, "4": {"iso": [100, 16000]},
}
WB_MODES = ["auto", "daylight", "cloudy", "incandescent", "fluorescent", "kelvin"]


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def frame_us():
    return int(time.monotonic() * 1_000_000)


def kebab(s):
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return re.sub(r"^-+|-+$", "", re.sub(r"[^a-z0-9]+", "-", s))


def fwd(a, b):
    """Forward travel from angle a to b, in [0, 360)."""
    return (b - a) % 360.0


class ApiError(Exception):
    def __init__(self, status, code, message, **extra):
        super().__init__(message)
        self.status, self.code, self.message, self.extra = status, code, message, extra

    def body(self):
        return dict({"error": self.code, "message": self.message}, **self.extra)


class Aborted(Exception):
    pass


class MoveFailed(Exception):
    def __init__(self, reason, message):
        super().__init__(message)
        self.reason, self.message = reason, message


# ---------------------------------------------------------------------------
# Simulated turntable
# ---------------------------------------------------------------------------

class Table:
    """Platter angle (as the ring reads it) driven by IR roles, ticked at 50 Hz."""

    def __init__(self, deg_per_sec, start_deg):
        self.lock = threading.Lock()
        self.raw = start_deg
        self.speed = deg_per_sec
        self.direction = 0        # +1 / -1 while moving
        self.remaining = None     # degrees left for a step / coast; None = continuous
        self.origin = None        # the table's own Set Origin position
        self.overshoot = 3.0
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        last = time.monotonic()
        while True:
            time.sleep(0.02)
            now = time.monotonic()
            dt, last = now - last, now
            with self.lock:
                if not self.direction:
                    continue
                d = self.speed * dt
                if self.remaining is not None:
                    d = min(d, self.remaining)
                    self.remaining -= d
                self.raw = (self.raw + self.direction * d) % 360.0
                if self.remaining is not None and self.remaining <= 1e-6:
                    self.direction, self.remaining = 0, None

    def moving(self):
        with self.lock:
            return self.direction != 0

    def angle(self):
        with self.lock:
            return self.raw

    def spin(self, direction=1):
        with self.lock:
            self.direction, self.remaining = direction, None

    def stop(self):
        with self.lock:
            if self.direction and self.remaining is None:
                self.remaining = self.overshoot  # coast

    def step(self, degrees):
        with self.lock:
            self.direction = 1 if degrees >= 0 else -1
            self.remaining = abs(degrees)

    def set_to(self, raw):
        with self.lock:
            self.raw, self.direction, self.remaining = raw % 360.0, 0, None

    def press(self, role):
        if role == "start_stop":
            if self.moving():
                self.stop()
            else:
                self.spin(1)
        elif role in ("turn_right", "continuous_mode"):
            self.spin(1)
        elif role == "turn_left":
            self.spin(-1)
        elif role in ("step_45", "step_90", "step_180"):
            self.step(int(role.split("_")[1]) + random.uniform(-0.4, 0.6))
        elif role == "set_origin":
            with self.lock:
                self.origin = self.raw
        elif role == "return_to_origin":
            with self.lock:
                origin = self.origin
                raw = self.raw
            if origin is not None:
                self.step(fwd(raw, origin))
        # speed_up / speed_down / intermittent_mode: nothing to simulate


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

_fonts = {}


def font(size):
    size = max(8, int(size))
    if size not in _fonts:
        try:
            _fonts[size] = ImageFont.load_default(size=size)
        except TypeError:
            _fonts[size] = ImageFont.load_default()
    return _fonts[size]


def render_scene(w, h, ring_deg, zero_deg, label=None, cam=None, ring_visible=True):
    """Light box, platter with the encoder ring and a badge on a stand.
    Returns (image, markers, axis) with markers/axis normalised to the frame."""
    img = Image.new("RGB", (w, h), (236, 236, 232))
    d = ImageDraw.Draw(img)
    d.rectangle([0, int(h * 0.62), w, h], fill=(224, 224, 220))
    cx, cy = w * 0.5, h * 0.70
    rx, ry, band = w * 0.36, w * 0.06, w * 0.035
    d.ellipse([cx - rx, cy + band - ry, cx + rx, cy + band + ry], fill=(18, 18, 18))
    d.rectangle([cx - rx, cy, cx + rx, cy + band], fill=(18, 18, 18))
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=(78, 78, 82))
    markers = []
    for k in range(MARKERS):
        a = math.radians(k * MARKER_PITCH - ring_deg)
        c = math.cos(a)
        if c <= 0.2:
            continue
        x = cx + rx * math.sin(a)
        y = cy + band * 0.5 + ry * c
        s = band * 0.72
        sw = s * c
        d.rectangle([x - sw / 2, y - s / 2, x + sw / 2, y + s / 2], fill=(245, 245, 245))
        for bit in range(4):
            if (k >> bit) & 1:
                bx = x - sw / 2 + sw * (0.15 + 0.35 * (bit % 2))
                by = y - s / 2 + s * (0.15 + 0.35 * (bit // 2))
                d.rectangle([bx, by, bx + sw * 0.3, by + s * 0.3], fill=(18, 18, 18))
        if c > 0.45:
            markers.append({"id": k, "x": x / w, "y": y / h})
    if not ring_visible:
        d.rectangle([cx - rx * 0.6, cy - band, cx + rx * 0.6, cy + band * 2.5], fill=(150, 120, 90))
        markers = []

    t = math.radians(ring_deg - zero_deg)
    bw, bh, thick = w * 0.17, w * 0.23, w * 0.014
    half = abs(math.cos(t)) * bw / 2 + thick / 2
    top = cy - bh
    front = math.cos(t) >= 0
    d.line([cx, top, cx - w * 0.06, h * 0.02], fill=(190, 40, 40), width=max(2, int(w / 240)))
    d.line([cx, top, cx + w * 0.06, h * 0.02], fill=(190, 40, 40), width=max(2, int(w / 240)))
    d.rectangle([cx - half, top, cx + half, cy - ry * 0.2], fill=(38, 166, 154) if front else (230, 126, 34),
                outline=(30, 30, 30), width=max(1, int(w / 400)))
    if half > w * 0.05:
        d.text((cx, top + bh * 0.42), "FRONT" if front else "BACK", anchor="mm",
               font=font(min(half * 0.45, w * 0.035)), fill=(255, 255, 255))
    d.text((w * 0.02, h * 0.02), "MOCK PHONE", font=font(w * 0.025), fill=(150, 150, 150))
    if label:
        d.text((w * 0.02, h * 0.06), label, font=font(w * 0.03), fill=(60, 60, 60))
    axis = {"x1": 0.5, "y1": top / h, "x2": 0.5, "y2": (cy + band + ry) / h}

    if cam:
        lens = next((l for l in LENSES if l["id"] == cam["lens"]), LENSES[1])
        z = cam["zoom"] * lens["focalLengthMm"] / 5.59
        if abs(z - 1) > 1e-3:
            if z > 1:
                cw, ch = w / z, h / z
                img = img.crop((int((w - cw) / 2), int((h - ch) / 2), int((w + cw) / 2), int((h + ch) / 2))).resize((w, h))
            else:
                small = img.resize((max(1, int(w * z)), max(1, int(h * z))))
                img = Image.new("RGB", (w, h), (236, 236, 232))
                img.paste(small, ((w - small.width) // 2, (h - small.height) // 2))

            def tx(p, k):
                return (p - 0.5) * z + 0.5
            markers = [{"id": m["id"], "x": tx(m["x"], "x"), "y": tx(m["y"], "y")} for m in markers]
            markers = [m for m in markers if 0 <= m["x"] <= 1 and 0 <= m["y"] <= 1]
            axis = {k: tx(v, k) for k, v in axis.items()}
        if cam["exposureMode"] == "manual":
            factor = cam["iso"] * cam["shutterNs"] / (200 * 16_666_666)
        else:
            factor = 2 ** (cam["ev"] / 6.0)
        if abs(factor - 1) > 0.02:
            img = ImageEnhance.Brightness(img).enhance(max(0.05, min(factor, 4.0)))
    return img, markers, axis


def jpeg(img, quality):
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    return buf.getvalue()


# ---------------------------------------------------------------------------
# WebSocket (RFC 6455, just what this needs)
# ---------------------------------------------------------------------------

class WsClosed(Exception):
    pass


def ws_read_frame(rfile):
    head = rfile.read(2)
    if len(head) < 2:
        raise WsClosed()
    b0, b1 = head
    opcode, masked, n = b0 & 0x0F, b1 & 0x80, b1 & 0x7F
    if n == 126:
        n = struct.unpack(">H", rfile.read(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", rfile.read(8))[0]
    if n > 1 << 20:
        raise WsClosed()
    mask = rfile.read(4) if masked else b"\0\0\0\0"
    data = bytearray(rfile.read(n))
    if len(data) < n:
        raise WsClosed()
    for i in range(n):
        data[i] ^= mask[i % 4]
    return opcode, bytes(data)


def ws_frame(opcode, payload):
    n = len(payload)
    if n < 126:
        head = struct.pack(">BB", 0x80 | opcode, n)
    elif n < 1 << 16:
        head = struct.pack(">BBH", 0x80 | opcode, 126, n)
    else:
        head = struct.pack(">BBQ", 0x80 | opcode, 127, n)
    return head + payload


class Client:
    """One editor tab's WebSocket. JSON messages queue; preview frames don't."""

    def __init__(self, wfile, client_name):
        self.wfile = wfile
        self.client_name = client_name
        self.since = now_iso()
        self.cv = threading.Condition()
        self.msgs = collections.deque()
        self.frame = None
        self.close_code = None
        self.preview = {"enabled": True, "fps": 8, "width": 640}
        self.next_frame_at = 0.0
        self.dead = False

    def send_json(self, obj):
        with self.cv:
            self.msgs.append(json.dumps(obj, separators=(",", ":")).encode())
            self.cv.notify()

    def offer_frame(self, data):
        with self.cv:
            self.frame = data  # a newer frame replaces one still waiting
            self.cv.notify()

    def close(self, code, reason=""):
        with self.cv:
            self.close_code = (code, reason)
            self.cv.notify()

    def run_sender(self):
        last_ping = time.monotonic()
        try:
            while True:
                with self.cv:
                    while not self.msgs and self.frame is None and self.close_code is None:
                        if not self.cv.wait(timeout=15):
                            break
                    msgs = list(self.msgs)
                    self.msgs.clear()
                    frame, self.frame = self.frame, None
                    close = self.close_code
                if PHONE.offline():
                    msgs, frame = [], None  # lost in the air
                for m in msgs:
                    self.wfile.write(ws_frame(1, m))
                if frame is not None:
                    self.wfile.write(ws_frame(2, frame))
                if close:
                    self.wfile.write(ws_frame(8, struct.pack(">H", close[0]) + close[1].encode()[:120]))
                    return
                if time.monotonic() - last_ping > 15:
                    self.wfile.write(ws_frame(9, b"mock"))
                    last_ping = time.monotonic()
        except OSError:
            pass
        finally:
            self.dead = True


# ---------------------------------------------------------------------------
# Jobs
# ---------------------------------------------------------------------------

class Job:
    _n = 0

    def __init__(self, phone, kind, params, source="editor", target=None):
        Job._n += 1
        self.phone = phone
        self.id = "j-%d" % Job._n
        self.kind = kind
        self.params = params
        self.source = source
        # Where its files go: {kind: session | shots | none, sessionId, name}
        self.target = target or {"kind": "none", "sessionId": None, "name": None}
        self.stopped_by = None
        self.filenames = []
        self.state = "running"
        self.progress = {"step": None, "of": None, "text": "Starting…"}
        self.prompt = None
        self.started_at = now_iso()
        self.ended_at = None
        self.result = None
        self.error = None
        self.shot_ids = []
        self.camera = dict(phone.camera)
        self.abort_flag = threading.Event()
        self.answer_event = threading.Event()
        self.answer = None
        self.moved = False

    def to_json(self):
        return {"id": self.id, "kind": self.kind, "source": self.source, "target": dict(self.target),
                "params": self.params, "state": self.state,
                "progress": dict(self.progress), "prompt": self.prompt, "startedAt": self.started_at,
                "endedAt": self.ended_at, "result": self.result, "error": self.error,
                "stoppedBy": self.stopped_by, "shotIds": list(self.shot_ids), "filenames": list(self.filenames)}

    def describe(self):
        """"The phone is capturing Mk9", for busy messages."""
        who = "The editor" if self.source == "editor" else "The phone"
        what = {"calibrate": "is calibrating", "capture": "is capturing", "extra": "is taking an extra shot"}.get(
            self.kind, "is moving the table")
        into = (" " + self.target["name"]) if self.target["kind"] == "session" else \
            " (shots for the editor)" if self.target["kind"] == "shots" else ""
        return "%s %s%s" % (who, what, into)

    def check(self):
        if self.abort_flag.is_set():
            raise Aborted()

    def sleep(self, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            self.check()
            time.sleep(min(0.05, max(0, end - time.monotonic())))

    def step(self, n, of, text):
        self.progress = {"step": n, "of": of, "text": text}
        self.phone.push_job(self)

    def say(self, text):
        self.progress = dict(self.progress, text=text)
        self.phone.push_job(self)

    def ask(self, kind, text, answers, **extra):
        self.phone.prompt_n += 1
        prompt = dict({"id": "p-%d" % self.phone.prompt_n, "kind": kind, "text": text, "answers": answers}, **extra)
        with self.phone.lock:
            self.answer_event.clear()
            self.answer = None
            self.prompt = prompt
            self.state = "waiting"
        self.phone.push_job(self)
        print("  prompt %s (%s): %s  answers=%s" % (prompt["id"], kind, text, answers))
        while not self.answer_event.wait(0.1):
            self.check()
        return self.answer


# ---------------------------------------------------------------------------
# The phone
# ---------------------------------------------------------------------------

class Phone:
    def __init__(self, args):
        self.args = args
        self.lock = threading.RLock()
        self.device_id = hashlib.sha1(("mock-" + socket.gethostname()).encode()).hexdigest()[:16]
        self.name = args.name
        self.tokens = {}
        if args.token:
            self.tokens[args.token] = {"clientName": "preset", "pairedAt": now_iso()}
        self.fixed_code = args.code
        self.code = None
        self.code_expires = 0
        self.bad_codes = 0
        self.locked_until = 0
        self.table = Table(args.deg_per_sec, random.uniform(0, 360))
        self.ring_blocked = args.ring_blocked
        self.calibration = None
        self.front = None  # ring angle set with Set front, or None
        if args.calibrated:
            self.calibration = self._make_calibration(self.table.angle())
        self.camera = {"lens": "2", "zoom": 1.0, "exposureMode": "auto", "ev": 0, "iso": 200,
                       "shutterNs": 16_666_666, "focusMode": "auto", "focusDiopters": 0.0,
                       "whiteBalance": "auto", "kelvin": 5000, "aeLock": False, "awbLock": False, "afLock": False,
                       "focusPoint": None}
        self.shots = {}
        self.shot_n = 0
        self.set_order = []
        # Sessions captured on the phone on its own (Remote-API.md, Sessions), by ID.
        self.sessions = {}
        # The shared capture target (Remote-API.md, Capture target).
        self.target = {"kind": "none", "sessionId": None, "name": None, "setBy": None, "editorJoined": False}
        # Whether the phone is on its Capture screen (the camera endpoints need it).
        self.camera_open = not args.camera_closed
        self.jobs = {}
        self.job = None
        self.last_job = None
        self.prompt_n = 0
        self.move_n = 0
        self.fail_moves = set(args.fail_move or [])
        self.offline_until = 0.0  # /mock/offline: as if the phone dropped off Wi-Fi
        self.clients = []
        self.ffmpeg = shutil.which("ffmpeg")
        for i in range(args.sessions or 0):
            s = self.mock_session("Mock badge %d" % (i + 1))
            if i == args.sessions - 1:
                self.set_target({"kind": "session", "sessionId": s["id"], "name": s["name"]}, "phone")
        self.rotate_code()
        for target in (self._status_loop, self._ring_loop, self._preview_loop, self._code_loop):
            threading.Thread(target=target, daemon=True).start()

    # -- pairing ------------------------------------------------------------

    def rotate_code(self):
        self.code = self.fixed_code or "%06d" % secrets.randbelow(1_000_000)
        self.code_expires = time.monotonic() + 120
        print("\n  Pairing code: %s %s  (as shown on the phone's Capture screen)\n" % (self.code[:3], self.code[3:]))

    def _code_loop(self):
        while True:
            time.sleep(1)
            if not self.fixed_code and time.monotonic() > self.code_expires:
                self.rotate_code()
                self.broadcast({"type": "notice", "level": "info", "text": "The pairing code on the phone changed."})

    def pair(self, body):
        code = str(body.get("code", "")).strip().replace(" ", "")
        if not re.fullmatch(r"\d{6}", code):
            raise ApiError(400, "bad_request", "The pairing code is 6 digits.", field="code")
        now = time.monotonic()
        if now < self.locked_until:
            raise ApiError(429, "pairing_locked", "Too many wrong codes. Try again in a minute.",
                           retryAfterMs=int((self.locked_until - now) * 1000))
        if code != self.code:
            self.bad_codes += 1
            if self.bad_codes >= 5:
                self.bad_codes = 0
                self.locked_until = now + 60
                raise ApiError(429, "pairing_locked", "Too many wrong codes. Try again in a minute.", retryAfterMs=60000)
            raise ApiError(401, "bad_code", "That code doesn't match the one on the phone.", attemptsLeft=5 - self.bad_codes)
        self.bad_codes = 0
        token = secrets.token_hex(16)
        name = str(body.get("clientName") or "Unnamed PC")[:64]
        self.tokens[token] = {"clientName": name, "pairedAt": now_iso()}
        print("  Paired with %r" % name)
        if not self.fixed_code:
            self.rotate_code()
        return {"token": token, "deviceId": self.device_id, "name": self.name}

    # -- readings -----------------------------------------------------------

    def offline(self):
        return time.monotonic() < self.offline_until

    def ring_ok(self):
        return not self.ring_blocked

    def zero(self):
        return self.calibration["zeroAngleDeg"] if self.calibration else 0.0

    def table_deg(self, raw=None):
        if not self.calibration or not self.ring_ok():
            return None
        return round(fwd(self.calibration["zeroAngleDeg"], self.table.angle() if raw is None else raw), 2)

    def from_front(self, raw):
        """How far a ring angle is from the front, going forward, in (-180, 180]; None without a front."""
        if self.front is None:
            return None
        d = fwd(self.front, raw)
        return round(d - 360 if d > 180 else d, 2)

    def ring_json(self):
        raw = self.table.angle() + random.uniform(-0.08, 0.08)
        ok = self.ring_ok()
        return {"ok": ok, "ringDeg": round(raw % 360, 2) if ok else None, "tableDeg": self.table_deg(raw) if ok else None,
                "frontDeg": self.from_front(raw) if ok else None, "markers": 11 if ok else 0, "rmsPx": round(random.uniform(0.5, 1.1), 2) if ok else None,
                "frameUs": frame_us()}

    def _make_calibration(self, zero):
        return {"calibratedAt": now_iso(), "degPerSec": round(self.args.deg_per_sec + random.uniform(-0.4, 0.4), 2),
                "stopOvershootDeg": 3.0, "startLatencyMs": 180, "step90Deg": round(90 + random.uniform(-0.4, 0.6), 2),
                "directionSign": 1, "markerCount": MARKERS, "zeroAngleDeg": round(zero, 2)}

    def calibration_json(self):
        c = self.calibration
        out = {"state": "valid" if c else "missing", "reason": None}
        for k in ("calibratedAt", "degPerSec", "stopOvershootDeg", "startLatencyMs", "step90Deg", "directionSign", "markerCount"):
            out[k] = c[k] if c else None
        return out

    def status(self):
        with self.lock:
            job = self.job.to_json() if self.job else None
            shots = list(self.shots.values())
            sessions = list(self.sessions.values())
        try:
            free = shutil.disk_usage(HERE).free
        except OSError:
            free = None
        closed_ring = {"ok": False, "ringDeg": None, "tableDeg": None, "frontDeg": None, "markers": 0,
                       "rmsPx": None, "frameUs": None}
        return {
            "clients": [{"clientName": c.client_name, "since": c.since} for c in self.clients if not c.dead],
            "cameraOpen": self.camera_open,
            "target": dict(self.target),
            "sessions": {"count": len(sessions), "bytes": sum(session_bytes(s) for s in sessions)},
            "turntable": {"profileId": "jayegt-5.74", "profileName": "JAYEGT 5.74 in", "irAvailable": True,
                          "roles": list(JAYEGT_ROLES),
                          "frontRingDeg": None if self.front is None else round(self.front, 2)},
            "calibration": self.calibration_json(),
            "ring": self.ring_json() if self.camera_open else closed_ring,
            "camera": dict(self.camera) if self.camera_open else None,
            "job": job,
            "shots": {"count": len(shots), "bytes": sum(s["web"]["bytes"] for s in shots)},
            "storage": {"freeBytes": free},
        }

    def info(self, token):
        return {"api": API_VERSION, "app": "Badge Turntable", "appVersion": APP_VERSION, "deviceId": self.device_id,
                "name": self.name, "model": "Mock phone (%s)" % socket.gethostname(), "port": self.args.port,
                "addresses": local_addresses(), "paired": None if token is None else token in self.tokens,
                "busy": self.job is not None}

    # -- broadcasting -------------------------------------------------------

    def broadcast(self, obj):
        for c in list(self.clients):
            if not c.dead:
                c.send_json(obj)

    def push_job(self, job):
        self.broadcast({"type": "job", "job": job.to_json()})

    def push_status(self):
        self.broadcast(dict({"type": "status"}, **self.status()))

    def _status_loop(self):
        while True:
            time.sleep(0.5)
            if self.clients:
                self.push_status()

    def _ring_loop(self):
        while True:
            time.sleep(0.2)
            if not self.clients or not self.camera_open:
                continue
            _, markers, axis = render_scene(160, 120, self.table.angle(), self.zero(), cam=self.camera,
                                            ring_visible=self.ring_ok())
            r = self.ring_json()
            self.broadcast({"type": "ring", "frameUs": r["frameUs"], "ok": r["ok"], "ringDeg": r["ringDeg"],
                            "tableDeg": r["tableDeg"], "frontDeg": r["frontDeg"], "markers": markers, "rmsPx": r["rmsPx"], "axis": axis})

    def _preview_loop(self):
        while True:
            time.sleep(1 / 30)
            now = time.monotonic()
            due = [c for c in list(self.clients) if not c.dead and c.preview["enabled"] and now >= c.next_frame_at
                   and self.camera_open]
            if not due:
                continue
            cache = {}
            for c in due:
                c.next_frame_at = now + 1.0 / c.preview["fps"]
                width = c.preview["width"]
                if width not in cache:
                    img, _, _ = render_scene(width, width * 3 // 4, self.table.angle(), self.zero(), cam=self.camera,
                                             ring_visible=self.ring_ok())
                    rot = self.args.rotate % 4
                    if rot:
                        img = img.rotate(90 * rot, expand=True)  # counter-clockwise; the editor turns it back
                    head = b"BTPV" + struct.pack(">BBHq", 1, rot, 0, frame_us())
                    cache[width] = head + jpeg(img, 70)
                c.offer_frame(cache[width])

    # -- jobs ---------------------------------------------------------------

    def busy(self):
        """409 busy naming who is running what (call holding the lock, with a job running)."""
        j = self.job
        return ApiError(409, "busy", "%s. Wait for it, or stop it." % j.describe(), jobId=j.id, source=j.source,
                        target=dict(j.target))

    def start_job(self, kind, params, fn, target=None, source="editor"):
        with self.lock:
            if self.job:
                raise self.busy()
            job = Job(self, kind, params, source, target)
            self.job = job
            self.jobs[job.id] = job
        print("Job %s %s %s" % (job.id, kind, json.dumps(params)))
        threading.Thread(target=self._run_job, args=(job, fn), daemon=True).start()
        return job

    def _run_job(self, job, fn):
        self.push_job(job)
        try:
            job.result = fn(job)
            job.state = "done"
        except Aborted:
            job.say("Stopping…")
            self.table.stop()
            if job.kind == "capture" and job.moved and job.params.get("auto", True):
                try:
                    self.home(None)
                except Exception:
                    pass
            job.state = "aborted"
        except MoveFailed as e:
            job.state, job.error = "failed", {"error": "move_failed", "message": e.message}
        except ApiError as e:
            job.state, job.error = "failed", {"error": e.code, "message": e.message}
        except Exception as e:  # noqa: BLE001 - report anything
            job.state, job.error = "failed", {"error": "internal", "message": str(e)}
            import traceback
            traceback.print_exc()
        job.prompt = None
        job.ended_at = now_iso()
        if job.state != "done":
            job.progress = dict(job.progress, text={"failed": "Failed", "aborted": "Stopped"}[job.state])
        else:
            job.progress = dict(job.progress, text="Done")
        with self.lock:
            self.job = None
            self.last_job = job
        print("Job %s %s%s" % (job.id, job.state, (": " + job.error["message"]) if job.error else ""))
        self.push_job(job)
        self.push_status()

    def answer(self, body, by="editor"):
        with self.lock:
            job = self.job
            if not job:
                raise ApiError(409, "no_job", "Nothing is running.")
            p = job.prompt
            if not p or body.get("promptId") != p["id"]:
                raise ApiError(409, "prompt_gone", "That question was already answered.")
            ans = body.get("answer")
            if ans not in p["answers"]:
                raise ApiError(400, "bad_request", "Answer must be one of %s." % ", ".join(p["answers"]), field="answer")
            job.answer = ans
            if ans == "stop" and not job.stopped_by:
                job.stopped_by = by
            job.prompt = None
            job.state = "running"
            job.answer_event.set()
        self.push_job(job)
        return job

    def abort(self, by="editor"):
        with self.lock:
            job = self.job
            if not job:
                raise ApiError(409, "no_job", "Nothing is running.")
            if not job.stopped_by:
                job.stopped_by = by
            job.abort_flag.set()
        return job

    # -- capture target and sessions --------------------------------------

    def set_target(self, t, by):
        """Changes the shared capture target (refused while a job runs) and tells every tab."""
        with self.lock:
            if self.job:
                raise self.busy()
            prev = dict(self.target)
            kind = t["kind"]
            self.target = {"kind": kind, "sessionId": t.get("sessionId"), "name": t.get("name"),
                           "setBy": by if kind != "none" else None,
                           "editorJoined": by == "editor" and kind != "none"}
        print("  target %s %s (by %s)" % (kind, t.get("name") or "", by))
        self.broadcast({"type": "target", "target": dict(self.target), "previous": prev, "by": by})
        self.push_status()
        return dict(self.target)

    def target_for(self, source):
        """The job target for a capture from this side (Remote-API.md, Capture target)."""
        t = self.target
        if source == "editor":
            if t["kind"] == "none":
                t = self.set_target({"kind": "shots"}, "editor")
            elif t["kind"] == "session" and not t["editorJoined"]:
                raise ApiError(409, "session_open", "The phone has %s open. Join it, or start a new set, first." % t["name"],
                               sessionId=t["sessionId"])
        return {"kind": t["kind"], "sessionId": t["sessionId"], "name": t["name"]}

    def require_camera(self):
        if not self.camera_open:
            raise ApiError(503, "camera_unavailable", "Open the Camera screen on the phone.")

    def open_camera(self):
        if not self.camera_open:
            time.sleep(0.8)  # the screen opens and the camera binds
            self.camera_open = True
            print("  Camera screen opened (by the editor)")
            self.push_status()
        return {"cameraOpen": True}

    def mock_session(self, name, notes="Made by the mock phone."):
        """A session as if captured on the phone on its own: the four views and a lanyard shot."""
        sid = secrets.token_hex(16)
        s = {"id": sid, "name": name, "notes": notes, "createdAt": now_iso(), "updatedAt": now_iso(),
             "media": []}
        with self.lock:
            self.sessions[sid] = s
        for nominal, view, caption in VIEWS:
            self.session_still(s, view + ".jpg", caption, "%s · %s" % (name, caption), "view", view=view,
                               nominal=nominal, actual=nominal + round(random.uniform(-0.6, 0.6), 2))
        self.session_still(s, "lanyard.jpg", "Lanyard", name + " · Lanyard", "extra", label="Lanyard")
        return s

    def session_still(self, s, filename, caption, label_text, kind, **kw):
        img, _, _ = render_scene(1024, 768, random.uniform(0, 360), 0, label=label_text, cam=self.camera)
        web = jpeg(img.resize((2400, 1800), Image.LANCZOS), 85)
        m = {"filename": filename, "kind": kind, "setLabel": kw.get("set_label"), "view": kw.get("view"),
             "label": kw.get("label"), "caption": caption, "nominalDeg": kw.get("nominal"), "actualDeg": kw.get("actual"),
             "takenAt": now_iso(), "ready": True, "original": None, "durationMs": None,
             "web": {"mime": "image/jpeg", "bytes": len(web), "width": 2400, "height": 1800, "filename": filename},
             "_data": {"web": web, "thumb": jpeg(img.resize((320, 240), Image.LANCZOS), 75)}, "_t": time.monotonic()}
        self.session_put(s, m)

    def session_put(self, s, m):
        """Adds a file to a session, replacing one with the same name, and tells every tab."""
        with self.lock:
            s["media"] = [x for x in s["media"] if x["filename"] != m["filename"]] + [m]
            s["updatedAt"] = now_iso()
        self.broadcast({"type": "session", "session": self.session_summary(s)})

    def session_summary(self, s):
        job = self.job
        media = [m for m in s["media"] if m["ready"]]
        cover = next((m["filename"] for m in media if m["filename"] == "front.jpg"), None) or \
            next((m["filename"] for m in media if m["kind"] != "spin"), None)
        return {"id": s["id"], "name": s["name"], "createdAt": s["createdAt"], "updatedAt": s["updatedAt"],
                "open": self.target["kind"] == "session" and self.target["sessionId"] == s["id"],
                "busy": bool(job and job.target.get("sessionId") == s["id"]),
                "hasNotes": bool(s["notes"].strip()), "mediaCount": len(media),
                "bytes": session_bytes(s), "cover": cover}

    def session_detail(self, s):
        out = self.session_summary(s)
        out["notes"] = s["notes"]
        media = [m for m in s["media"] if m["ready"]]
        out["media"] = [public_shot(m) for m in sorted(media, key=lambda m: media_order_key(m, media))]
        return out

    def get_session(self, sid):
        s = self.sessions.get(sid)
        if not s:
            raise ApiError(404, "not_found", "No such session.")
        return s

    def check_not_writing(self, sid):
        if self.job and self.job.target.get("sessionId") == sid:
            raise self.busy()

    def delete_session(self, sid):
        with self.lock:
            s = self.get_session(sid)
            self.check_not_writing(sid)
            if self.target["kind"] == "session" and self.target["sessionId"] == sid:
                raise ApiError(409, "session_open", "%s is open on the phone. Close it there first." % s["name"],
                               sessionId=sid)
            del self.sessions[sid]
        self.broadcast({"type": "session_removed", "id": sid})

    def delete_session_file(self, sid, filename):
        with self.lock:
            s = self.get_session(sid)
            if not any(m["filename"] == filename for m in s["media"]):
                raise ApiError(404, "not_found", "That session has no such file.")
            self.check_not_writing(sid)
            s["media"] = [m for m in s["media"] if m["filename"] != filename]
            s["updatedAt"] = now_iso()
        self.broadcast({"type": "session", "session": self.session_summary(s)})

    # -- motion -------------------------------------------------------------

    def wait_still(self, job):
        while self.table.moving():
            if job:
                job.check()
            time.sleep(0.03)
        time.sleep(0.3)

    def closed_move(self, job, by):
        self.move_n += 1
        if job:
            job.moved = True
        if self.move_n in self.fail_moves:
            self.fail_moves.discard(self.move_n)
            if job:
                job.sleep(1.5)
            raise MoveFailed("no_movement", "The table didn't move. Check the IR path, then retry.")
        if not self.ring_ok():
            raise MoveFailed("ring_lost", "Lost sight of the ring. Is the badge blocking it?")
        by = by + random.uniform(-0.6, 0.6)
        self.table.step(by)
        self.wait_still(job)

    def set_front(self):
        """Set front: the table's position now faces the camera. With a calibration it's also
        the zero, and the table's own origin is set there."""
        if self.job:
            raise ApiError(409, "busy", "%s is running." % self.job.kind.capitalize(), jobId=self.job.id)
        if not self.ring_ok():
            raise ApiError(409, "ring_lost", "Can't see the encoder ring, so the front can't be set.")
        self.front = self.table.angle() % 360
        if self.calibration:
            self.calibration["zeroAngleDeg"] = round(self.front, 2)
            self.table.press("set_origin")
        return round(self.front, 2)

    def home(self, job):
        if self.calibration:
            dist = fwd(self.table.angle(), self.calibration["zeroAngleDeg"])
            if 0.5 < dist < 359.5:
                self.table.step(dist)
                self.wait_still(job)
            self.table.press("set_origin")
        else:
            self.table.press("return_to_origin")
            self.wait_still(job)

    def job_move(self, job):
        p = job.params
        if p.get("home"):
            job.step(None, None, "Homing…")
            self.home(job)
        else:
            if p.get("to") is not None:
                if self.table_deg() is None:
                    raise ApiError(409, "not_calibrated", "Calibrate first.")
                by = fwd(self.table_deg(), float(p["to"]))
                job.step(None, None, "Moving to %g°…" % float(p["to"]))
            else:
                by = float(p["by"])
                job.step(None, None, "Moving %+g°…" % by)
            while True:
                try:
                    self.closed_move(job, by)
                    break
                except MoveFailed as e:
                    target = self.table_deg(self.table.angle() + by)
                    if job.ask("move_failed", e.message, ["retry", "stop"], view=None, targetDeg=None,
                               targetTableDeg=target, toleranceDeg=2.0, reason=e.reason) == "stop":
                        raise Aborted()
        return {"tableDeg": self.table_deg(), "ringDeg": round(self.table.angle(), 2) if self.ring_ok() else None}

    def job_calibrate(self, job):
        of = 5
        job.step(1, of, "Checking the ring…")
        job.sleep(0.8)
        if not self.ring_ok():
            raise ApiError(409, "ring_lost", "Can't see the encoder ring. Clear the view of the rim and try again.")
        job.step(2, of, "Measuring speed and direction…")
        job.moved = True
        start = self.table.angle()
        self.table.spin(1)
        job.sleep(360 / self.args.deg_per_sec)
        job.step(3, of, "Measuring how far the table coasts…")
        self.table.stop()
        self.wait_still(job)
        job.step(4, of, "Measuring the 90° step…")
        self.table.press("step_90")
        self.wait_still(job)
        job.step(5, of, "Setting the origin…")
        self.table.step(fwd(self.table.angle(), start))
        self.wait_still(job)
        self.table.press("set_origin")
        self.calibration = self._make_calibration(start)
        return self.calibration_json()

    def countdown(self, job, ms):
        remaining = ms
        while remaining > 0:
            self.broadcast({"type": "countdown", "remainingMs": remaining})
            job.say("Capturing in %d…" % math.ceil(remaining / 1000))
            job.sleep(min(1.0, remaining / 1000))
            remaining -= 1000
        self.broadcast({"type": "countdown", "remainingMs": 0})

    def job_capture(self, job):
        p = job.params
        auto, video, set_label = p["auto"], p["video"], p["setLabel"]
        stills = p.get("stills", True)
        prefix = kebab(set_label) + "-" if set_label else ""
        of = (4 if stills else 0) + (1 if video else 0) + (1 if auto else 0)
        if self.camera.get("focusPoint"):
            # The sequence focuses on the centre itself, which replaces a clicked point.
            self.camera["focusPoint"] = None
            self.broadcast({"type": "camera", "settings": dict(self.camera)})
        base = self.table.angle()
        manual = not auto
        complete = True
        if self.front is not None:
            # A front was set: start from there, turning to it first if needed.
            off = fwd(self.front, base)
            if min(off, 360 - off) > 2.0:
                while not manual:
                    job.step(1, of, "Turning to the front…")
                    try:
                        self.closed_move(job, fwd(self.table.angle(), self.front))
                        break
                    except MoveFailed as e:
                        ans = job.ask("move_failed", e.message, ["retry", "manual", "stop"], view=prefix + "front",
                                      targetDeg=0, targetTableDeg=self.table_deg(self.front),
                                      toleranceDeg=2.0, reason=e.reason)
                        if ans == "manual":
                            manual = True
                        elif ans == "stop":
                            raise Aborted()
                if manual:
                    if job.ask("position", "Turn the table to the front", ["ready", "stop"], view=prefix + "front",
                               targetDeg=0, targetTableDeg=self.table_deg(self.front), toleranceDeg=2.0) == "stop":
                        raise Aborted()
                    self.table.set_to(self.front + random.uniform(-1.5, 1.5))  # the user turned it
                    job.moved = True
            base = self.front
        for i, (nominal, view, caption) in enumerate(VIEWS if stills else []):
            job.step(i + 1, of, "Capturing %s…" % caption if i == 0
                     else ("Turn the table to %d°" % nominal if manual else "Moving to %d°…" % nominal))
            if i > 0:
                while not manual:
                    try:
                        self.closed_move(job, fwd(self.table.angle(), (base + nominal) % 360))
                        break
                    except MoveFailed as e:
                        ans = job.ask("move_failed", e.message, ["retry", "manual", "stop"], view=prefix + view,
                                      targetDeg=nominal, targetTableDeg=self.table_deg(base + nominal),
                                      toleranceDeg=2.0, reason=e.reason)
                        if ans == "manual":
                            manual = True
                        elif ans == "stop":
                            complete = False
                            break
                if not complete:
                    break
                if manual:
                    ans = job.ask("position", "Turn the table to %d° (%s)" % (nominal, caption), ["ready", "stop"],
                                  view=prefix + view, targetDeg=nominal, targetTableDeg=self.table_deg(base + nominal),
                                  toleranceDeg=2.0)
                    if ans == "stop":
                        complete = False
                        break
                    self.table.set_to(base + nominal + random.uniform(-1.5, 1.5))  # the user turned it
                    job.moved = True
            if manual and i > 0:
                self.countdown(job, 2000)
            job.say("Capturing %s…" % caption)
            actual = round(fwd(base, self.table.angle()), 2) if self.ring_ok() else None
            if actual is not None and actual > 359:
                actual = round(actual - 360, 2)
            label = (set_label + " · " if set_label else "") + caption
            self.add_still(job, "view", prefix + view + ".jpg", caption if not set_label else
                           "%s (%s)" % (set_label, caption[0].lower() + caption[1:]), label,
                           set_label=set_label, view=view, nominal=nominal, actual=actual)
        if complete and video:
            complete = self.record_spin(job, of, manual, prefix, set_label)
        if auto:
            job.step(of, of, "Returning to zero…")
            self.home(job)
        return {"complete": complete, "shotIds": list(job.shot_ids)}

    def record_spin(self, job, of, manual, prefix, set_label):
        """The spin, following Spec.md "Seamless spin loop": steady speed first, then
        a lead-in, 360 degrees and the overlap, then cut one revolution."""
        job.step((4 if job.params.get("stills", True) else 0) + 1, of, "Recording the spin…")
        total = 360 + 15
        if manual:
            ans = job.ask("position", "Start the table turning with its remote (continuous rotation), "
                          "then press Ready once it's turning steadily.",
                          ["ready", "stop"], view=prefix + "spin", targetDeg=None, targetTableDeg=None, toleranceDeg=2.0)
            if ans == "stop":
                return False
            if not self.table.moving():
                self.table.spin(1)  # the user started it with the remote
        else:
            self.table.spin(1)
        job.moved = True
        if self.ring_ok():
            job.say("Waiting for the table to turn steadily…")
            job.sleep(0.6)
            job.sleep(0.5)  # lead-in
            start = time.monotonic()
            while True:
                done = (time.monotonic() - start) * self.args.deg_per_sec
                if done >= total:
                    break
                job.say("Recording the spin: %d° of %d°" % (done, total))
                job.sleep(min(0.5, (total - done) / self.args.deg_per_sec))
        else:
            ans = job.ask("spin_done", "Recording. Press Done once the table has gone a little more than once round, "
                          "past where it was when you pressed Ready.", ["done", "stop"], view=prefix + "spin")
            if ans == "stop":
                if manual:
                    self.broadcast({"type": "notice", "level": "info", "text": "Spin not kept. You can stop the table."})
                    job.sleep(0.5)  # the user reaches for the remote
                self.table.stop()
                self.wait_still(job)
                return False
        if manual:
            self.broadcast({"type": "notice", "level": "info", "text": "Spin recorded. You can stop the table."})
            job.sleep(0.5)  # the user reaches for the remote
        self.table.stop()
        self.wait_still(job)
        job.say("Encoding the spin…")
        self.add_spin(job, prefix, set_label)
        return True

    def job_extra(self, job):
        label = job.params["label"]
        job.step(1, 1, "Capturing %s…" % label)
        self.countdown(job, job.params["delayMs"])
        with self.lock:
            s = self.sessions.get(job.target.get("sessionId")) if job.target["kind"] == "session" else None
            taken = {m["filename"] for m in s["media"]} if s else {x["filename"] for x in self.shots.values()}
        base = kebab(label) or "extra"
        name = base + ".jpg"
        n = 2
        while name in taken:
            name = "%s-%d.jpg" % (base, n)
            n += 1
        self.add_still(job, "extra", name, label, label, label=label)
        return {"shotIds": list(job.shot_ids)}

    # -- shots --------------------------------------------------------------

    def _new_shot(self, job, kind, filename, caption, **kw):
        self.shot_n += 1
        shot = {"id": "s-%d" % self.shot_n, "kind": kind, "setLabel": kw.get("set_label"), "view": kw.get("view"),
                "label": kw.get("label"), "filename": filename, "caption": caption, "nominalDeg": kw.get("nominal"),
                "actualDeg": kw.get("actual"), "takenAt": now_iso(), "jobId": job.id, "ready": True,
                "web": None, "original": None, "durationMs": None, "_data": {}, "_t": time.monotonic()}
        set_label = kw.get("set_label")
        if set_label and set_label not in self.set_order:
            self.set_order.append(set_label)
        return shot

    def _store_shot(self, job, shot):
        if job.target["kind"] == "session":
            # Into the session instead: no shot ID, the job lists the file name.
            s = self.sessions.get(job.target["sessionId"])
            if s is None:
                raise ApiError(500, "internal", "The session was deleted.")
            for k in ("id", "jobId"):
                shot.pop(k, None)
            shot["_session"] = s["id"]
            if shot["ready"]:
                self.session_put(s, shot)
            else:
                with self.lock:  # a spin joins its session once it's encoded
                    s["media"] = [x for x in s["media"] if x["filename"] != shot["filename"]] + [shot]
            if shot["filename"] not in job.filenames:
                job.filenames.append(shot["filename"])
            self.push_job(job)
            return
        replaced = []
        with self.lock:
            if shot["kind"] != "extra":
                for s in list(self.shots.values()):
                    if s["filename"] == shot["filename"]:
                        del self.shots[s["id"]]
                        replaced.append(s["id"])
            self.shots[shot["id"]] = shot
            job.shot_ids.append(shot["id"])
        for sid in replaced:
            self.broadcast({"type": "shot_removed", "id": sid})
        self.broadcast({"type": "shot", "shot": public_shot(shot)})
        self.push_job(job)

    def add_still(self, job, kind, filename, caption, label_text, **kw):
        shot = self._new_shot(job, kind, filename, caption, **kw)
        img, _, _ = render_scene(2048, 1536, self.table.angle(), self.zero(), label=label_text, cam=self.camera,
                                 ring_visible=self.ring_ok())
        # Only the web copy is kept (Spec.md, Output): original stays null.
        web = img.resize((2400, 1800), Image.LANCZOS)
        thumb = img.resize((320, 240), Image.LANCZOS)
        shot["_data"] = {"web": jpeg(web, 85), "thumb": jpeg(thumb, 75)}
        shot["web"] = {"mime": "image/jpeg", "bytes": len(shot["_data"]["web"]), "width": 2400, "height": 1800, "filename": filename}
        self._store_shot(job, shot)

    def add_spin(self, job, prefix, set_label):
        filename = prefix + "spin.mp4"
        caption = "%s (spin)" % set_label if set_label else "Spin"
        shot = self._new_shot(job, "spin", filename, caption, set_label=set_label)
        img, _, _ = render_scene(320, 240, self.zero(), self.zero(), label="spin", cam=self.camera)
        shot["_data"] = {"thumb": jpeg(img, 75)}
        shot["ready"] = False
        shot["web"] = {"mime": "video/mp4", "bytes": 0, "width": 640, "height": 480, "filename": filename}
        shot["durationMs"] = 3000
        self._store_shot(job, shot)
        threading.Thread(target=self._encode_spin, args=(shot,), daemon=True).start()

    def _encode_spin(self, shot):
        data = None
        if self.ffmpeg:
            frames = 72
            cmd = [self.ffmpeg, "-loglevel", "error", "-y", "-f", "image2pipe", "-framerate", "24", "-i", "-",
                   "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-f", "mp4", "pipe:1"]
            tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".spin-%s.mp4" % shot["id"])
            cmd[-1] = tmp
            try:
                proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
                for i in range(frames):
                    a = self.zero() + i * 360 / frames
                    img, _, _ = render_scene(640, 480, a, self.zero(), label=shot["caption"])
                    proc.stdin.write(jpeg(img, 85))
                proc.stdin.close()
                proc.wait(60)
                with open(tmp, "rb") as f:
                    data = f.read()
            except Exception as e:  # noqa: BLE001
                print("  spin encode failed: %s" % e)
            finally:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
        else:
            time.sleep(1.5)
        if data is None:
            self.broadcast({"type": "notice", "level": "warn", "text": "Mock: no ffmpeg, so the spin video is empty."})
            data = b""
        sid = shot.get("_session")
        with self.lock:
            if sid is None and shot.get("id") not in self.shots:
                return
            if sid is not None and sid not in self.sessions:
                return
            shot["_data"]["web"] = data
            shot["web"]["bytes"] = len(data)
            shot["ready"] = True
        if sid is not None:
            self.session_put(self.sessions[sid], shot)
        else:
            self.broadcast({"type": "shot", "shot": public_shot(shot)})

    def ordered_shots(self):
        def key(s):
            view_i = {v[1]: i for i, v in enumerate(VIEWS)}.get(s["view"], 4)
            if s["kind"] == "extra":
                return (2, 0, 0, s["_t"])
            if s["setLabel"]:
                return (1, self.set_order.index(s["setLabel"]), view_i, s["_t"])
            return (0, 0, view_i, s["_t"])
        with self.lock:
            return sorted(self.shots.values(), key=key)

    def delete_shot(self, sid):
        with self.lock:
            s = self.shots.get(sid)
            if not s:
                raise ApiError(404, "not_found", "No such shot.")
            if self.job and sid in self.job.shot_ids:
                raise ApiError(409, "busy", "That shot belongs to the running job.", jobId=self.job.id)
            del self.shots[sid]
        self.broadcast({"type": "shot_removed", "id": sid})

    def delete_all(self):
        with self.lock:
            if self.job:
                raise ApiError(409, "busy", "Stop the running job first.", jobId=self.job.id)
            ids = list(self.shots)
            self.shots.clear()
            self.set_order.clear()
        for sid in ids:
            self.broadcast({"type": "shot_removed", "id": sid})
        return len(ids)

    def session_json(self):
        shots = self.ordered_shots()
        job_ids = []
        for s in shots:
            if s["jobId"] not in job_ids:
                job_ids.append(s["jobId"])
        jobs = []
        for jid in sorted(job_ids, key=lambda j: int(j.split("-")[1])):
            j = self.jobs.get(jid)
            if j:
                jobs.append({"id": j.id, "kind": j.kind, "params": j.params, "startedAt": j.started_at,
                             "endedAt": j.ended_at, "state": j.state, "camera": j.camera, "shotIds": j.shot_ids})
        return {"api": API_VERSION,
                "device": {"deviceId": self.device_id, "model": "Mock phone", "appVersion": APP_VERSION},
                "turntable": {"profileId": "jayegt-5.74", "markerCount": MARKERS},
                "calibration": self.calibration_json() if self.calibration else None,
                "jobs": jobs, "shots": [public_shot(s) for s in shots]}

    # -- camera -------------------------------------------------------------

    def capabilities(self):
        caps = LENS_CAPS[self.camera["lens"]]
        return {"lenses": LENSES, "zoom": {"min": 1.0, "max": 10.0}, "ev": {"min": -12, "max": 12, "stepEv": 1 / 6},
                "iso": {"min": caps["iso"][0], "max": caps["iso"][1]}, "shutterNs": {"min": 10_000, "max": 1_000_000_000},
                "focusDiopters": {"min": 0.0, "max": round(100 / next(l for l in LENSES if l["id"] == self.camera["lens"])["minFocusCm"], 2)},
                "whiteBalance": WB_MODES, "kelvin": {"min": 2000, "max": 10000}}

    def set_camera(self, body):
        with self.lock:
            if self.job and self.job.kind != "move":
                raise ApiError(409, "busy", "The camera is locked while %s runs." % self.job.kind, jobId=self.job.id)
        new = dict(self.camera)
        caps = self.capabilities()

        def rng(field, lo, hi, cast):
            if field in body:
                try:
                    v = cast(body[field])
                except (TypeError, ValueError):
                    raise ApiError(400, "bad_request", "%s isn't a number." % field, field=field)
                if not lo <= v <= hi:
                    raise ApiError(400, "bad_request", "%s must be between %s and %s." % (field, lo, hi), field=field)
                new[field] = v

        if "lens" in body:
            if body["lens"] not in [l["id"] for l in LENSES]:
                raise ApiError(422, "unsupported", "No such lens.", field="lens")
            new["lens"] = body["lens"]
        rng("zoom", caps["zoom"]["min"], caps["zoom"]["max"], float)
        rng("ev", caps["ev"]["min"], caps["ev"]["max"], int)
        rng("iso", caps["iso"]["min"], caps["iso"]["max"], int)
        rng("shutterNs", caps["shutterNs"]["min"], caps["shutterNs"]["max"], int)
        rng("focusDiopters", caps["focusDiopters"]["min"], caps["focusDiopters"]["max"], float)
        rng("kelvin", caps["kelvin"]["min"], caps["kelvin"]["max"], int)
        for field, allowed in (("exposureMode", ["auto", "manual"]), ("focusMode", ["auto", "manual"]),
                               ("whiteBalance", WB_MODES)):
            if field in body:
                if body[field] not in allowed:
                    raise ApiError(400, "bad_request", "%s must be one of %s." % (field, ", ".join(allowed)), field=field)
                new[field] = body[field]
        for field in ("aeLock", "awbLock", "afLock"):
            if field in body:
                if not isinstance(body[field], bool):
                    raise ApiError(400, "bad_request", "%s must be true or false." % field, field=field)
                new[field] = body[field]
        if new["focusMode"] == "manual" or new["afLock"] or new["lens"] != self.camera["lens"]:
            new["focusPoint"] = None  # a focus point only lasts while autofocus is free to use it
        if new["lens"] != self.camera["lens"]:
            time.sleep(0.8)  # rebinding
        self.camera = new
        self.broadcast({"type": "camera", "settings": dict(new)})
        return {"settings": dict(new)}


def public_shot(s):
    return {k: v for k, v in s.items() if not k.startswith("_")}


def session_bytes(s):
    return sum(m["web"]["bytes"] for m in s["media"] if m["ready"])


def media_order_key(m, media):
    """The standard order: main views, main spin, extra sets as first shot, then extra shots by time."""
    sets = []
    for x in sorted(media, key=lambda x: x["_t"]):
        if x["setLabel"] and x["setLabel"] not in sets:
            sets.append(x["setLabel"])
    view_i = {v[1]: i for i, v in enumerate(VIEWS)}.get(m["view"], 4)
    if m["kind"] == "extra":
        return (2, 0, 0, m["_t"])
    if m["setLabel"]:
        return (1, sets.index(m["setLabel"]), view_i, m["_t"])
    return (0, 0, view_i, m["_t"])


def local_addresses():
    out = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))
        out.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return out


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

PHONE = None


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            return  # browsers drop keep-alive connections all the time
        super().handle_error(request, client_address)


class ApiHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "BadgeTurntableMock/1"

    def log_message(self, fmt, *args):
        if PHONE.args.verbose:
            sys.stderr.write("  %s %s\n" % (self.address_string(), fmt % args))

    # -- plumbing -----------------------------------------------------------

    def cors(self):
        # Any page may call: the pairing code and token are what keep others out.
        origin = self.headers.get("Origin")
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Expose-Headers", "Content-Disposition, X-Shot-Filename")
            self.send_header("Access-Control-Max-Age", "600")

    def send_bytes(self, status, data, mime, extra=None, cors=True):
        self.send_response(status)
        if cors:
            self.cors()
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def send_json(self, status, obj, cors=True):
        if status == 204:
            self.send_response(204)
            if cors:
                self.cors()
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_bytes(status, json.dumps(obj).encode(), "application/json; charset=utf-8", cors=cors)

    def read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 16384:
            raise ApiError(400, "bad_request", "Request body too large.")
        raw = self.rfile.read(n) if n else b""
        if not raw.strip():
            return {}
        try:
            body = json.loads(raw)
        except ValueError:
            raise ApiError(400, "bad_request", "The request body isn't valid JSON.")
        if not isinstance(body, dict):
            raise ApiError(400, "bad_request", "The request body must be a JSON object.")
        return body

    def token(self):
        auth = self.headers.get("Authorization") or ""
        m = re.match(r"Bearer\s+(\S+)$", auth)
        return m.group(1) if m else None

    def require_auth(self):
        tok = self.token()
        if not tok or not any(secrets.compare_digest(tok, t) for t in list(PHONE.tokens)):
            raise ApiError(401, "unauthorized", "This browser isn't paired with the phone, or its pairing was revoked.")
        return tok

    # -- verbs --------------------------------------------------------------

    def dropped(self):
        """A request sent while the phone is off the network never arrives: hang
        until the drop ends (at most 30 s), then close without answering."""
        if not PHONE.offline():
            return False
        end = time.monotonic() + 30
        while PHONE.offline() and time.monotonic() < end:
            time.sleep(0.2)
        self.close_connection = True
        return True

    def do_OPTIONS(self):
        if self.dropped():
            return
        self.send_response(204)
        self.cors()
        if self.headers.get("Access-Control-Request-Private-Network") == "true":
            self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self.dispatch("GET")

    def do_HEAD(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_DELETE(self):
        self.dispatch("DELETE")

    def dispatch(self, method):
        url = urlparse(self.path)
        path, query = url.path.rstrip("/") or "/", parse_qs(url.query)
        if path.startswith("/mock"):
            return self.mock_control(path, query)
        if self.dropped():
            return
        try:
            if path == "/api/live":
                if method != "GET" or "websocket" not in (self.headers.get("Upgrade") or "").lower():
                    raise ApiError(400, "bad_request", "WebSocket upgrade expected.")
                return self.websocket()
            body = self.read_json() if method == "POST" else {}
            status, out = self.route(method, path, query, body)
            if isinstance(out, tuple):
                data, mime, extra = out
                return self.send_bytes(status, data, mime, extra)
            self.send_json(status, out)
        except ApiError as e:
            self.send_json(e.status, e.body())
        except (ConnectionError, BrokenPipeError):
            pass
        except Exception as e:  # noqa: BLE001
            import traceback
            traceback.print_exc()
            self.send_json(500, {"error": "internal", "message": str(e)})

    def route(self, method, path, query, body):
        P = PHONE
        if path == "/api/info" and method == "GET":
            return 200, P.info(self.token())
        if path == "/api/pair":
            if method == "POST":
                return 200, P.pair(body)
            if method == "DELETE":
                P.tokens.pop(self.require_auth(), None)
                return 204, None
            raise ApiError(405, "method_not_allowed", "Use POST or DELETE.")
        if not path.startswith("/api/"):
            raise ApiError(404, "not_found", "No such endpoint.")
        self.require_auth()

        if path == "/api/status" and method == "GET":
            return 200, P.status()
        # The camera, ring and turntable moves need the phone's Capture screen open.
        if path in ("/api/camera", "/api/camera/focus", "/api/turntable/move", "/api/turntable/front",
                    "/api/calibrate", "/api/capture", "/api/extra") and method in ("GET", "POST"):
            P.require_camera()
        if path == "/api/camera/open" and method == "POST":
            return 200, P.open_camera()
        if path == "/api/target":
            if method == "GET":
                return 200, {"target": dict(P.target)}
            if method == "POST":
                if body.get("session"):
                    s = P.get_session(str(body["session"]))
                    return 200, {"target": P.set_target({"kind": "session", "sessionId": s["id"], "name": s["name"]}, "editor")}
                if body.get("shots") is True:
                    return 200, {"target": P.set_target({"kind": "shots"}, "editor")}
                raise ApiError(400, "bad_request", "Give session (an ID to join) or shots: true (a new set).", field="session")
        if path == "/api/sessions" and method == "GET":
            with P.lock:
                sessions = sorted(P.sessions.values(), key=lambda s: s["createdAt"], reverse=True)
            return 200, {"sessions": [P.session_summary(s) for s in sessions]}
        sm = re.fullmatch(r"/api/sessions/([^/]+)(?:/media/([^/]+))?", path)
        if sm:
            s = P.get_session(sm.group(1))
            filename = unquote(sm.group(2)) if sm.group(2) else None
            if filename is None:
                if method == "GET":
                    return 200, P.session_detail(s)
                if method == "DELETE":
                    P.delete_session(s["id"])
                    return 204, None
            else:
                if method == "DELETE":
                    P.delete_session_file(s["id"], filename)
                    return 204, None
                if method == "GET":
                    m = next((x for x in s["media"] if x["filename"] == filename and x["ready"]), None)
                    if not m:
                        raise ApiError(404, "not_found", "That session has no such file.")
                    variant = (query.get("variant") or ["web"])[0]
                    if variant == "original":
                        raise ApiError(404, "not_found", "The phone keeps only the web copy.")
                    if variant not in ("web", "thumb"):
                        raise ApiError(400, "bad_request", "variant is web or thumb.", field="variant")
                    if variant == "thumb":
                        data = m["_data"].get("thumb", b"")
                        mime, name = "image/jpeg", filename.rsplit(".", 1)[0] + "-thumb.jpg"
                    else:
                        data, mime, name = m["_data"].get("web", b""), m["web"]["mime"], filename
                    return 200, (data, mime, {"Content-Disposition": 'attachment; filename="%s"' % name,
                                              "X-Shot-Filename": name})
            raise ApiError(405, "method_not_allowed", "%s isn't allowed here." % method)
        if path == "/api/camera":
            if method == "GET":
                return 200, {"capabilities": P.capabilities(), "settings": dict(P.camera)}
            if method == "POST":
                return 200, P.set_camera(body)
        if path == "/api/camera/focus" and method == "POST":
            if P.job and P.job.kind != "move":
                raise P.busy()
            if body.get("clear") is True:
                point = None
            else:
                for f in ("x", "y"):
                    v = body.get(f)
                    if not isinstance(v, (int, float)) or not 0 <= v <= 1:
                        raise ApiError(400, "bad_request", "x and y must be between 0 and 1.", field=f)
                point = {"x": round(body["x"], 3), "y": round(body["y"], 3)}
            out = P.set_camera({"afLock": False, "focusMode": "auto"})
            P.camera["focusPoint"] = point
            out = {"settings": dict(P.camera)}
            P.broadcast({"type": "camera", "settings": dict(P.camera)})
            print("  focus " + ("back to continuous" if point is None else "at %.3f, %.3f" % (point["x"], point["y"])))
            return 200, out
        if path == "/api/turntable/press" and method == "POST":
            role = body.get("role")
            if role not in ALL_ROLES:
                raise ApiError(400, "bad_request", "Unknown role %r." % role, field="role")
            if role not in JAYEGT_ROLES:
                raise ApiError(422, "unsupported", "This turntable's remote has no %s button." % role, field="role")
            if P.job:
                raise P.busy()
            P.table.press(role)
            return 200, {"role": role}
        if path == "/api/turntable/move" and method == "POST":
            if body.get("home") is True:
                params = {"home": True}
            elif "to" in body:
                params = {"to": self.num(body, "to", 0, 360)}
                if not P.calibration:
                    raise ApiError(409, "not_calibrated", "Calibrate the turntable first.")
            elif "by" in body:
                params = {"by": self.num(body, "by", -360, 360)}
                if not P.calibration:
                    raise ApiError(409, "not_calibrated", "Calibrate the turntable first.")
            else:
                raise ApiError(400, "bad_request", "Give by, to or home.")
            return 202, {"job": P.start_job("move", params, P.job_move).to_json()}
        if path == "/api/turntable/front" and method == "POST":
            return 200, {"frontRingDeg": P.set_front()}
        if path == "/api/calibrate" and method == "POST":
            return 202, {"job": P.start_job("calibrate", {}, P.job_calibrate).to_json()}
        if path == "/api/capture" and method == "POST":
            params = {"stills": body.get("stills", True) is not False, "video": body.get("video", True) is not False,
                      "auto": body.get("auto", True) is not False,
                      "setLabel": (str(body.get("setLabel") or "").strip() or None)}
            if not params["stills"] and not params["video"]:
                raise ApiError(400, "bad_request", "Ask for the stills, the spin or both.", field="stills")
            if P.job:
                raise P.busy()
            if params["auto"] and not P.calibration:
                raise ApiError(409, "not_calibrated", "Calibrate the turntable before an auto capture, or use manual mode.")
            target = P.target_for("editor")
            return 202, {"job": P.start_job("capture", params, P.job_capture, target).to_json()}
        if path == "/api/extra" and method == "POST":
            label = str(body.get("label") or "").strip()
            if not 1 <= len(label) <= 60:
                raise ApiError(400, "bad_request", "Give the extra shot a label (up to 60 characters).", field="label")
            delay = body.get("delayMs", 2000)
            if not isinstance(delay, int) or not 0 <= delay <= 10000:
                raise ApiError(400, "bad_request", "delayMs must be 0 to 10000.", field="delayMs")
            if P.job:
                raise P.busy()
            target = P.target_for("editor")
            return 202, {"job": P.start_job("extra", {"label": label, "delayMs": delay}, P.job_extra, target).to_json()}
        if path == "/api/jobs/current":
            if method == "GET":
                j = P.job or P.last_job
                return 200, {"job": j.to_json() if j else None}
            if method == "DELETE":
                return 200, {"job": P.abort("editor").to_json()}
        if path == "/api/jobs/current/answer" and method == "POST":
            return 200, {"job": P.answer(body, "editor").to_json()}
        if path == "/api/shots":
            if method == "GET":
                return 200, {"shots": [public_shot(s) for s in P.ordered_shots()]}
            if method == "DELETE":
                return 200, {"deleted": P.delete_all()}
        m = re.fullmatch(r"/api/shots/([^/]+)", path)
        if m:
            sid = m.group(1)
            if method == "DELETE":
                P.delete_shot(sid)
                return 204, None
            if method == "GET":
                s = P.shots.get(sid)
                if not s:
                    raise ApiError(404, "not_found", "No such shot.")
                variant = (query.get("variant") or ["web"])[0]
                if variant == "original":
                    raise ApiError(404, "not_found", "The phone keeps only the web copy.")
                if variant not in ("web", "thumb"):
                    raise ApiError(400, "bad_request", "variant is web or thumb.", field="variant")
                if variant != "thumb" and not s["ready"]:
                    raise ApiError(409, "not_ready", "The spin video is still being encoded.")
                data = s["_data"].get(variant, b"")
                if variant == "thumb":
                    mime, name = "image/jpeg", s["filename"].rsplit(".", 1)[0] + "-thumb.jpg"
                else:
                    mime, name = s["web"]["mime"], s["web"]["filename"]
                return 200, (data, mime, {"Content-Disposition": 'attachment; filename="%s"' % name,
                                          "X-Shot-Filename": name})
        if path == "/api/session.json" and method == "GET":
            return 200, P.session_json()
        known = {"/api/status", "/api/camera", "/api/camera/focus", "/api/camera/open", "/api/turntable/press", "/api/turntable/move",
                 "/api/turntable/front", "/api/calibrate", "/api/capture", "/api/extra", "/api/jobs/current", "/api/jobs/current/answer",
                 "/api/shots", "/api/session.json", "/api/target", "/api/sessions"}
        if path in known or m:
            raise ApiError(405, "method_not_allowed", "%s isn't allowed here." % method)
        raise ApiError(404, "not_found", "No such endpoint.")

    @staticmethod
    def num(body, field, lo, hi):
        v = body.get(field)
        if not isinstance(v, (int, float)) or isinstance(v, bool) or not lo <= v <= hi:
            raise ApiError(400, "bad_request", "%s must be a number from %s to %s." % (field, lo, hi), field=field)
        return v

    # -- websocket ----------------------------------------------------------

    def websocket(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            raise ApiError(400, "bad_request", "Missing Sec-WebSocket-Key.")
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        sock = self.connection
        client = None
        try:
            sock.settimeout(5)
            try:
                op, data = ws_read_frame(self.rfile)
                msg = json.loads(data) if op == 1 else {}
            except (socket.timeout, TimeoutError):
                self.wfile.write(ws_frame(8, struct.pack(">H", 4408) + b"auth timeout"))
                return
            except (ValueError, WsClosed):
                msg = {}
            tok = msg.get("token") if msg.get("type") == "auth" else None
            if not tok or tok not in PHONE.tokens:
                self.wfile.write(ws_frame(8, struct.pack(">H", 4401) + b"unauthorized"))
                return
            if len([c for c in PHONE.clients if not c.dead]) >= 4:
                self.wfile.write(ws_frame(8, struct.pack(">H", 4409) + b"too many clients"))
                return
            sock.settimeout(None)
            client = Client(self.wfile, PHONE.tokens[tok]["clientName"])
            client.send_json({"type": "hello", "api": API_VERSION, "deviceId": PHONE.device_id, "name": PHONE.name})
            client.send_json(dict({"type": "status"}, **PHONE.status()))
            PHONE.clients.append(client)
            print("WebSocket open: %s" % client.client_name)
            threading.Thread(target=client.run_sender, daemon=True).start()
            while not client.dead:
                op, data = ws_read_frame(self.rfile)
                if op == 8:
                    client.close(1000)
                    break
                if op == 9:
                    continue  # the sender doesn't pong; browsers don't ping
                if op != 1:
                    continue
                try:
                    msg = json.loads(data)
                except ValueError:
                    continue
                if msg.get("type") == "preview":
                    p = client.preview
                    if "enabled" in msg:
                        p["enabled"] = bool(msg["enabled"])
                    if isinstance(msg.get("fps"), (int, float)):
                        p["fps"] = max(1, min(15, msg["fps"]))
                    if isinstance(msg.get("width"), int):
                        p["width"] = max(160, min(1280, msg["width"]))
        except (WsClosed, OSError):
            pass
        finally:
            if client:
                client.dead = True
                if client in PHONE.clients:
                    PHONE.clients.remove(client)
                print("WebSocket closed: %s" % client.client_name)

    # -- mock-only controls (no auth; local testing) --------------------------

    def mock_control(self, path, query):
        P = PHONE
        q = {k: v[0] for k, v in query.items()}
        if path == "/mock/ring":
            P.ring_blocked = q.get("blocked", "1") == "1"
        elif path == "/mock/fail-move":
            P.fail_moves.add(P.move_n + int(q.get("n", "1")))
        elif path == "/mock/answer" and P.job and P.job.prompt:
            try:
                P.answer({"promptId": P.job.prompt["id"], "answer": q.get("answer")}, "phone")
            except ApiError as e:
                return self.send_json(e.status, e.body(), cors=False)
        elif path == "/mock/camera":
            # The user opens (open=1) or leaves (open=0) the Capture screen on the phone.
            P.camera_open = q.get("open", "1") == "1"
            if not P.camera_open and P.job:
                P.abort("phone")
            P.push_status()
        elif path in ("/mock/session", "/mock/open", "/mock/close"):
            # The user at the phone: makes a session (name=, open=1 to open it), opens one
            # (id=), or closes the open one.
            try:
                if path == "/mock/session":
                    s = P.mock_session(q.get("name") or "Mock badge", q.get("notes", "Made by the mock phone."))
                    if q.get("open", "1") == "1":
                        P.set_target({"kind": "session", "sessionId": s["id"], "name": s["name"]}, "phone")
                elif path == "/mock/open":
                    s = P.get_session(q.get("id", ""))
                    P.set_target({"kind": "session", "sessionId": s["id"], "name": s["name"]}, "phone")
                else:
                    P.set_target({"kind": "none"}, "phone")
            except ApiError as e:
                return self.send_json(e.status, e.body(), cors=False)
        elif path == "/mock/offline":
            P.offline_until = time.monotonic() + int(q.get("ms", "10000")) / 1000
        elif path == "/mock/uncalibrate":
            P.calibration = None
        elif path == "/mock/revoke":
            P.tokens.clear()
        elif path == "/mock/reset":
            if P.job:
                P.job.abort_flag.set()
            P.tokens.clear()
            P.calibration = None
            P.front = None
            P.ring_blocked = False
            P.offline_until = 0.0
            with P.lock:
                ids = list(P.shots)
                P.shots.clear()
                P.set_order.clear()
                session_ids = list(P.sessions)
                P.sessions.clear()
                P.target = {"kind": "none", "sessionId": None, "name": None, "setBy": None, "editorJoined": False}
                P.camera_open = not P.args.camera_closed
            for sid in ids:
                P.broadcast({"type": "shot_removed", "id": sid})
            for sid in session_ids:
                P.broadcast({"type": "session_removed", "id": sid})
            for c in list(P.clients):
                c.close(4401, "reset")
        state = {"code": P.code, "offlineMs": max(0, round((P.offline_until - time.monotonic()) * 1000)),
                 "ringBlocked": P.ring_blocked, "calibrated": bool(P.calibration),
                 "tokens": len(P.tokens), "job": (P.job or P.last_job).to_json() if (P.job or P.last_job) else None,
                 "cameraOpen": P.camera_open, "target": dict(P.target),
                 "sessions": [{"id": s["id"], "name": s["name"]} for s in P.sessions.values()],
                 "pendingMoveFailures": sorted(P.fail_moves)}
        self.send_json(200, state, cors=False)


# ---------------------------------------------------------------------------
# Editor server (a stand-in for `jekyll serve`, enough for /edit/)
# ---------------------------------------------------------------------------

EDITOR_SHELL = """<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Badge Add/Edit (mock phone dev server)</title>
<link rel="stylesheet" href="/badges/assets/css/main.css"></head>
<body><nav class="site-nav"><div class="nav-inner"><a href="/badges/edit/" class="nav-brand">the hamster badge museum · editor (dev)</a></div></nav>
<main><div class="site-wrapper">
%s
</div></main></body></html>
"""


class EditorHandler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=SITE_ROOT, **kw)

    def log_message(self, fmt, *args):
        if PHONE.args.verbose:
            sys.stderr.write("  editor %s\n" % (fmt % args))

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/edit", "/edit/", "/badges", "/badges/", "/badges/edit"):
            self.send_response(302)
            self.send_header("Location", "/badges/edit/")
            self.end_headers()
            return
        if path in ("/badges/edit/", "/badges/edit/index.html"):
            with open(os.path.join(SITE_ROOT, "edit", "index.html"), encoding="utf-8") as f:
                text = f.read()
            text = re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S)
            text = text.replace("{{ site.repository }}", "hamster/badges")
            data = (EDITOR_SHELL % text).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path.startswith("/badges/"):
            self.path = self.path[len("/badges"):]
            return super().do_GET()
        self.send_error(404)


def main():
    global PHONE, SITE_ROOT
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1", help="address to listen on (0.0.0.0 for the LAN)")
    ap.add_argument("--port", type=int, default=8737)
    ap.add_argument("--editor-port", type=int, default=4000, help="serve /badges/edit/ here (0 = off)")
    ap.add_argument("--site", default=DEFAULT_SITE_ROOT,
                    help="badges site repo checkout to serve the editor from (default: %(default)s)")
    ap.add_argument("--name", default="badge-turntable", help="the phone's mDNS name, without .local")
    ap.add_argument("--code", help="fixed 6-digit pairing code (default: random, changes every 2 minutes)")
    ap.add_argument("--token", help="pre-paired token, for curl tests")
    ap.add_argument("--calibrated", action="store_true", help="start calibrated")
    ap.add_argument("--deg-per-sec", type=float, default=90.0, help="table speed (the JAYEGT does about 24)")
    ap.add_argument("--fail-move", type=int, action="append", help="make the Nth closed-loop move fail (repeatable)")
    ap.add_argument("--ring-blocked", action="store_true", help="start with the ring hidden")
    ap.add_argument("--rotate", type=int, default=1, help="preview header rotation, 0-3 (default 1, to test it)")
    ap.add_argument("--sessions", type=int, default=0, help="start with N sessions on the phone, the last one open")
    ap.add_argument("--camera-closed", action="store_true", help="start off the Camera screen (camera endpoints 503)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    if args.code and not re.fullmatch(r"\d{6}", args.code):
        ap.error("--code must be 6 digits")

    SITE_ROOT = os.path.abspath(args.site)
    PHONE = Phone(args)
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass
    api = QuietServer((args.host, args.port), ApiHandler)
    print("Mock phone: http://%s:%d/api/info" % (args.host, args.port))
    if not PHONE.ffmpeg:
        print("  (no ffmpeg on PATH: spin videos will be empty)")
    if args.editor_port and not os.path.isfile(os.path.join(SITE_ROOT, "edit", "index.html")):
        print("Editor:     off (no badges site checkout at %s; use --site)" % SITE_ROOT)
    elif args.editor_port:
        ed = QuietServer(("127.0.0.1", args.editor_port), EditorHandler)
        threading.Thread(target=ed.serve_forever, daemon=True).start()
        print("Editor:     http://localhost:%d/badges/edit/" % args.editor_port)
        print("  In the Turntable panel, add the phone by address: 127.0.0.1 port %d" % args.port)
    try:
        api.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
