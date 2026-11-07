#!/usr/bin/env python3
"""API smoke pass on a seeded fixture: the server halves of SMK-01..04, a Z-Bon, then release_checks.py.

    api_smoke.py [--no-checks] [--expect-release X.Y.Z]

Needs `source $QA_DIR/rig.env; source $QA_DIR/creds.env` after `seed_fixture.py --pair api`.
Run it ONCE per fixture: it books sales on T1-T3 and closes a Z-Bon (a second run books a second day's
worth on top, which is fine, but the expected Z-Bon total below then no longer applies).

Every line printed is `<STATUS> <case id> <what>`; the same table is written to $QA_DIR/api-smoke.md.
Exit 0 = everything PASS (and release_checks.py exited 0), 1 = something FAILED, 2 = setup problem.

What it books (fixture F, catalogue section 0.3):
  T1  ordered by K001 on the till, Espresso + Suppe zum Mitnehmen, BAR exact 10,60   -> SMK-01 (VAT A 2,50 / B 8,10)
  T2  station order Cola + Schnitzel, EC-KARTE 16,00 + 1,00 tip     -> SMK-03, SMK-04 (station refusals)
  T3  ordered by K001, Tagesmenü + Wein Flasche, BAR 26,90 (the 16,90 float-artefact amount)
  Round-robin hands every new table to the rotation pool (K003 alone); each table is billed by its owner.
  Z-Bon by M001 with the counted cash = the expected cash          -> SMK-02 (Gesamtumsatz 53,50)
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

from qalib import Api, ApiError, money, qa_dir

HERE = Path(__file__).resolve().parent
RESULTS: list[tuple[str, str, str]] = []


def record(status: str, case: str, text: str) -> None:
    RESULTS.append((status, case, text))
    print(f"{status:<5} {case:<7} {text}", flush=True)


def check(cond: bool, case: str, text: str, seen: object = "") -> bool:
    record("PASS" if cond else "FAIL", case, text if cond else f"{text} - seen: {str(seen)[:300]}")
    return cond


def env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        print(f"setup: {name} is not set - source $QA_DIR/rig.env and $QA_DIR/creds.env "
              f"(seed_fixture.py --pair api)", file=sys.stderr)
        sys.exit(2)
    return value


class Smoke:
    def __init__(self) -> None:
        self.dir = qa_dir()
        self.fx = json.loads((self.dir / "fixture.json").read_text())
        till = env("QA_TILL_DEVICE")
        # One login per employee on the paired till. Round-robin hands every new table to the rotation
        # pool (K003), and cross-employee billing is off, so a table is billed by its current owner.
        self.staff = {key: Api(env(f"QA_{key}_USER"), env(f"QA_{key}_PASSWORD"), device=till).login()
                      for key in ("K001", "K002", "K003", "M001")}
        self.k001, self.m001 = self.staff["K001"], self.staff["M001"]
        self.by_number = {e["employeeNumber"]: key for key, e in self.fx["employees"].items()}
        self.pay = {name: pt["id"] for name, pt in self.fx["paymentTypes"].items()}

    # --- helpers ------------------------------------------------------------------------------------
    def item(self, name: str, take_away: bool = False) -> dict:
        p = self.fx["products"][name]
        return {"productId": p["id"], "unitPrice": p["price"], "quantity": 1, "discount": 1.0,
                "customPrice": False, "takeAway": take_away, "sideNotes": None, "extras": None, "selections": None}

    def order(self, api: Api, table: str, items: list[dict]) -> None:
        api.call("POST", "/api/orders", {"tableInfoId": 0, "tableId": self.fx["tables"][table],
                                         "forEmployeeNumber": None, "orderItems": items}, expect=(201,))

    def bill_table(self, table: str, payment: str, tip: str = "0.00") -> dict:
        """Bills every open item of the table as the employee who currently owns it."""
        _, t = self.m001.call("GET", f"/api/tables/{self.fx['tables'][table]}", expect=(200,))
        owner = self.by_number.get(t.get("currentEmployeeNumber"))
        if owner is None:
            raise ApiError(f"{table} is owned by {t.get('currentEmployeeNumber')!r}, not a fixture employee")
        api = self.staff[owner]
        sid = t["currentSessionId"]
        _, items = api.call("GET", f"/api/items/session/{sid}?status=OPEN", expect=(200,))
        ids = [i for it in items for i in it["orderItemIds"]]
        total = sum((money(it["finalPrice"]) for it in items), Decimal("0.00"))
        body = {"tableSessionId": sid, "orderItems": ids, "billAmount": str(total), "tips": tip,
                "payments": [{"amount": str(total + money(tip)), "paymentTypeId": self.pay[payment]}],
                "voucherRedeem": [], "promotions": []}
        _, bill = api.call("POST", "/api/v1/bills", body, expect=(201,))
        bill["_billedBy"] = owner
        return bill

    def signed(self, bill: dict) -> bool:
        note = bill.get("tseSigningNote") or {}
        return bool(bill.get("tseQrCodeContent")) and not note.get("pending")

    # --- cases --------------------------------------------------------------------------------------
    def smk01(self) -> Decimal:
        self.order(self.k001, "T1", [self.item("Espresso"), self.item("Suppe", take_away=True)])
        bill = self.bill_table("T1", "BAR")
        check(money(bill["billAmount"]) == Decimal("10.60"), "SMK-01", "bill total 10,60", bill.get("billAmount"))
        check(self.signed(bill), "SMK-01", f"{bill.get('billNumber')} by {bill['_billedBy']} TSE-signed at once (no queueing note)",
              bill.get("tseSigningNote"))
        qr = bill.get("tseQrCodeContent") or ""
        check("Beleg^2.50_8.10_0.00_0.00_0.00^10.60:Bar" in qr, "SMK-01",
              "process data Beleg^2.50_8.10_0.00_0.00_0.00^10.60:Bar", qr)
        vat = {round(v["vatRate"] * (100 if v["vatRate"] < 1 else 1)): v for v in bill.get("vatDetails", [])}
        a, b = vat.get(19), vat.get(7)
        check(a is not None and (money(a["netAmount"]), money(a["vatAmount"]), money(a["totalAmount"]))
              == (Decimal("2.10"), Decimal("0.40"), Decimal("2.50")), "SMK-01", "VAT A 19 %: 2,10 + 0,40 = 2,50", a)
        check(b is not None and (money(b["netAmount"]), money(b["vatAmount"]), money(b["totalAmount"]))
              == (Decimal("7.57"), Decimal("0.53"), Decimal("8.10")), "SMK-01", "VAT B 7 % (to go): 7,57 + 0,53 = 8,10",
              b)
        _, again = self.k001.call("GET", f"/api/v1/bills/{bill['billId']}", expect=(200,))
        check(again.get("tseQrCodeContent") == qr, "SMK-01", "GET /api/v1/bills/{id} returns the same TSE data",
              again.get("tseQrCodeContent"))
        return money(bill["billAmount"])

    def station(self) -> Api:
        st = Api(device=env("QA_STATION_DEVICE"))
        st.extra_headers["X-Device-Token"] = env("QA_STATION_TOKEN")
        _, session = st.call("POST", "/api/v1/terminals/session", expect=(200,))
        st.extra_headers.pop("X-Device-Token")
        st.token = session["accessToken"]
        return st

    def smk03(self, st: Api) -> None:
        self.order(st, "T2", [self.item("Cola"), self.item("Schnitzel")])
        _, t = self.m001.call("GET", f"/api/tables/{self.fx['tables']['T2']}", expect=(200,))
        k003 = self.fx["employees"]["K003"]["employeeNumber"]
        check(t.get("hasActiveSession") is True, "SMK-03", "station order opened T2", t.get("tableStatus"))
        check(t.get("currentEmployeeNumber") == k003, "SMK-03", f"rotation picked {k003} (the only pool member)",
              t.get("currentEmployeeNumber"))
        check(money(t.get("openAmount") or 0) == Decimal("16.00"), "SMK-03", "T2 open amount 16,00 on the till",
              t.get("openAmount"))

    def smk04(self, st: Api) -> None:
        probes = [
            ("POST", "/api/v1/bills", {"tableSessionId": 0, "orderItems": [], "billAmount": "0.00", "tips": "0.00",
                                       "payments": []}, "BILL_WRITE"),
            ("GET", "/api/v1/bills", None, "BILL_READ"),
            ("POST", "/api/vouchers", {}, "VOUCHER_WRITE"),
        ]
        for method, path, body, capability in probes:
            code, resp = st.call(method, path, body)
            text = json.dumps(resp) if not isinstance(resp, str) else resp
            check(code == 403 and capability in text, "SMK-04", f"station {method} {path} -> 403 {capability}",
                  f"{code} {text[:200]}")

    def bill_t2(self) -> Decimal:
        bill = self.bill_table("T2", "EC-KARTE", tip="1.00")
        check(money(bill["billAmount"]) == Decimal("16.00") and money(bill.get("tips", 0)) == Decimal("1.00"),
              "SMK-03", f"{bill['_billedBy']} bills T2 on the till: 16,00 EC + 1,00 tip", f"{bill.get('billAmount')} tip {bill.get('tips')}")
        check(self.signed(bill), "SMK-03", f"{bill.get('billNumber')} TSE-signed", bill.get("tseSigningNote"))
        return money(bill["billAmount"])

    def bill_t3(self) -> Decimal:
        self.order(self.k001, "T3", [self.item("Tagesmenü"), self.item("Wein Flasche")])
        bill = self.bill_table("T3", "BAR")
        check(money(bill["billAmount"]) == Decimal("26.90"), "SMK-01", "T3 Tagesmenü + Wein = 26,90 exactly",
              bill.get("billAmount"))
        return money(bill["billAmount"])

    def smk02(self, expected_revenue: Decimal) -> int | None:
        _, exp = self.m001.call("GET", "/api/v1/kassenbuch/cash-count/expected", expect=(200,))
        check(money(exp["expectedCash"]) == money(exp["bookBalance"]) + money(exp["unbookedCashTakings"]),
              "SMK-02", "expected cash = book balance + unbooked cash takings", exp)
        check(money(exp["unbookedCashTakings"]) == Decimal("37.50"), "SMK-02",
              "unbooked cash takings 37,50 (10,60 + 26,90 BAR)", exp.get("unbookedCashTakings"))
        _, z = self.m001.call("POST", "/api/z-bon", {"countedAmount": str(money(exp["expectedCash"]))}, expect=(201,))
        check(money(z.get("totalRevenue", 0)) == expected_revenue, "SMK-02",
              f"Z-Bon #{z.get('zbonNummer')} Gesamtumsatz {expected_revenue}", z.get("totalRevenue"))
        _, again = self.m001.call("GET", f"/api/z-bon/{z['id']}", expect=(200,))
        check(again.get("zbonNummer") == z.get("zbonNummer"), "SMK-02", "GET /api/z-bon/{id} returns the closing",
              again.get("zbonNummer"))
        return z.get("id")

    def run(self) -> None:
        total = self.smk01()
        st = self.station()
        self.smk03(st)
        self.smk04(st)
        total += self.bill_t2()
        total += self.bill_t3()
        self.smk02(total)


def run_release_checks(expect_release: str | None) -> int:
    day = dt.date.today().isoformat()
    cmd = [sys.executable, str(HERE / "release_checks.py"), "--base", os.environ.get("QA_BASE", "http://localhost:8080"),
           "--date", day]
    if os.environ.get("QA_PG_PORT"):
        cmd += ["--pg-port", os.environ["QA_PG_PORT"]]
    if expect_release:
        cmd += ["--expect-release", expect_release]
    print(f"\n== release_checks.py --date {day}" + (f" --pg-port {os.environ['QA_PG_PORT']}" if os.environ.get("QA_PG_PORT") else ""),
          flush=True)
    rc = subprocess.run(cmd, cwd=HERE).returncode
    record("PASS" if rc == 0 else "FAIL", "checks", f"release_checks.py exit {rc}")
    return rc


def write_report() -> None:
    path = qa_dir() / "api-smoke.md"
    lines = ["| Status | Case | Check |", "|---|---|---|"] + [f"| {s} | {c} | {t.replace('|', '/')} |" for s, c, t in RESULTS]
    path.write_text("\n".join(lines) + "\n")
    fails = sum(1 for s, _, _ in RESULTS if s != "PASS")
    print(f"\napi_smoke: {len(RESULTS) - fails} PASS / {fails} FAIL -> {path}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--no-checks", action="store_true", help="skip release_checks.py")
    p.add_argument("--expect-release", help="passed to release_checks.py (e.g. 1.1.1)")
    args = p.parse_args()
    try:
        Smoke().run()
    except ApiError as exc:
        record("FAIL", "smoke", f"aborted: {exc}")
    if not args.no_checks and not any(c == "smoke" for _, c, _ in RESULTS):
        run_release_checks(args.expect_release)
    write_report()
    sys.exit(0 if all(s == "PASS" for s, _, _ in RESULTS) else 1)


if __name__ == "__main__":
    main()
