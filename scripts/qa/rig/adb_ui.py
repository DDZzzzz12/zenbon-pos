#!/usr/bin/env python3
"""Drive the till / order-station app on an Android emulator by visible text (docs/testing/qa-rig.md section 6).

    adb_ui.py setup  <serial> <apk>              uninstall + install, grant notifications, disable every IME,
                                                 adb reverse tcp:8080 (run after every emulator boot)
    adb_ui.py dump   <serial>                    every visible text / content-desc with its tap point
    adb_ui.py tap    <serial> <exact text> [--nth N] [--long]
    adb_ui.py wait   <serial> <exact text> [--timeout S]
    adb_ui.py text   <serial> <literal text>     type non-secret text into the focused field
    adb_ui.py secret <serial> <ENV_VAR>          type the VALUE of an env var (a PIN from creds.env) without printing it
    adb_ui.py key    <serial> back|enter|del|tab [--repeat N]
    adb_ui.py shot   <serial> <file.png> [--burst N]   screenshot(s); a burst of N every 0.5 s catches toasts

Why each rule exists (all cost a pass once):
  - taps match the WHOLE text, never a substring ("Koppeln" hit the title "Gerät koppeln", "2" hit a badge)
  - a tap waits for two identical UI dumps, because coordinates go stale while a dialog animates
  - `key escape` is not offered: keyevent 111 DISMISSES a Compose dialog. To hide the keyboard tap a
    neutral spot inside the dialog instead
  - `adb shell input text` splits on spaces, so spaces are sent as %s
  - IMEs come back after an install, so `setup` disables them after installing
  - a disabled Compose button still has bounds: after a tap that "did nothing", take a screenshot
adb is taken from $ANDROID_HOME / ~/Library/Android/sdk when it is not on PATH.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "org.example.project"


def adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Library/Android/sdk")):
        if root and os.path.exists(os.path.join(root, "platform-tools", "adb")):
            return os.path.join(root, "platform-tools", "adb")
    sys.exit("adb not found (set ANDROID_HOME)")


ADB = adb_path()


def adb(serial: str, *args: str, check: bool = True, capture: bool = True) -> str:
    r = subprocess.run([ADB, "-s", serial, *args], capture_output=capture, text=not args[:1] == ("exec-out",))
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {(r.stderr or '')[:300]}")
    return r.stdout if capture else ""


def nodes(serial: str) -> list[dict]:
    # uiautomator answers with no XML ("could not get idle state") while the screen animates: retry.
    xml = ""
    for _ in range(8):
        raw = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty")
        text = raw.decode(errors="replace") if isinstance(raw, bytes) else raw
        if "<?xml" in text and "</hierarchy>" in text:
            xml = text[text.find("<?xml"): text.rfind("</hierarchy>") + len("</hierarchy>")]
            break
        time.sleep(1)
    if not xml:
        sys.exit("uiautomator returned no screen after 8 tries (an ANR dialog or a busy host?) - take a `shot`")
    out = []
    for n in ET.fromstring(xml).iter("node"):
        label = n.get("text") or n.get("content-desc") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not label.strip() or not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"label": label.strip(), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                    "clickable": n.get("clickable") == "true", "enabled": n.get("enabled") == "true"})
    return out


def dismiss_anr(serial: str, current: list[dict]) -> bool:
    """An Android "<app> isn't responding" dialog hides the app from uiautomator. Under host load it is
    environment, not product (catalogue section 16): tap Wait and say so, so it lands in the results."""
    if not any(n["label"].endswith("isn't responding") for n in current):
        return False
    wait = next((n for n in current if n["label"] == "Wait"), None)
    if wait is None:
        return False
    adb(serial, "shell", "input", "tap", str(wait["x"]), str(wait["y"]))
    print("NOTE: dismissed an \"isn't responding\" dialog with Wait (environment; record it if it recurs)",
          file=sys.stderr)
    time.sleep(2)
    return True


def stable_nodes(serial: str, tries: int = 6) -> list[dict]:
    prev = None
    anrs = 0
    for _ in range(tries + 6):
        cur = nodes(serial)
        if anrs < 5 and dismiss_anr(serial, cur):
            anrs += 1
            prev = None
            continue
        if cur == prev:
            return cur
        prev = cur
        time.sleep(0.6)
    return prev or []


def cmd_dump(serial: str) -> None:
    for n in stable_nodes(serial):
        flags = ("" if n["enabled"] else " [disabled]") + (" [clickable]" if n["clickable"] else "")
        print(f"{n['x']:>5},{n['y']:<5} {n['label']}{flags}")


def cmd_tap(serial: str, text: str, nth: int = 1, long: bool = False) -> None:
    hits = [n for n in stable_nodes(serial) if n["label"] == text]
    if len(hits) < nth:
        near = sorted({n["label"] for n in nodes(serial) if text.lower() in n["label"].lower()})[:8]
        sys.exit(f"no exact match #{nth} for {text!r}" + (f"; similar: {near}" if near else "; run `dump`"))
    n = hits[nth - 1]
    if long:
        adb(serial, "shell", "input", "swipe", str(n["x"]), str(n["y"]), str(n["x"]), str(n["y"]), "900")
    else:
        adb(serial, "shell", "input", "tap", str(n["x"]), str(n["y"]))
    note = "" if n["enabled"] else " (node reports DISABLED - check a screenshot)"
    print(f"{'long-' if long else ''}tapped {text!r} at {n['x']},{n['y']}{note}")


def cmd_wait(serial: str, text: str, timeout: float = 20) -> None:
    end = time.time() + timeout
    while time.time() < end:
        if any(n["label"] == text for n in stable_nodes(serial, tries=2)):
            print(f"visible: {text!r}")
            return
        time.sleep(1)
    sys.exit(f"{text!r} not visible after {timeout:.0f} s")


def type_text(serial: str, value: str) -> None:
    adb(serial, "shell", "input", "text", value.replace("%", "\\%").replace(" ", "%s"))


def cmd_setup(serial: str, apk: str) -> None:
    adb(serial, "uninstall", PACKAGE, check=False)
    adb(serial, "install", apk)
    adb(serial, "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    for ime in adb(serial, "shell", "ime", "list", "-s").split():
        adb(serial, "shell", "ime", "disable", ime, check=False)
    adb(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "0")
    adb(serial, "reverse", "tcp:8080", "tcp:8080")
    print(f"{serial}: installed {os.path.basename(apk)}, notifications granted, IMEs disabled, 8080 reversed")


def cmd_shot(serial: str, path: str, burst: int = 1) -> None:
    base, ext = os.path.splitext(path)
    for i in range(burst):
        target = path if burst == 1 else f"{base}-{i + 1}{ext or '.png'}"
        with open(target, "wb") as f:
            f.write(subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
        print(target)
        if i + 1 < burst:
            time.sleep(0.5)


KEYS = {"back": "4", "enter": "66", "del": "67", "tab": "61"}


def main() -> None:
    a = sys.argv[1:]
    if len(a) < 2:
        print(__doc__)
        sys.exit(2)
    cmd, serial, rest = a[0], a[1], a[2:]

    def opt(name: str, default=None):
        if name in rest:
            i = rest.index(name)
            value = rest[i + 1] if i + 1 < len(rest) and not rest[i + 1].startswith("--") else True
            del rest[i: i + (2 if value is not True else 1)]
            return value
        return default

    if cmd == "dump":
        cmd_dump(serial)
    elif cmd == "tap":
        nth, long = int(opt("--nth", 1)), bool(opt("--long", False))
        cmd_tap(serial, " ".join(rest), nth, long)
    elif cmd == "wait":
        timeout = float(opt("--timeout", 20))
        cmd_wait(serial, " ".join(rest), timeout)
    elif cmd == "text":
        type_text(serial, " ".join(rest))
    elif cmd == "secret":
        value = os.environ.get(rest[0]) if rest else None
        if not value:
            sys.exit(f"env var {rest[0] if rest else '?'} is not set - source $QA_DIR/creds.env")
        type_text(serial, value)
        print(f"typed ${rest[0]} ({len(value)} characters)")
    elif cmd == "key":
        repeat = int(opt("--repeat", 1))
        if not rest or rest[0] not in KEYS:
            sys.exit(f"key must be one of {sorted(KEYS)} (no escape: it dismisses Compose dialogs)")
        for _ in range(repeat):
            adb(serial, "shell", "input", "keyevent", KEYS[rest[0]])
    elif cmd == "shot":
        burst = int(opt("--burst", 1))
        cmd_shot(serial, rest[0], burst)
    elif cmd == "setup":
        cmd_setup(serial, rest[0])
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
