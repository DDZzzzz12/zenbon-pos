#!/usr/bin/env python3
"""Screen-off mode behind the System tab's "Close the screen UI" button.

Runs as root from pos-ui-off.service, which conflicts with pos-kiosk.service: starting it stops the kiosk
(cage and the browser), the server keeps running. This script then

  * turns the screen off (backlight to 0, framebuffer powerdown, console blanked) and
  * waits for the wake shortcut (WAKE_COMBO, default ctrl+alt+u) on any attached keyboard, read straight
    from /dev/input, because no compositor is running to deliver key events.

On the shortcut it switches the screen back on and starts pos-kiosk.service. The service's ExecStopPost
runs "screen-off.py --restore" as well, so a stop by any other route (systemctl start pos-kiosk, reboot
handling, a crash) leaves the screen on.

  screen-off.py             run (service entry point)
  screen-off.py --restore   switch the screen back on (idempotent)
  screen-off.py --validate COMBO   exit 0 when COMBO is a valid shortcut

A shortcut names physical key positions of a US keyboard (evdev codes), so "u" is the key labelled U on a
German layout as well. It must contain at least one modifier: a bare key would wake the till when someone
types on the closed-down machine.
"""

import glob
import json
import os
import select
import struct
import subprocess
import sys
import time

COMBO = os.environ.get("WAKE_COMBO", "ctrl+alt+u")
STATE_FILE = os.environ.get("SCREEN_OFF_STATE", "/run/pos-screen-off.json")
BACKLIGHT_GLOB = os.environ.get("BACKLIGHT_GLOB", "/sys/class/backlight/*")
FB_GLOB = os.environ.get("FB_GLOB", "/sys/class/graphics/fb[0-9]*")
CONSOLE = os.environ.get("CONSOLE_TTY", "/dev/tty1")
INPUT_GLOB = os.environ.get("INPUT_GLOB", "/dev/input/event*")
RESCAN_SECONDS = 3

EV_KEY = 1
FB_BLANK_UNBLANK = "0"
FB_BLANK_POWERDOWN = "4"
EVENT_FORMAT = "llHHi"  # struct input_event: time (sec, usec), type, code, value
EVENT_SIZE = struct.calcsize(EVENT_FORMAT)

MODIFIERS = {"ctrl": (29, 97), "alt": (56, 100), "shift": (42, 54), "meta": (125, 126)}


def build_key_codes():
    codes = {}
    for row, first in (("qwertyuiop", 16), ("asdfghjkl", 30), ("zxcvbnm", 44)):
        for offset, letter in enumerate(row):
            codes[letter] = first + offset
    for offset, digit in enumerate("1234567890"):
        codes[digit] = 2 + offset
    for number in range(1, 11):
        codes[f"f{number}"] = 58 + number
    codes.update({"f11": 87, "f12": 88, "space": 57, "enter": 28})
    return codes


KEY_CODES = build_key_codes()


def log(message):
    sys.stderr.write(f"screen-off: {message}\n")
    sys.stderr.flush()


def parse_combo(text):
    """'ctrl+alt+u' -> (required modifier names, main key code); ValueError when it is not usable."""
    parts = [part.strip().lower() for part in text.split("+")]
    key, modifiers = parts[-1], parts[:-1]
    if key not in KEY_CODES:
        raise ValueError(f"unknown key '{key}'")
    unknown = [name for name in modifiers if name not in MODIFIERS]
    if unknown:
        raise ValueError(f"unknown modifier '{unknown[0]}' (use ctrl, alt, shift or meta)")
    if not modifiers:
        raise ValueError("needs at least one modifier (ctrl, alt, shift or meta)")
    return modifiers, KEY_CODES[key]


class ComboTracker:
    """Follows key presses across every keyboard and says when the shortcut is completed."""

    def __init__(self, combo):
        self.modifiers, self.key = parse_combo(combo)
        self.down = set()

    def feed(self, code, value):
        if value == 0:
            self.down.discard(code)
            return False
        self.down.add(code)
        return value == 1 and code == self.key and all(
            any(variant in self.down for variant in MODIFIERS[name]) for name in self.modifiers
        )


# --- Screen -----------------------------------------------------------------------------------------

def read_text(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return None


def write_text(path, value):
    try:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(str(value))
        return True
    except OSError as error:
        log(f"cannot write {path}: {error}")
        return False


def console_setterm(mode):
    try:
        with open(CONSOLE, "w") as tty:
            subprocess.run(["setterm", "--term", "linux", "--blank", mode], stdin=tty, stdout=tty,
                           stderr=subprocess.DEVNULL, timeout=5, check=False)
    except (OSError, subprocess.SubprocessError) as error:
        log(f"setterm {mode} failed: {error}")


def screen_off():
    """Remember each backlight's level once, then switch everything off. Safe to call twice."""
    state = {}
    try:
        with open(STATE_FILE, encoding="utf-8") as handle:
            state = json.load(handle)
    except (OSError, ValueError):
        pass
    saved = state.setdefault("backlight", {})
    for device in glob.glob(BACKLIGHT_GLOB):
        current = read_text(os.path.join(device, "brightness"))
        if device not in saved and current and current != "0":
            saved[device] = current
        write_text(os.path.join(device, "brightness"), 0)
    with open(STATE_FILE, "w", encoding="utf-8") as handle:
        json.dump(state, handle)
    for framebuffer in glob.glob(FB_GLOB):
        write_text(os.path.join(framebuffer, "blank"), FB_BLANK_POWERDOWN)
    console_setterm("force")


def screen_on():
    """Undo screen_off(). Without a saved level a backlight is raised to its maximum, never left at 0."""
    state = {}
    try:
        with open(STATE_FILE, encoding="utf-8") as handle:
            state = json.load(handle)
    except (OSError, ValueError):
        pass
    saved = state.get("backlight", {})
    for device in glob.glob(BACKLIGHT_GLOB):
        level = saved.get(device) or read_text(os.path.join(device, "max_brightness"))
        if level and read_text(os.path.join(device, "brightness")) == "0":
            write_text(os.path.join(device, "brightness"), level)
    for framebuffer in glob.glob(FB_GLOB):
        write_text(os.path.join(framebuffer, "blank"), FB_BLANK_UNBLANK)
    console_setterm("poke")
    try:
        os.remove(STATE_FILE)
    except OSError:
        pass


# --- Keyboards --------------------------------------------------------------------------------------

def open_inputs(known):
    """Open every event device not open yet (keyboards come and go); returns {fd: path}."""
    for path in glob.glob(INPUT_GLOB):
        if path in known.values():
            continue
        try:
            known[os.open(path, os.O_RDONLY | os.O_NONBLOCK)] = path
        except OSError:
            continue
    return known


def close_input(fds, fd):
    os.close(fd)
    del fds[fd]


def wait_for_combo(tracker):
    fds = {}
    next_scan = 0.0
    while True:
        if time.monotonic() >= next_scan:
            open_inputs(fds)
            next_scan = time.monotonic() + RESCAN_SECONDS
        if not fds:
            time.sleep(RESCAN_SECONDS)
            continue
        ready, _, _ = select.select(list(fds), [], [], RESCAN_SECONDS)
        for fd in ready:
            try:
                data = os.read(fd, EVENT_SIZE * 64)
            except BlockingIOError:
                continue
            except OSError:  # unplugged
                close_input(fds, fd)
                continue
            if not data:
                close_input(fds, fd)
                continue
            for offset in range(0, len(data) - EVENT_SIZE + 1, EVENT_SIZE):
                _, _, kind, code, value = struct.unpack_from(EVENT_FORMAT, data, offset)
                if kind == EV_KEY and tracker.feed(code, value):
                    return


def run():
    tracker = ComboTracker(COMBO)
    screen_off()
    log(f"screen off, waiting for {COMBO}")
    wait_for_combo(tracker)
    log(f"{COMBO} pressed, bringing the till back")
    screen_on()
    subprocess.run(["systemctl", "--no-block", "start", "pos-kiosk.service"], check=False)


def main(argv):
    if argv[1:2] == ["--restore"]:
        screen_on()
        return 0
    if argv[1:2] == ["--validate"]:
        try:
            parse_combo(argv[2] if len(argv) > 2 else "")
        except ValueError as error:
            sys.stderr.write(f"{error}\n")
            return 1
        return 0
    run()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
