#!/usr/bin/env python3
"""A minimal fake IPP printer: answers Get-Printer-Attributes and stores Print-Job documents.

    fake_ipp.py [--host 127.0.0.1] [--port 9631] [--out DIR]

Used to capture the PDF print path (reference print, PDF receipts) without CUPS. Point an IPP
printer of the QA DB at ipp://127.0.0.1:9631/printers/QA_PDF, make it the default for the one
call under test, then switch the default back. Each Print-Job is saved as DIR/ippNN-HHMMSS.bin
(a %PDF document); read it with `pdftotext -layout`.

The attribute set is the smallest one the server's IPP client accepted; when a new client version
asks for another attribute it fails with "printer attribute '<name>' is not available" - add it to
PRINTER_ATTRIBUTES below. That is a gap in this fake, not a product defect.
"""
import argparse
import http.server
import os
import struct
import time

STATE = {"jobs": 0, "out": ".", "uri": ""}


def attr(tag: int, name: str, value) -> bytes:
    v = value if isinstance(value, bytes) else value.encode()
    return bytes([tag]) + struct.pack(">H", len(name)) + name.encode() + struct.pack(">H", len(v)) + v


def extra_value(tag: int, value: str) -> bytes:
    v = value.encode()
    return bytes([tag]) + struct.pack(">H", 0) + struct.pack(">H", len(v)) + v


def i4(n: int) -> bytes:
    return struct.pack(">i", n)


def parse(body: bytes):
    """Returns (operation id, request id, document bytes after the end-of-attributes tag)."""
    op = struct.unpack(">H", body[2:4])[0]
    rid = struct.unpack(">i", body[4:8])[0]
    i = 8
    while i < len(body):
        tag = body[i]
        i += 1
        if tag == 0x03:
            return op, rid, body[i:]
        if tag < 0x10:
            continue
        name_len = struct.unpack(">H", body[i:i + 2])[0]
        i += 2 + name_len
        value_len = struct.unpack(">H", body[i:i + 2])[0]
        i += 2 + value_len
    return op, rid, b""


def printer_attributes(uri: str) -> bytes:
    return (
        b"\x04"
        + attr(0x45, "printer-uri-supported", uri)
        + attr(0x42, "printer-name", "QA_PDF")
        + attr(0x23, "printer-state", i4(3))
        + attr(0x44, "printer-state-reasons", "none")
        + attr(0x49, "document-format-supported", "application/pdf")
        + extra_value(0x49, "application/octet-stream")
        + attr(0x49, "document-format-default", "application/pdf")
        + attr(0x44, "ipp-versions-supported", "1.1")
        + extra_value(0x44, "2.0")
        + attr(0x23, "operations-supported", i4(2))
        + extra_value(0x23, "\x00\x00\x00\x0b")
        + attr(0x22, "printer-is-accepting-jobs", b"\x01")
        + attr(0x45, "device-uri", uri)
        + attr(0x41, "printer-make-and-model", "QA Fake")
        + attr(0x47, "charset-supported", "utf-8")
        + attr(0x47, "charset-configured", "utf-8")
        + attr(0x48, "natural-language-configured", "en")
        + attr(0x48, "generated-natural-language-supported", "en")
        + attr(0x44, "compression-supported", "none")
        + attr(0x22, "multiple-document-jobs-supported", b"\x00")
        + attr(0x44, "pdl-override-supported", "not-attempted")
        + attr(0x44, "uri-security-supported", "none")
        + attr(0x44, "uri-authentication-supported", "none")
    )


class Handler(http.server.BaseHTTPRequestHandler):
    def _body(self) -> bytes:
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            return self.rfile.read(n)
        body = b""  # chunked transfer encoding
        while True:
            size = int(self.rfile.readline().strip(), 16)
            if size == 0:
                self.rfile.readline()
                return body
            body += self.rfile.read(size)
            self.rfile.readline()

    def do_POST(self) -> None:  # noqa: N802 (http.server naming)
        op, rid, doc = parse(self._body())
        uri = STATE["uri"]
        resp = (b"\x02\x00\x00\x00" + i4(rid) + b"\x01"
                + attr(0x47, "attributes-charset", "utf-8") + attr(0x48, "attributes-natural-language", "en"))
        if op == 0x0002:  # Print-Job
            STATE["jobs"] += 1
            n = STATE["jobs"]
            path = os.path.join(STATE["out"], f"ipp{n:02d}-{time.strftime('%H%M%S')}.bin")
            with open(path, "wb") as f:
                f.write(doc)
            resp += (b"\x02" + attr(0x21, "job-id", i4(n)) + attr(0x45, "job-uri", f"{uri}/{n}")
                     + attr(0x23, "job-state", i4(9)) + attr(0x44, "job-state-reasons", "job-completed-successfully"))
            print(f"job {n}: {len(doc)} bytes -> {path}", flush=True)
        else:
            resp += printer_attributes(uri)
        resp += b"\x03"
        self.send_response(200)
        self.send_header("Content-Type", "application/ipp")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, fmt, *args) -> None:
        pass


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9631)
    p.add_argument("--out", default=".")
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    STATE["out"] = a.out
    STATE["uri"] = f"ipp://{a.host}:{a.port}/printers/QA_PDF"
    print(f"fake IPP printer at {STATE['uri']}, writing to {a.out}", flush=True)
    http.server.HTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
