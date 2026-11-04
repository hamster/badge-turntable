#!/usr/bin/env python3
"""Capture and replay turntable IR codes with an IguanaWorks USB IR transceiver.

Drives Iguanaworks' `igclient` (the igdaemon service must be running) and reads
and writes Flipper Zero `.ir` files, the format the Badge Turntable app bundles
in app/src/main/assets/profiles/.

    python iguana_ir.py listen                 # print every code the receiver sees
    python iguana_ir.py learn jayegt-5.74.ir   # walk through the JAYEGT buttons
    python iguana_ir.py send jayegt-5.74.ir 90 # transmit one button

Standard library only. igclient buffers its output when piped, so receiving
works in fixed windows (igclient --sleep) rather than as a live stream.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter

DEFAULT_IGCLIENT = r"C:\Program Files (x86)\IguanaIR\igclient.exe"

# Gaps longer than this end a frame. NEC frames repeat every 108 ms, with
# about 40 ms of silence after a full frame.
FRAME_GAP_US = 15000
MIN_FRAME_ENTRIES = 6  # shorter bursts are noise

# JAYEGT 5.74 in remote. Names match ButtonNames.java so the app maps roles itself.
JAYEGT_BUTTONS = [
    ("On_pause", "On/Pause"),
    ("Turn_left", "Turn Left"),
    ("Turn_right", "Turn Right"),
    ("Speed_up", "+Speed"),
    ("Speed_down", "-Speed"),
    ("Continuous", "Resume Continuous Rotation"),
    ("Intermittent", "Intermittent Rotation"),
    # The 45/90/180 buttons set the swing of intermittent mode; they don't step.
    ("Swing_45", "45 degrees"),
    ("Swing_90", "90 degrees"),
    ("Swing_180", "180 degrees"),
    ("Set_origin", "Set Origin Position"),
    ("Back_to_set", "Back to Set Position"),
    # The unlabeled bottom button advances 90 degrees and stops: the app's step.
    ("Step_90", "the unlabeled bottom button"),
]


# --------------------------------------------------------------------------- igclient

def find_igclient(explicit=None):
    for candidate in (explicit, os.environ.get("IGCLIENT"), shutil.which("igclient"), DEFAULT_IGCLIENT):
        if candidate and os.path.isfile(candidate):
            return candidate
    sys.exit("Can't find igclient. Install IguanaIR, or pass --igclient / set IGCLIENT.")


def run_igclient(igclient, args, timeout, check_rc=True):
    try:
        p = subprocess.run([igclient] + args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        sys.exit("igclient timed out. Is the Iguanaworks IR Daemon service running?")
    out = p.stdout + p.stderr
    if (check_rc and p.returncode != 0) or "failed" in out.lower() or "error" in out.lower():
        sys.exit("igclient " + " ".join(args) + " failed:\n" + out.strip())
    return out


def receive(igclient, seconds):
    """Listen for `seconds` and return [(is_pulse, microseconds), ...], merged."""
    # igclient always exits 1 after --sleep, so check its messages instead.
    out = run_igclient(igclient, ["--receiver-on", "--sleep=%d" % seconds, "--receiver-off"], seconds + 10,
                       check_rc=False)
    if "receiver on: success" not in out:
        sys.exit("Couldn't turn the receiver on:\n" + out.strip())
    signals = []
    for m in re.finditer(r"^\s*(pulse|space):\s*(\d+)", out, re.M):
        is_pulse, us = m.group(1) == "pulse", int(m.group(2))
        if signals and signals[-1][0] == is_pulse:
            signals[-1] = (is_pulse, signals[-1][1] + us)
        else:
            signals.append((is_pulse, us))
    return signals


def transmit(igclient, carrier_hz, timings):
    lines = ["%s %d" % ("pulse" if i % 2 == 0 else "space", t) for i, t in enumerate(timings)]
    fd, path = tempfile.mkstemp(suffix=".txt", text=True)
    try:
        with os.fdopen(fd, "w") as f:
            f.write("\n".join(lines) + "\n")
        run_igclient(igclient, ["--set-carrier=%d" % carrier_hz, "--send=" + path], 15)
    finally:
        os.unlink(path)


# --------------------------------------------------------------------------- decoding

def split_frames(signals):
    """Split a pulse/space stream into frames: lists of µs timings starting with a pulse."""
    frames, cur = [], []
    for is_pulse, us in signals:
        if is_pulse:
            cur.append(us)
        elif cur:
            if us > FRAME_GAP_US:
                frames.append(cur)
                cur = []
            else:
                cur.append(us)
    if cur:
        frames.append(cur)
    return [f for f in frames if len(f) >= MIN_FRAME_ENTRIES or is_repeat(f)]


def near(value, target, tol=0.3):
    return abs(value - target) <= target * tol


def is_repeat(frame):
    return len(frame) in (2, 3) and near(frame[0], 9000) and near(frame[1], 2250)


def decode(frame):
    """Return a dict describing the frame: a Flipper parsed signal, a repeat, or raw."""
    if is_repeat(frame):
        return {"kind": "repeat"}
    if len(frame) >= 2 + 64 and near(frame[1], 4500, 0.25):
        if near(frame[0], 9000, 0.25):
            header = "NEC"
        elif near(frame[0], 4500, 0.25):
            header = "Samsung"
        else:
            header = None
        if header:
            value, ok = 0, True
            for b in range(32):
                mark, space = frame[2 + 2 * b], frame[3 + 2 * b]
                period = mark + space  # robust to receivers that stretch marks
                if not (700 <= period <= 3000) or not (250 <= mark <= 1000):
                    ok = False
                    break
                if period > 1700:
                    value |= 1 << b
            if ok:
                b0, b1, b2, b3 = (value >> 0) & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF, (value >> 24) & 0xFF
                if header == "Samsung" and b0 == b1 and b2 ^ b3 == 0xFF:
                    return parsed("Samsung32", b0, b2)
                if header == "NEC":
                    if b0 ^ b1 == 0xFF and b2 ^ b3 == 0xFF:
                        return parsed("NEC", b0, b2)
                    return parsed("NECext", b0 | b1 << 8, b2 | b3 << 8)
    return {"kind": "raw", "frequency": 38000, "data": [int(round(t)) for t in frame]}


def parsed(protocol, address, command):
    return {"kind": "parsed", "protocol": protocol, "address": address, "command": command}


def describe(sig):
    if sig["kind"] == "parsed":
        width = 4 if sig["protocol"] == "NECext" else 2
        return "%s address 0x%0*X command 0x%0*X" % (sig["protocol"], width, sig["address"], width, sig["command"])
    if sig["kind"] == "repeat":
        return "NEC repeat (button held)"
    d = sig["data"]
    return "raw, %d timings, %.1f ms, starts %s" % (len(d), sum(d) / 1000.0, " ".join(map(str, d[:6])))


def key(sig):
    if sig["kind"] == "parsed":
        return (sig["protocol"], sig["address"], sig["command"])
    return None


def best_signal(signals):
    """Pick the code a button sends from one capture window. Returns (signal, note)."""
    frames = [decode(f) for f in split_frames(signals)]
    full = [s for s in frames if s["kind"] == "parsed"]
    if full:
        counts = Counter(key(s) for s in full)
        winner, n = counts.most_common(1)[0]
        sig = next(s for s in full if key(s) == winner)
        note = "%d of %d frames agree" % (n, len(full))
        if len(counts) > 1:
            note += "; also saw " + ", ".join("%s x%d" % (describe(parsed(*k)), c) for k, c in counts.items() if k != winner)
        return sig, note
    raws = [s for s in frames if s["kind"] == "raw"]
    if raws:
        # Not a protocol we decode: keep the longest burst as raw timings.
        sig = max(raws, key=lambda s: len(s["data"]))
        return sig, "no known protocol; %d raw bursts, kept the longest. Check it with `send`." % len(raws)
    if frames:
        return None, "only NEC repeat codes were seen; press and release the button instead of holding it"
    return None, "nothing received"


# --------------------------------------------------------------------------- .ir files

def fmt_bytes(value):
    return " ".join("%02X" % ((value >> (8 * i)) & 0xFF) for i in range(4))


def write_ir(path, named):
    out = ["Filetype: IR signals file", "Version: 1"]
    for name, sig in named:
        out += ["#", "name: " + name]
        if sig["kind"] == "parsed":
            out += ["type: parsed", "protocol: " + sig["protocol"],
                    "address: " + fmt_bytes(sig["address"]), "command: " + fmt_bytes(sig["command"])]
        else:
            out += ["type: raw", "frequency: %d" % sig["frequency"], "duty_cycle: 0.330000",
                    "data: " + " ".join(map(str, sig["data"]))]
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        f.write("\n".join(out) + "\n")


def read_ir(path):
    named, block = [], {}

    def flush():
        if "name" in block:
            named.append((block["name"], block_to_signal(block)))
        block.clear()

    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("#"):
                flush()
                continue
            k, sep, v = line.partition(":")
            if not sep:
                continue
            k, v = k.strip().lower(), v.strip()
            if k == "name" and "name" in block:
                flush()
            block[k] = v
    flush()
    return named


def le(hex_bytes):
    return sum(int(b, 16) << (8 * i) for i, b in enumerate(hex_bytes.split()))


def block_to_signal(b):
    if b.get("type") == "raw":
        return {"kind": "raw", "frequency": int(float(b.get("frequency", 38000))),
                "data": [int(x) for x in b.get("data", "").split()]}
    return parsed(b.get("protocol", ""), le(b.get("address", "00")), le(b.get("command", "00")))


# --------------------------------------------------------------------------- encoding

def encode(sig):
    """Return (carrier_hz, timings) for transmitting. Matches NecEncoder.java."""
    if sig["kind"] == "raw":
        return sig["frequency"], sig["data"]
    p, a, c = sig["protocol"], sig["address"], sig["command"]
    if p == "NEC":
        hdr, first, second = (9000, 4500), (a & 0xFF) | ((~a & 0xFF) << 8), (c & 0xFF) | ((~c & 0xFF) << 8)
    elif p == "NECext":
        hdr, first, second = (9000, 4500), a & 0xFFFF, c & 0xFFFF
    elif p == "Samsung32":
        hdr, first, second = (4500, 4500), (a & 0xFF) * 0x101, (c & 0xFF) | ((~c & 0xFF) << 8)
    else:
        sys.exit("Can't encode protocol %s" % p)
    bits = first | (second << 16)
    t = list(hdr)
    for b in range(32):
        t += [562, 1687 if (bits >> b) & 1 else 562]
    t.append(562)
    return 38000, t


# --------------------------------------------------------------------------- commands

def cmd_listen(args):
    ig = find_igclient(args.igclient)
    print("Listening in %d s windows. Point the remote at the receiver. Ctrl+C to stop." % args.window)
    try:
        while True:
            for frame in split_frames(receive(ig, args.window)):
                sig = decode(frame)
                print(describe(sig))
                if args.raw:
                    print("   ", " ".join(map(str, frame)))
            sys.stdout.flush()
    except KeyboardInterrupt:
        print()


def cmd_learn(args):
    ig = find_igclient(args.igclient)
    named = read_ir(args.file) if os.path.exists(args.file) else []
    have = {n for n, _ in named}
    buttons = [(n, label) for n, label in JAYEGT_BUTTONS] if not args.only else [(n, n) for n in args.only]
    if args.extra:
        buttons += [(n, n) for n in args.extra]
    print("Saving to %s. Each capture listens for %d s." % (args.file, args.window))
    print("Hold the remote 10-30 cm from the receiver. Short presses, not holds.\n")

    for name, label in buttons:
        if name in have and not args.redo:
            print("%-14s already captured, skipping (use --redo to replace)" % name)
            continue
        while True:
            ans = input("%s (%s): Enter, then press it 2-3 times. [s]kip, [q]uit: " % (label, name)).strip().lower()
            if ans == "q":
                return
            if ans == "s":
                break
            print("  listening...", end="", flush=True)
            sig, note = best_signal(receive(ig, args.window))
            print("\r  " + (describe(sig) if sig else "no code") + "  (" + note + ")")
            if not sig:
                continue
            ans = input("  [Enter] keep, [r]etry, [s]kip: ").strip().lower()
            if ans == "r":
                continue
            if ans != "s":
                named = [(n, s) for n, s in named if n != name] + [(name, sig)]
                write_ir(args.file, named)
            break
    print("\nDone. %d buttons in %s." % (len(named), args.file))
    dupes = Counter(key(s) for _, s in named if key(s))
    for k, c in dupes.items():
        if c > 1:
            print("Warning: %d buttons share %s" % (c, describe(parsed(*k))))


def cmd_send(args):
    ig = find_igclient(args.igclient)
    named = dict(read_ir(args.file))
    if args.name not in named:
        sys.exit("No '%s' in %s. It has: %s" % (args.name, args.file, ", ".join(named)))
    sig = named[args.name]
    carrier, timings = encode(sig)
    transmit(ig, carrier, timings)
    print("Sent %s: %s" % (args.name, describe(sig)))


def cmd_nec(args):
    ig = find_igclient(args.igclient)
    for c in args.command:
        sig = parsed("NEC", int(args.address, 16), int(c, 16))
        carrier, timings = encode(sig)
        transmit(ig, carrier, timings)
        print("Sent " + describe(sig))
        if len(args.command) > 1:
            input("  Enter for the next code...")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--igclient", help="path to igclient (default: IGCLIENT env, PATH, then %s)" % DEFAULT_IGCLIENT)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("listen", help="print decoded codes as they arrive")
    p.add_argument("--window", type=int, default=2, help="seconds per capture window (default 2)")
    p.add_argument("--raw", action="store_true", help="also print raw timings")
    p.set_defaults(func=cmd_listen)

    p = sub.add_parser("learn", help="capture the JAYEGT buttons into a Flipper .ir file")
    p.add_argument("file")
    p.add_argument("--window", type=int, default=4, help="seconds to listen per button (default 4)")
    p.add_argument("--redo", action="store_true", help="re-capture buttons already in the file")
    p.add_argument("--only", nargs="+", metavar="NAME", help="capture just these names instead of the JAYEGT list")
    p.add_argument("--extra", nargs="+", metavar="NAME", help="capture these names after the JAYEGT list")
    p.set_defaults(func=cmd_learn)

    p = sub.add_parser("send", help="transmit one button from a .ir file")
    p.add_argument("file")
    p.add_argument("name")
    p.set_defaults(func=cmd_send)

    p = sub.add_parser("nec", help="transmit NEC commands by number, e.g. to probe unused codes")
    p.add_argument("--address", default="00", help="hex address (default 00)")
    p.add_argument("command", nargs="+", help="hex commands; with several, waits for Enter between them")
    p.set_defaults(func=cmd_nec)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
