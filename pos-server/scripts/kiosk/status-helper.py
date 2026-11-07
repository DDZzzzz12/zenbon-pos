#!/usr/bin/env python3
"""Local status and power helper behind the POS kiosk's "System" tab.

Started by kiosk-browser.sh inside the kiosk session, so it lives and dies with the kiosk. It listens
on 127.0.0.1 only and serves:

  GET  /                the status page (status.html, with this run's token filled in)
  GET  /api/status      battery, server health, network and uptime as JSON
  POST /api/back        bring the tab that was showing before a warning back to the front
  POST /api/poweroff    power the machine off
  POST /api/reboot      restart the machine
  POST /api/ui-off      stop the kiosk and turn the screen off (pos-ui-off.service; the server keeps running)

POST needs the X-Token header the page was served with, so a page from another origin cannot trigger
a power action; every request must also carry a loopback Host header (DNS-rebinding guard).

When the battery drops below BATTERY_WARN_PERCENT while on battery power, the helper brings the System
tab to the front through Chromium's local DevTools HTTP endpoint (127.0.0.1 only), then repeats at a
fixed interval until the charger is connected. Power actions are permitted by the polkit rule
provision-node.sh installs for the kiosk user; nothing here needs root (the screen-off work itself runs as
root in pos-ui-off.service, see screen-off.py).
"""

import glob
import http.server
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.request

HOST = "127.0.0.1"
PORT = int(os.environ.get("STATUS_PORT", "8099"))
CDP_PORT = int(os.environ.get("CDP_PORT", "9222"))
HEALTH_URL = os.environ.get("SERVER_HEALTH_URL", "http://127.0.0.1:8080/health")
POWER_DIR = os.environ.get("POWER_SUPPLY_DIR", "/sys/class/power_supply")
WARN = int(os.environ.get("BATTERY_WARN_PERCENT", "40"))
CRIT = int(os.environ.get("BATTERY_CRITICAL_PERCENT", "15"))
REWARN_SECONDS = int(os.environ.get("BATTERY_REWARN_MINUTES", "10")) * 60
CRIT_REWARN_SECONDS = 180
POLL_SECONDS = int(os.environ.get("BATTERY_POLL_SECONDS", "20"))
PAGE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "status.html")
WAKE_COMBO = os.environ.get("WAKE_COMBO", "ctrl+alt+u")
TOKEN = secrets.token_urlsafe(24)
OWN_URL_PREFIX = f"http://{HOST}:{PORT}/"


def log(message):
    sys.stderr.write(f"status-helper: {message}\n")
    sys.stderr.flush()


def read_text(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return None


def battery():
    """The first battery, or {"present": False} on a machine without one."""
    batteries = sorted(glob.glob(os.path.join(POWER_DIR, "BAT*")))
    if not batteries:
        return {"present": False}
    capacity = read_text(os.path.join(batteries[0], "capacity"))
    status = read_text(os.path.join(batteries[0], "status")) or "Unknown"
    mains = {p for pattern in ("AC*", "ADP*", "A*") for p in glob.glob(os.path.join(POWER_DIR, pattern))}
    on_ac = any(read_text(os.path.join(p, "online")) == "1" for p in mains) or status in ("Charging", "Full")
    return {
        "present": True,
        "percent": int(capacity) if capacity and capacity.isdigit() else None,
        "status": status,
        "onAc": on_ac,
    }


def level(bat):
    """ok | low | critical. Only a battery that is discharging can be low."""
    if not bat.get("present") or bat.get("onAc") or bat.get("percent") is None:
        return "ok"
    if bat["percent"] < CRIT:
        return "critical"
    if bat["percent"] < WARN:
        return "low"
    return "ok"


def server_health():
    started = time.monotonic()
    try:
        with urllib.request.urlopen(HEALTH_URL, timeout=2) as response:
            return {"ok": response.status == 200, "ms": int((time.monotonic() - started) * 1000)}
    except Exception:
        return {"ok": False, "ms": None}


def wifi():
    try:
        out = subprocess.run(
            ["nmcli", "-t", "-f", "ACTIVE,SSID,SIGNAL", "device", "wifi"],
            capture_output=True, text=True, timeout=3,
            env={**os.environ, "LC_ALL": "C"},  # nmcli prints "ja"/"nein" under a German locale
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    for line in out.splitlines():
        fields = re.split(r"(?<!\\):", line)
        if len(fields) >= 3 and fields[0] == "yes":
            signal = fields[-1]
            return {"ssid": ":".join(fields[1:-1]).replace("\\:", ":"), "signal": int(signal) if signal.isdigit() else None}
    return None


def addresses():
    try:
        out = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=3).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    return [a for a in out.split() if "." in a]


def uptime_seconds():
    raw = read_text("/proc/uptime")
    try:
        return int(float(raw.split()[0]))
    except (AttributeError, ValueError, IndexError):
        return None


def status():
    bat = battery()
    return {
        "battery": bat,
        "level": level(bat),
        "warnPercent": WARN,
        "criticalPercent": CRIT,
        "server": server_health(),
        "wifi": wifi(),
        "addresses": addresses(),
        "hostname": os.uname().nodename,
        "uptimeSeconds": uptime_seconds(),
    }


# --- Tab control through Chromium's local DevTools HTTP endpoint ------------------------------------

def cdp(path):
    with urllib.request.urlopen(f"http://127.0.0.1:{CDP_PORT}{path}", timeout=3) as response:
        return response.read().decode("utf-8")


def pages():
    return [t for t in json.loads(cdp("/json/list")) if t.get("type") == "page"]


def activate(target_id):
    cdp(f"/json/activate/{target_id}")


def is_own(target):
    return target.get("url", "").startswith(OWN_URL_PREFIX)


# The tab that was showing when a warning took over, so "back" can return to it. Chromium lists targets
# most recently used first; if that ever stops holding, "back" still lands on a real app tab.
previous_target = None
previous_lock = threading.Lock()


def show_status_tab():
    global previous_target
    try:
        tabs = pages()
        own = next((t for t in tabs if is_own(t)), None)
        if own is None:
            return False
        others = [t for t in tabs if not is_own(t)]
        with previous_lock:
            # Already looking at the status tab: keep the tab we remembered, do not overwrite it with itself.
            if tabs and not is_own(tabs[0]) and others:
                previous_target = others[0]["id"]
        activate(own["id"])
        return True
    except Exception as error:  # DevTools not up yet, browser restarting: try again next round
        log(f"could not show the status tab: {error}")
        return False


def go_back():
    try:
        tabs = pages()
        others = [t for t in tabs if not is_own(t)]
        with previous_lock:
            wanted = previous_target
        target = next((t for t in others if t["id"] == wanted), others[0] if others else None)
        if target is None:
            return False
        activate(target["id"])
        return True
    except Exception as error:
        log(f"could not return to the app tab: {error}")
        return False


def warn_loop():
    """Pull the status tab forward while on battery below the threshold, then repeat at an interval."""
    last_warning = 0.0
    while True:
        try:
            bat = battery()
            state = level(bat)
            if state == "ok":
                last_warning = 0.0  # charger back or charged: the next drop warns immediately
            else:
                interval = CRIT_REWARN_SECONDS if state == "critical" else REWARN_SECONDS
                if time.monotonic() - last_warning >= interval or last_warning == 0.0:
                    log(f"battery {bat.get('percent')}% ({state}) — showing the status tab")
                    if show_status_tab():
                        last_warning = time.monotonic()
        except Exception as error:
            log(f"warning loop error: {error}")
        time.sleep(POLL_SECONDS)


def wake_label():
    """'ctrl+alt+u' -> 'Ctrl+Alt+U', the way the page shows the screen-off wake shortcut."""
    return "+".join(part.capitalize() for part in WAKE_COMBO.split("+"))


# --- HTTP -------------------------------------------------------------------------------------------

def run(command):
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=15)
        return {"ok": done.returncode == 0, "error": (done.stderr or done.stdout).strip()}
    except (OSError, subprocess.SubprocessError) as error:
        return {"ok": False, "error": str(error)}


ACTIONS = {
    "/api/poweroff": lambda: run(["systemctl", "poweroff"]),
    "/api/reboot": lambda: run(["systemctl", "reboot"]),
    "/api/ui-off": lambda: run(["systemctl", "--no-block", "start", "pos-ui-off.service"]),
    "/api/back": lambda: {"ok": go_back(), "error": ""},
}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "pos-kiosk-status"

    def log_message(self, fmt, *args):
        log(fmt % args)

    def host_ok(self):
        return self.headers.get("Host", "").rsplit(":", 1)[0] in ("127.0.0.1", "localhost")

    def send(self, code, body, content_type="application/json"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self.host_ok():
            return self.send(403, "{}")
        if self.path in ("/", "/index.html"):
            page = read_text(PAGE_PATH)
            if page is None:
                return self.send(500, "status.html missing", "text/plain")
            return self.send(200, page.replace("__TOKEN__", TOKEN).replace("__WAKE__", wake_label()), "text/html")
        if self.path == "/api/status":
            return self.send(200, json.dumps(status()))
        return self.send(404, "{}")

    def do_POST(self):
        if not self.host_ok() or not secrets.compare_digest(self.headers.get("X-Token", ""), TOKEN):
            return self.send(403, "{}")
        action = ACTIONS.get(self.path)
        if action is None:
            return self.send(404, "{}")
        log(f"action {self.path}")
        result = action()
        if not result["ok"]:
            log(f"action {self.path} failed: {result['error']}")
        return self.send(200 if result["ok"] else 500, json.dumps(result))


def main():
    threading.Thread(target=warn_loop, daemon=True).start()
    server = http.server.ThreadingHTTPServer((HOST, PORT), Handler)
    log(f"listening on {HOST}:{PORT} (warn <{WARN}%, critical <{CRIT}%)")
    server.serve_forever()


if __name__ == "__main__":
    main()
