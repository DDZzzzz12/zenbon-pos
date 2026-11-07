"""Shared helpers for the QA scripts (seed_fixture.py, api_smoke.py). Standard library only.

Never prints a credential: logins take the password from the caller and only the HTTP status is
reported on failure.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from decimal import Decimal
from pathlib import Path

BASE = os.environ.get("QA_BASE", "http://localhost:8080")


class ApiError(Exception):
    pass


class Api:
    """One logged-in principal on one device. `device` becomes X-Device-ID (and the JWT deviceId)."""

    def __init__(self, user: str | None = None, password: str | None = None, device: str = "qa-admin",
                 base: str = BASE):
        self.user, self.password, self.device, self.base = user, password, device, base
        self.token: str | None = None
        self.extra_headers: dict[str, str] = {}

    def call(self, method: str, path: str, body=None, expect: tuple[int, ...] | None = None, raw: bool = False):
        headers = {"Content-Type": "application/json", "X-Device-ID": self.device, **self.extra_headers}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                code, payload = resp.status, resp.read()
        except urllib.error.HTTPError as exc:
            code, payload = exc.code, exc.read()
        except urllib.error.URLError as exc:
            raise ApiError(f"{method} {path}: server unreachable ({exc.reason})") from exc
        if code == 401 and self.user and self.token:
            self.login()
            return self.call(method, path, body, expect, raw)
        if raw:
            result = payload
        else:
            try:
                result = json.loads(payload) if payload else None
            except ValueError:
                result = payload.decode(errors="replace")[:500]
        if expect is not None and code not in expect:
            raise ApiError(f"{method} {path} -> HTTP {code} (expected {expect}): {str(result)[:400]}")
        return code, result

    def login(self) -> "Api":
        self.token = None
        code, body = self.call("POST", "/api/auth/login", {"identifier": self.user, "secret": self.password})
        if code != 200:
            raise ApiError(f"login of {self.user} on device {self.device} failed: HTTP {code}")
        self.token = body["token"]["accessToken"]
        return self


def money(value) -> Decimal:
    return Decimal(str(value)).quantize(Decimal("0.01"))


def load_env_file(path: Path) -> dict[str, str]:
    """Reads `export KEY=VALUE` / `KEY=VALUE` lines (values may be single-quoted)."""
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.removeprefix("export ").split("=", 1)
        env[key.strip()] = value.strip().strip("'\"")
    return env


def qa_dir() -> Path:
    d = os.environ.get("QA_DIR")
    if not d:
        sys.exit("QA_DIR is not set - `source $QA_DIR/rig.env` (written by scripts/qa/rig.sh up)")
    return Path(d)
