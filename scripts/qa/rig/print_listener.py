#!/usr/bin/env python3
"""Captures ESC/POS print jobs: a raw TCP listener standing in for a NETWORK receipt printer.

    print_listener.py [--host 127.0.0.1] [--port 9107] [--out DIR]

Point a NETWORK printer at host:port (on the QA DB only), enable print-settings, print. Each
connection is one job, saved as DIR/jobNN-HHMMSS.bin. Decode with escpos_decode.py.
Stop it with Ctrl-C or `pkill -f print_listener.py`.
"""
import argparse
import os
import socket
import time


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9107)
    p.add_argument("--out", default=".", help="directory for the captured jobs")
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((a.host, a.port))
    srv.listen(5)
    print(f"listening on {a.host}:{a.port}, writing to {a.out}", flush=True)
    n = 0
    while True:
        conn, _ = srv.accept()
        n += 1
        data = b""
        conn.settimeout(3)
        try:
            while True:
                chunk = conn.recv(65536)
                if not chunk:
                    break
                data += chunk
        except OSError:
            pass  # the printer driver keeps the socket open; a 3 s silence ends the job
        conn.close()
        path = os.path.join(a.out, f"job{n:02d}-{time.strftime('%H%M%S')}.bin")
        with open(path, "wb") as f:
            f.write(data)
        print(f"job {n}: {len(data)} bytes -> {path}", flush=True)


if __name__ == "__main__":
    main()
