# IR tools

[iguana_ir.py](iguana_ir.py) captures and replays turntable remote codes on a PC with an [IguanaWorks USB IR transceiver](https://www.iguanaworks.net/). It reads and writes Flipper Zero `.ir` files, which the Badge Turntable app bundles in [app/src/main/assets/profiles/](../badge-turntable/app/src/main/assets/profiles/).

It needs Python 3 (standard library only) and the IguanaIR software, with the *Iguanaworks IR Daemon* service running. On Windows the tool finds `igclient.exe` in `C:\Program Files (x86)\IguanaIR\`. Elsewhere, put `igclient` on the PATH, set `IGCLIENT`, or pass `--igclient`.

## Commands

Run these from the repo root.

```
python apps/ir-tools/iguana_ir.py listen [--raw]
python apps/ir-tools/iguana_ir.py learn <file.ir> [--redo] [--only NAME ...] [--extra NAME ...]
python apps/ir-tools/iguana_ir.py send <file.ir> <name>
python apps/ir-tools/iguana_ir.py nec [--address 00] <command> [<command> ...]
```

- **`listen`** prints each code the receiver sees, such as `NEC address 0x00 command 0x40`. `--raw` adds the timings. Stop with Ctrl+C.
- **`learn`** goes through the JAYEGT buttons one at a time. For each one: press Enter, press the button 2–3 times (short presses, not holds), then keep, retry or skip the result. The file is saved after every button, and buttons already in it are skipped unless `--redo` is given. `--only` captures the names given instead of the JAYEGT list, for another remote. `--extra` captures extra names after the JAYEGT list.
- **`send`** transmits one named button. Use it to check a capture moves the table.
- **`nec`** transmits NEC commands by hex number, with no `.ir` file needed. If several are given, it waits for Enter between them so you can watch what each one does. For the JAYEGT's unused codes: `nec 45 19 08 1C 5A 42 52 4A`.

Button names follow [ButtonNames.java](../badge-turntable/app/src/main/java/org/snurkle/badgeturntable/ir/ButtonNames.java), so the app assigns roles itself when it loads the file.

## Decoding

The tool decodes NEC, NECext and Samsung32 into Flipper "parsed" signals. Bits are classified by mark + space period, which tolerates receivers that stretch marks. Anything else is saved as a `raw` signal (the longest burst seen), which the app can still transmit. In each capture window the code most frames agree on wins, and NEC repeat frames are ignored.

## igclient quirks

- It buffers its output when piped, so receiving happens in fixed windows (`--sleep`) rather than as a live stream.
- It exits with status 1 after `--sleep` even when it worked, so the tool checks igclient's messages instead of the exit status.
- Each time the receiver is turned on, it reports one or two stray long pulses. They're too short to count as a frame and are dropped.

## JAYEGT 5.74 in remote

Captured Nov 3, 2026, in [jayegt-5.74.ir](../badge-turntable/app/src/main/assets/profiles/jayegt-5.74.ir). Every button is NEC, address 0x00. Replaying them with `send` and `nec` from the IguanaIR moves the table, which confirms both the captured codes and the tool's NEC encoding.

| Button | Name | Command | What it does (observed) |
| --- | --- | --- | --- |
| On/Pause | `On_pause` | 0x47 | Continuous: stops or resumes. Intermittent: stops, and pressing again resumes in *continuous* mode. |
| Turn Left | `Turn_left` | 0x16 | From rest: continuous rotation to the left. |
| Turn Right | `Turn_right` | 0x0D | From rest: continuous rotation to the right. The app starts continuous moves with this. |
| +Speed | `Speed_up` | 0x15 | |
| −Speed | `Speed_down` | 0x18 | |
| Resume Continuous Rotation | `Continuous` | 0x0C | |
| Intermittent Rotation | `Intermittent` | 0x5E | From swing: turns one way, stopping at uneven intervals of about 120°. |
| 45° | `Swing_45` | 0x44 | Sets the swing: the platter rocks back and forth this far. From rest, starts swinging. |
| 90° | `Swing_90` | 0x40 | As above, 90°. |
| 180° | `Swing_180` | 0x43 | As above, 180°. |
| Set Origin Position | `Set_origin` | 0x07 | Stores the current position as the origin. |
| Back to Set Position | `Back_to_set` | 0x09 | Returns to the stored origin. Does nothing useful until `Set_origin` has been pressed. |
| Unlabeled bottom button | `Step_90` | 0x46 | Advances 90° and stops. This is the app's step for each view. |

The swing buttons don't map to any app role. `Step_90` is always 90°: the ASA button on the base only starts swing mode and changes its angle. Calibration still measures the step and stops with an explanation if it isn't about 90°, in case a profile maps the wrong button.

These are all codes from the common 21-key "Car MP3" NEC remote, so the turntable's remote is probably that remote with relabeled keys. The other eight codes on that remote were tested with `nec` on Nov 3, 2026: **0x19** does the same as On/Pause, and 0x45, 0x08, 0x1C, 0x5A, 0x42, 0x52 and 0x4A do nothing. So the base's back-panel buttons have no IR codes. That loses nothing: the remote's +Speed/−Speed do SR's job, and its 45/90/180 swing buttons do ASA's (swing mode and angle). Only SR's hold-to-stop has no IR code, and On/Pause covers that.
