#!/usr/bin/env python3
"""Turns a captured ESC/POS job into readable text (crude: drops control bytes, keeps the lines).

    escpos_decode.py job01-120000.bin [more.bin ...]

The receipt printers use code page 858, so umlauts and the euro sign decode correctly. The QR code
payload is binary and comes out as noise; compare the printed totals with the bill's
tseQrCodeContent from the API instead. Typical assertion: SUMME / tender / GESAMT on paper equal
the process data in the QR (Beleg^<A>_<B>_<C>_<D>_<E>^<total>:<Bar|Unbar>).
"""
import re
import sys


def strip_qr_blocks(raw: bytes) -> bytes:
    """Removes every `GS ( k pL pH <data>` block (QR code commands) including its payload."""
    out, i = bytearray(), 0
    while i < len(raw):
        if raw[i:i + 3] == b"\x1d(k" and i + 5 <= len(raw):
            length = raw[i + 3] + 256 * raw[i + 4]
            i += 5 + length
            continue
        out.append(raw[i])
        i += 1
    return bytes(out)


def decode(raw: bytes) -> str:
    text = strip_qr_blocks(raw).decode("cp858", errors="replace")
    # Control bytes go; the occasional printable parameter byte of an ESC/GS command may remain.
    return re.sub(r"[\x00-\x09\x0b-\x1f\x7f]", "", text)


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    for path in sys.argv[1:]:
        with open(path, "rb") as f:
            if len(sys.argv) > 2:
                print(f"===== {path}")
            print(decode(f.read()))


if __name__ == "__main__":
    main()
