#!/usr/bin/env python3
"""Proves a public deploy dir carries no Swissbit TSE runtime (CLAUDE.md "Release Flow").

    leak_scan.py <pos-server/build/deploy>

The same two checks as release.yml's "Verify public artifacts carry no TSE runtime" step, plus the
"no native/ folder" rule: SDK binaries by file name, and com/secureflashcard/ entries inside every
jar or zip, nested jars included. Prints "clean: ..." and exits 0, or prints the hits and exits 1.
Scanning nothing is a failure: a check that found no archive proved nothing (gate.sh once reported
PASS after an `xargs` error had skipped the whole scan).
"""

from __future__ import annotations

import io
import os
import re
import sys
import zipfile

SDK = re.compile(r"(^|/)(WormAPI\.jar|libWormAPI[^/]*\.so|WormAPI[^/]*\.dll|libWormAPIJni[^/]*\.so|WormAPIJni[^/]*\.dll)$")


def scan(root: str) -> tuple[list[str], int]:
    hits: list[str] = []
    archives = 0

    def scan_zip(data: bytes, label: str, depth: int = 0) -> None:
        nonlocal archives
        archives += 1
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            hits.append(f"unreadable archive {label}")
            return
        for name in z.namelist():
            if "com/secureflashcard/" in name or SDK.search(name):
                hits.append(f"{label}!{name}")
            elif name.endswith((".jar", ".zip")) and depth < 3:
                scan_zip(z.read(name), f"{label}!{name}", depth + 1)

    if os.path.exists(os.path.join(root, "native")):
        hits.append("deploy/native/ exists")
    for d, _, files in os.walk(root):
        for f in files:
            path = os.path.join(d, f)
            rel = os.path.relpath(path, root)
            if SDK.search(rel):
                hits.append(rel)
            if f.endswith((".jar", ".zip")):
                with open(path, "rb") as fh:
                    scan_zip(fh.read(), rel)
    return hits, archives


def main() -> None:
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print(f"FAIL: deploy dir {sys.argv[1:] or '?'} does not exist")
        sys.exit(1)
    hits, archives = scan(sys.argv[1])
    if hits:
        print(f"FAIL: TSE runtime in the public package: {'; '.join(hits[:5])}")
        sys.exit(1)
    if archives == 0:
        print("FAIL: no jar or zip found to scan - the check proved nothing")
        sys.exit(1)
    print(f"clean: {archives} archive(s) scanned, no SDK binary, no com/secureflashcard/, no native/")


if __name__ == "__main__":
    main()
