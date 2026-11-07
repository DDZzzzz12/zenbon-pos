"""Offline tests for release_checks.py: rule and parsing functions, synthetic fixtures only.

Run: python3 -m unittest discover scripts/qa/tests
"""

from __future__ import annotations

import contextlib
import datetime as dt
import io
import sys
import types
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import release_checks as rc  # noqa: E402


def quiet():
    return contextlib.redirect_stdout(io.StringIO())


class FakeApi:
    """Answers the few endpoints the checks call; anything else is a test bug."""

    def __init__(self, exports=None, history=None, overview=None, zbon=None):
        self.exports = exports or {}  # day -> zip bytes, or an int HTTP code
        self.history = history or []
        self.overview = overview or {}
        self.zbon = zbon
        self.calls = []

    def json(self, method, path, body=None, params=None, auth=True):
        self.calls.append((method, path, params))
        if path == "/api/v1/dsfinvk/validate":
            return {"isValid": True, "errors": [], "warnings": []}
        if path == "/api/z-bon/history":
            return self.history
        if path == "/api/statistics/sales/overview":
            return self.overview
        if path.startswith("/api/z-bon/"):
            return self.zbon
        raise AssertionError(f"unexpected call {method} {path}")

    def bytes(self, method, path, body=None, params=None):
        self.calls.append((method, path, body))
        answer = self.exports[body["startDate"]]
        if isinstance(answer, int):
            raise rc.ApiError(method, path, answer, b'{"detail":"found no data in range"}')
        return answer


def make_ctx(api, date="2026-10-03", extra_days=None):
    args = types.SimpleNamespace(date=date, dsfinvk_day=extra_days, zbon_window_days=14, zbon_number=None)
    return rc.Ctx(args, api, sql=None, res=rc.Results())


def csv_text(header, rows):
    lines = [";".join(header)] + [";".join(f'"{r.get(h, "")}"' for h in header) for r in rows]
    return "\n".join(lines) + "\n"


def export_zip(closing, transactions):
    """A minimal DSFinV-K export: one closing of 10.00 cash, its transactions."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("cashpointclosing.csv", csv_text(
            ["Z_NR", "Z_ERSTELLUNG", "Z_BUCHUNGSTAG", "Z_START_ID", "Z_ENDE_ID", "Z_SE_ZAHLUNGEN",
             "Z_SE_BARZAHLUNGEN"], [closing]))
        zf.writestr("transactions.csv", csv_text(
            ["Z_NR", "BON_ID", "BON_START", "BON_ENDE"], transactions))
        zf.writestr("businesscases.csv", csv_text(
            ["Z_NR", "GV_TYP", "GV_NAME", "UST_SCHLUESSEL", "Z_UMS_BRUTTO"],
            [{"Z_NR": closing["Z_NR"], "GV_TYP": "Umsatz", "GV_NAME": "", "UST_SCHLUESSEL": "1",
              "Z_UMS_BRUTTO": "10,00"}]))
        zf.writestr("payment.csv", csv_text(
            ["Z_NR", "ZAHLART_TYP", "Z_ZAHLART_BETRAG"],
            [{"Z_NR": closing["Z_NR"], "ZAHLART_TYP": "Bar", "Z_ZAHLART_BETRAG": "10,00"}]))
    return buf.getvalue()


class ParseServerTsTest(unittest.TestCase):
    def test_keeps_milliseconds(self):
        self.assertEqual(rc.parse_server_ts("2026-10-02T21:58:08.874"),
                         dt.datetime(2026, 10, 2, 21, 58, 8, 874000))

    def test_kotlin_drops_zero_seconds(self):
        self.assertEqual(rc.parse_server_ts("2026-10-02T21:58"), dt.datetime(2026, 10, 2, 21, 58))

    def test_nanoseconds_truncate_to_micros(self):
        self.assertEqual(rc.parse_server_ts("2026-10-02T21:58:08.123456789").microsecond, 123456)

    def test_rejects_garbage(self):
        with self.assertRaises(ValueError):
            rc.parse_server_ts("yesterday")


class ZbonStatsWindowTest(unittest.TestCase):
    def test_start_is_exclusive_at_full_precision(self):
        start, end = rc.zbon_stats_window("2026-10-02T21:58:08.874", "2026-10-03T01:33:12.5")
        self.assertEqual(start, "2026-10-02T21:58:08.874001")
        self.assertEqual(end, "2026-10-03T01:33:12.500000")

    def test_start_never_falls_before_previous_closing_end(self):
        # emu19: start truncated to 21:58:08 counted 11 receipts of the previous closing.
        start, _ = rc.zbon_stats_window("2026-10-02T21:58:08.874", "2026-10-03T01:33:12")
        self.assertGreater(dt.datetime.fromisoformat(start), dt.datetime(2026, 10, 2, 21, 58, 8, 874000))

    def test_whole_second_start(self):
        start, _ = rc.zbon_stats_window("2026-10-02T21:58", "2026-10-03T01:33")
        self.assertEqual(start, "2026-10-02T21:58:00.000001")

    def test_check_passes_window_to_overview(self):
        zbon = {"zbonNummer": 154, "startTime": "2026-10-02T21:58:08.874", "endTime": "2026-10-03T01:33:12.5",
                "totalRevenue": "429.41", "totalDiscounts": "26.59", "refundAmount": "39.40",
                "rebookStornoAmount": "0", "totalEmployeeTips": "6.00", "adminTip": "4.00"}
        overview = {"totalRevenue": "429.41", "totalDiscounts": "26.59", "refundRevenue": "39.40",
                    "totalTips": "10.00"}
        api = FakeApi(overview=overview, zbon=zbon)
        ctx = make_ctx(api)
        ctx._zbon_summary, ctx._zbon_summary_loaded = {"id": 166, "zbonNummer": 154}, True
        with quiet():
            rc.check_stats_vs_zbon(ctx)
        params = next(p for m, path, p in api.calls if path == "/api/statistics/sales/overview")
        self.assertEqual(params["start_date"], "2026-10-02T21:58:08.874001")
        self.assertEqual([s for _, s, _ in ctx.res.rows], [rc.PASS] * 4)


class ExpectedBuchungstagTest(unittest.TestCase):
    def test_same_day_closing_is_empty(self):
        closing = {"Z_ERSTELLUNG": "2026-10-02T23:10:00"}
        txs = [{"BON_ID": "B-1", "BON_START": "2026-10-02T18:00:00", "BON_ENDE": "2026-10-02T18:05:00"}]
        self.assertEqual(rc.expected_buchungstag(closing, txs), "")

    def test_after_midnight_closing_carries_previous_day(self):
        closing = {"Z_ERSTELLUNG": "2026-10-03T01:33:00"}
        txs = [
            {"BON_ID": "B-2", "BON_START": "2026-10-03T00:20:00", "BON_ENDE": "2026-10-03T00:25:00"},
            {"BON_ID": "B-1", "BON_START": "2026-10-02T22:00:00", "BON_ENDE": "2026-10-02T22:10:00"},
        ]
        self.assertEqual(rc.expected_buchungstag(closing, txs), "2026-10-02")

    def test_first_bon_decides_not_the_previous_closing(self):
        # A closing made the next evening after a closed day: its own first Bon is today.
        closing = {"Z_ERSTELLUNG": "2026-10-05T22:00:00"}
        txs = [{"BON_ID": "B-9", "BON_START": "2026-10-05T11:00:00", "BON_ENDE": "2026-10-05T11:30:00"}]
        self.assertEqual(rc.expected_buchungstag(closing, txs), "")

    def test_no_transactions_claims_nothing(self):
        self.assertEqual(rc.expected_buchungstag({"Z_ERSTELLUNG": "2026-10-03T01:00:00"}, []), "")


class DsfinvkCheckTest(unittest.TestCase):
    CLOSING = {"Z_NR": "154", "Z_ERSTELLUNG": "2026-10-03T01:33:00", "Z_START_ID": "B-1", "Z_ENDE_ID": "B-2",
               "Z_SE_ZAHLUNGEN": "10,00", "Z_SE_BARZAHLUNGEN": "10,00"}
    TXS = [{"Z_NR": "154", "BON_ID": "B-1", "BON_START": "2026-10-02T22:00:00", "BON_ENDE": "2026-10-02T22:10:00"},
           {"Z_NR": "154", "BON_ID": "B-2", "BON_START": "2026-10-03T00:20:00", "BON_ENDE": "2026-10-03T00:25:00"}]
    HISTORY = [{"zbonNummer": 154, "startTime": "2026-10-02T21:58:08.874"}]

    def run_check(self, closing, exports, extra=None):
        api = FakeApi(exports={"2026-10-03": export_zip(closing, self.TXS), **exports}, history=self.HISTORY)
        ctx = make_ctx(api, extra_days=extra)
        with quiet():
            rc.check_dsfinvk(ctx)
        return {c: s for c, s, _ in ctx.res.rows}

    def test_after_midnight_buchungstag_passes(self):
        rows = self.run_check(dict(self.CLOSING, Z_BUCHUNGSTAG="2026-10-02"), {})
        self.assertEqual(rows["dsfinvk[2026-10-03]:Z154:buchungstag"], rc.PASS)
        self.assertNotIn(rc.FAIL, rows.values())

    def test_wrong_buchungstag_fails(self):
        rows = self.run_check(dict(self.CLOSING, Z_BUCHUNGSTAG=""), {})
        self.assertEqual(rows["dsfinvk[2026-10-03]:Z154:buchungstag"], rc.FAIL)

    def test_day_without_closing_skips_and_continues(self):
        rows = self.run_check(dict(self.CLOSING, Z_BUCHUNGSTAG="2026-10-02"),
                              {"2026-10-02": 404, "2026-09-27": export_zip(
                                  dict(self.CLOSING, Z_BUCHUNGSTAG="2026-10-02"), self.TXS)},
                              extra=["2026-10-02", "2026-09-27"])
        self.assertEqual(rows["dsfinvk[2026-10-02]"], rc.SKIP)
        self.assertIn("dsfinvk[2026-09-27]:validate", rows)  # the next day still ran
        self.assertNotIn(rc.FAIL, rows.values())

    def test_other_http_error_fails_that_day_only(self):
        rows = self.run_check(dict(self.CLOSING, Z_BUCHUNGSTAG="2026-10-02"), {"2026-10-02": 500},
                              extra=["2026-10-02"])
        self.assertEqual(rows["dsfinvk[2026-10-02]"], rc.FAIL)
        self.assertEqual(rows["dsfinvk[2026-10-03]:Z154:sum"], rc.PASS)


class FakeSql:
    """Answers the two counts check_zbon_bills asks; `bill where zbon_id = … and is_training` first."""

    available = True

    def __init__(self, total, training):
        self.total, self.training = total, training

    def scalar(self, query):
        return str(self.training if "is_training" in query else self.total)


class ZbonBillsCheckTest(unittest.TestCase):
    """emu20 NOTE-t: the receipt list must carry the closing's Schulungsbelege, like bill.zbon_id and
    DSFinV-K's B-* rows do; before, the script compared a training-free API list and FAILed."""

    SUMMARY = {"id": 166, "zbonNummer": 154, "startTime": "2026-10-02T21:58:08.874",
               "endTime": "2026-10-03T01:33:00.000"}
    TXS = [{"Z_NR": "154", "BON_ID": f"B-{i}", "BON_START": "2026-10-02T22:00:00",
            "BON_ENDE": "2026-10-02T22:10:00"} for i in range(1, 4)]
    CLOSING = {"Z_NR": "154", "Z_ERSTELLUNG": "2026-10-03T01:33:00", "Z_BUCHUNGSTAG": "2026-10-02",
               "Z_START_ID": "B-1", "Z_ENDE_ID": "B-3", "Z_SE_ZAHLUNGEN": "10,00", "Z_SE_BARZAHLUNGEN": "10,00"}

    class Api(FakeApi):
        def __init__(self, bills, **kw):
            super().__init__(**kw)
            self.bills = bills

        def json(self, method, path, body=None, params=None, auth=True):
            if path == "/api/v1/bills/search":
                self.calls.append((method, path, params))
                visible = [b for b in self.bills if params.get("includeTraining") == "true" or not b.get("isTraining")]
                return visible[params["offset"]: params["offset"] + params["limit"]]
            return super().json(method, path, body, params, auth)

    def run_check(self, bills, sql):
        api = self.Api(bills, exports={"2026-10-03": export_zip(self.CLOSING, self.TXS)},
                       history=[self.SUMMARY])
        ctx = rc.Ctx(types.SimpleNamespace(date="2026-10-03", dsfinvk_day=None, zbon_window_days=14,
                                           zbon_number=None), api, sql=sql, res=rc.Results())
        with quiet():
            rc.check_zbon_bills(ctx)
        searches = [p for _, path, p in api.calls if path == "/api/v1/bills/search"]
        return {c: s for c, s, _ in ctx.res.rows}, searches

    BILLS = [{"billId": 1}, {"billId": 2, "isTraining": True}, {"billId": 3}]

    def test_training_receipts_are_requested_and_counted(self):
        rows, searches = self.run_check(self.BILLS, FakeSql(total=3, training=1))
        self.assertTrue(all(p.get("includeTraining") == "true" for p in searches))
        self.assertEqual(rows["zbon-bills[#154]:vs-db"], rc.PASS)
        self.assertEqual(rows["zbon-bills[#154]:vs-dsfinvk"], rc.PASS)
        self.assertEqual(rows["zbon-bills[#154]:training-flagged"], rc.PASS)

    def test_a_server_that_ignores_include_training_fails(self):
        # An older server drops the training receipt: the counts no longer meet.
        rows, _ = self.run_check([b for b in self.BILLS if not b.get("isTraining")], FakeSql(total=3, training=1))
        self.assertEqual(rows["zbon-bills[#154]:vs-db"], rc.FAIL)
        self.assertEqual(rows["zbon-bills[#154]:training-flagged"], rc.FAIL)

    def test_the_window_keeps_sub_second_precision(self):
        # sk1 (2026-10-09): api_smoke.py billed and closed within one second; an end time cut to
        # 01:33:00 lost every receipt closed at 01:33:00.xxx and the list came back empty.
        summary = dict(self.SUMMARY, endTime="2026-10-03T01:33:00.627788")
        self.assertEqual(rc.zbon_window(summary), ("2026-10-02T21:58:08.874", "2026-10-03T01:33:00.627788"))
        _, searches = self.run_check(self.BILLS, FakeSql(total=3, training=1))
        self.assertEqual(searches[0]["endDate"], self.SUMMARY["endTime"])


class BalanceTextTest(unittest.TestCase):
    """KB-07: the balance must be judged as the server wrote it, scale included."""

    def test_plain_text_keeps_its_scale(self):
        self.assertEqual(rc.balance_text(b"37.50"), "37.50")

    def test_json_string_and_object(self):
        self.assertEqual(rc.balance_text(b'"5524.72"'), "5524.72")
        self.assertEqual(rc.balance_text(b'{"balance":"5524.72","currency":"EUR"}'), "5524.72")

    def test_locale_formatting_survives_to_fail_the_check(self):
        self.assertEqual(rc.balance_text(b"5.524,72"), "5.524,72")


class KassenbuchVerdictTest(unittest.TestCase):
    def test_non_fiscal_rows_are_ignored(self):
        status, detail = rc.kassenbuch_unclosed_verdict([["false", "2"]])
        self.assertEqual(status, rc.PASS)
        self.assertIn("2 on non-fiscal books", detail)

    def test_fiscal_rows_warn(self):
        status, _ = rc.kassenbuch_unclosed_verdict([["true", "1"], ["false", "2"]])
        self.assertEqual(status, rc.WARN)

    def test_psql_boolean_text(self):
        self.assertEqual(rc.kassenbuch_unclosed_verdict([["t", "3"]])[0], rc.WARN)
        self.assertEqual(rc.kassenbuch_unclosed_verdict([])[0], rc.PASS)


class MoneyTest(unittest.TestCase):
    def test_comma_and_dot(self):
        self.assertEqual(rc.money("12,30"), rc.money("12.30"))
        self.assertEqual(rc.money(""), 0)


if __name__ == "__main__":
    unittest.main()
