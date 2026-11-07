#!/usr/bin/env python3
"""Tiny authenticated API client for driving a QA server from the shell.

    qa_api.py [-u USER_ENV] [-d DEVICE_ID] [-o OUTFILE] METHOD PATH [JSON_BODY]

Prints the response body followed by "[HTTP <code>]". The access token is cached per user+device in
$TMPDIR and refreshed by re-login on a 401 (access tokens live 15 minutes).

Credentials never appear on the command line or in a script: -u names an ENV PREFIX. With the default
prefix QA the login is $QA_USER / $QA_PASSWORD; `-u QA_MGR` uses $QA_MGR_USER / $QA_MGR_PASSWORD, and so
on. Keep the test accounts' passwords in an untracked env file in the scratch dir and `source` it.

-d sets X-Device-ID. Calls that book (orders, bills, Zahlartwechsel) must carry the device id of a
PAIRED terminal or they answer 404 "Terminal not found" / 409 DEVICE_NOT_PAIRED.
BASE comes from $QA_BASE (default http://localhost:8080).

Examples:
  qa_api.py GET /api/setup/status
  qa_api.py -u QA_MGR -d <till-device-id> POST /api/v1/bills/42/rebook '{"payments":[...],"tips":"2.00"}'
  qa_api.py -o zbon.pdf GET /api/z-bon/166/pdf
"""
import hashlib
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request

BASE = os.environ.get("QA_BASE", "http://localhost:8080")


def main() -> None:
    args = sys.argv[1:]
    prefix, device, outfile = "QA", "qa-cli", None
    while args and args[0].startswith("-"):
        flag = args.pop(0)
        if flag == "-u":
            prefix = args.pop(0)
        elif flag == "-d":
            device = args.pop(0)
        elif flag == "-o":
            outfile = args.pop(0)
        else:
            print(__doc__)
            sys.exit(2)
    if len(args) < 2:
        print(__doc__)
        sys.exit(2)
    method, path = args[0], args[1]
    body = args[2] if len(args) > 2 else None
    user = os.environ.get(f"{prefix}_USER")
    password = os.environ.get(f"{prefix}_PASSWORD")
    if not user or not password:
        sys.exit(f"set {prefix}_USER and {prefix}_PASSWORD in the environment")
    key = hashlib.sha256(f"{BASE}|{user}|{device}".encode()).hexdigest()[:16]
    cache = os.path.join(tempfile.gettempdir(), f"qa-api-token-{key}")

    def request(m, p, data, token=None):
        headers = {"Content-Type": "application/json", "X-Device-ID": device}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(BASE + p, data=data.encode() if data is not None else None,
                                     method=m, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read()

    def login():
        code, raw = request("POST", "/api/auth/login", json.dumps({"identifier": user, "secret": password}))
        if code != 200:
            sys.exit(f"login failed: HTTP {code} {raw[:200]!r}")
        token = json.loads(raw)["token"]["accessToken"]
        with open(cache, "w") as f:
            f.write(token)
        os.chmod(cache, 0o600)
        return token

    token = open(cache).read() if os.path.exists(cache) else login()
    code, raw = request(method, path, body, token)
    if code == 401:
        code, raw = request(method, path, body, login())
    if outfile:
        with open(outfile, "wb") as f:
            f.write(raw)
        raw = f"<{len(raw)} bytes -> {outfile}>".encode()
    sys.stdout.write(raw.decode(errors="replace") + f"\n[HTTP {code}]\n")


if __name__ == "__main__":
    main()
