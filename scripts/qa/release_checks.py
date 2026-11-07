#!/usr/bin/env python3
"""Release QA checks that need no UI: run them against an isolated QA server before tagging.

These are the API/SQL-verifiable cases of docs/testing/release-qa-catalogue.md, turned into
assertions with exact rules (for example "sum of businesscases = Z_SE_ZAHLUNGEN = sum of payment").
Each check prints PASS / FAIL / WARN / SKIP; the exit code is 1 when anything FAILs, 2 when the
server cannot be reached or the login fails, 0 otherwise.

Credentials come from the environment only:
  QA_USER, QA_PASSWORD     an owner/admin login of the QA server (needs Z-Bon, DSFinV-K, backup,
                           statistics and bill read capabilities; the owner account has them)
  QA_DEVICE_ID             optional X-Device-ID for the login (default: qa-release-checks)
  QA_PG_USER, QA_PG_PASSWORD, QA_PG_HOST, QA_PG_DB   optional, for the SQL checks (--pg-port)
  QA_PGJDBC_JAR            optional pgjdbc jar, used with rig/PgQuery.java when psql is absent

The checks only read fiscal data. Two calls have side effects outside the fiscal tables: the
DSFinV-K export may write an export-log row, and fetching a Z-Bon PDF whose stored file is missing
regenerates it into the server's document directory. Run it against a QA rig, never against a
shop's live server.

Examples:
  QA_USER=admin QA_PASSWORD=... scripts/qa/release_checks.py --base http://localhost:8080 --date 2026-10-02
  ... --date 2026-10-02 --dsfinvk-day 2026-09-27 --pg-port 5493 --expect-release 1.1.0
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from decimal import Decimal, InvalidOperation
from pathlib import Path

PASS, FAIL, WARN, SKIP = "PASS", "FAIL", "WARN", "SKIP"
CENT = Decimal("0.01")
ALL_CHECKS = [
    "setup",
    "release-notes",
    "restored-paths",
    "restore-sql",
    "dsfinvk",
    "zbon-totals",
    "zbon-pdf-stable",
    "stats-vs-zbon",
    "zbon-bills",
    "zbon-sessions",
    "session-receipts",
    "kassenbuch-balance",
]


class ServerUnreachable(Exception):
    pass


class LoginFailed(Exception):
    pass


# --------------------------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------------------------


def money(value) -> Decimal:
    """Parses a money string from the API ("12.30") or DSFinV-K ("12,30") into a Decimal."""
    if value is None or value == "":
        return Decimal("0")
    text = str(value).strip().replace(",", ".")
    try:
        return Decimal(text)
    except InvalidOperation as exc:
        raise ValueError(f"not a decimal: {value!r}") from exc


def same(a: Decimal, b: Decimal) -> bool:
    return abs(a - b) < CENT / 2


def fmt(d: Decimal) -> str:
    return str(d.quantize(CENT))


_SERVER_TS = re.compile(r"(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?")


def parse_server_ts(text: str) -> dt.datetime:
    """Parses a server LocalDateTime string at full precision.

    Kotlin's LocalDateTime.toString() drops zero seconds ("21:58") and prints 3, 6 or 9 fraction
    digits; Postgres keeps microseconds, so nanoseconds are truncated to microseconds.
    """
    m = _SERVER_TS.match(text.strip())
    if not m:
        raise ValueError(f"not a server timestamp: {text!r}")
    day, hh, mm, ss, frac = m.groups()
    micros = int((frac or "0").ljust(6, "0")[:6])
    return dt.datetime.fromisoformat(day).replace(
        hour=int(hh), minute=int(mm), second=int(ss or 0), microsecond=micros)


def zbon_stats_window(start_time: str, end_time: str) -> tuple[str, str]:
    """The statistics window that covers exactly one closing.

    A closing takes the bills with start > previous closing's end (= its startTime, exclusive) and
    <= its endTime (ZBonServiceImpl). The statistics repository filters greaterEq/lessEq, both
    inclusive, so the start moves 1 microsecond (Postgres timestamp precision) past startTime. Never
    truncate to whole seconds: that pulls the previous closing's last second into the window.
    """
    start = parse_server_ts(start_time) + dt.timedelta(microseconds=1)
    end = parse_server_ts(end_time)
    return start.isoformat(timespec="microseconds"), end.isoformat(timespec="microseconds")


def expected_buchungstag(closing: dict, transactions: list[dict]) -> str:
    """Z_BUCHUNGSTAG as DsfinvkExportServiceImpl derives it (Round-3 CONT-3/KABS-7, R3-2).

    The trading day is the date of the closing's own first Bon (sorted by BON_START, BON_ENDE,
    BON_ID); it is written only when it differs from the closing's creation date, so an
    after-midnight closing carries the previous day and a same-day closing leaves it empty. A
    closing without transactions claims no trading day.
    """
    if not transactions:
        return ""
    first = min(transactions, key=lambda r: (r.get("BON_START", ""), r.get("BON_ENDE", ""), r.get("BON_ID", "")))
    day = (first.get("BON_START") or "")[:10]
    return "" if day == (closing.get("Z_ERSTELLUNG") or "")[:10] else day


class Results:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []

    def add(self, check: str, status: str, detail: str) -> None:
        self.rows.append((check, status, detail))
        print(f"  {status:<4}  {check}: {detail}", flush=True)

    def failed(self) -> bool:
        return any(status == FAIL for _, status, _ in self.rows)

    def table(self) -> str:
        width = max([len(c) for c, _, _ in self.rows] + [5])
        lines = [f"{'CHECK'.ljust(width)}  STATUS  DETAIL", f"{'-' * width}  ------  ------"]
        for check, status, detail in self.rows:
            short = detail if len(detail) <= 110 else detail[:107] + "..."
            lines.append(f"{check.ljust(width)}  {status:<6}  {short}")
        return "\n".join(lines)


# --------------------------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------------------------


class Api:
    def __init__(self, base: str, device_id: str, timeout: int) -> None:
        self.base = base.rstrip("/")
        self.device_id = device_id
        self.timeout = timeout
        self.token: str | None = None
        self.user = os.environ.get("QA_USER", "")
        self.password = os.environ.get("QA_PASSWORD", "")

    def _raw(self, method: str, path: str, body=None, params=None, auth=True):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        headers = {"X-Device-ID": self.device_id, "Accept": "application/json, */*"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if auth and self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status, resp.read(), resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read(), exc.headers.get("Content-Type", "") if exc.headers else ""
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as exc:
            raise ServerUnreachable(f"{method} {url}: {exc}") from exc

    def login(self) -> None:
        if not self.user or not self.password:
            raise LoginFailed("QA_USER and QA_PASSWORD must be set in the environment")
        code, body, _ = self._raw(
            "POST", "/api/auth/login", {"identifier": self.user, "secret": self.password}, auth=False
        )
        if code != 200:
            raise LoginFailed(f"POST /api/auth/login answered {code}: {body[:200]!r}")
        self.token = json.loads(body)["token"]["accessToken"]

    def call(self, method: str, path: str, body=None, params=None, auth=True):
        code, raw, ctype = self._raw(method, path, body, params, auth)
        if code == 401 and auth:
            self.login()
            code, raw, ctype = self._raw(method, path, body, params, auth)
        return code, raw, ctype

    def json(self, method: str, path: str, body=None, params=None, auth=True):
        code, raw, _ = self.call(method, path, body, params, auth)
        if code >= 400:
            raise ApiError(method, path, code, raw)
        return json.loads(raw) if raw else None

    def bytes(self, method: str, path: str, body=None, params=None):
        code, raw, _ = self.call(method, path, body, params)
        if code >= 400:
            raise ApiError(method, path, code, raw)
        return raw


class ApiError(Exception):
    def __init__(self, method, path, code, raw) -> None:
        super().__init__(f"{method} {path} -> HTTP {code}: {raw[:240]!r}")
        self.code = code


# --------------------------------------------------------------------------------------------
# SQL (optional)
# --------------------------------------------------------------------------------------------


class Sql:
    """Runs SQL through psql when present, else through rig/PgQuery.java + a pgjdbc jar."""

    def __init__(self, port: int | None) -> None:
        self.port = port
        self.reason = ""
        self.mode = None
        if port is None:
            self.reason = "no --pg-port given"
            return
        if not os.environ.get("QA_PG_USER") or not os.environ.get("QA_PG_PASSWORD"):
            self.reason = "QA_PG_USER / QA_PG_PASSWORD not set"
            return
        if shutil.which("psql"):
            self.mode = "psql"
        elif shutil.which("java") and os.environ.get("QA_PGJDBC_JAR") and Path(os.environ["QA_PGJDBC_JAR"]).exists():
            self.mode = "java"
        else:
            self.reason = "neither psql nor java + QA_PGJDBC_JAR (pgjdbc jar) is available"

    @property
    def available(self) -> bool:
        return self.mode is not None

    def rows(self, query: str) -> list[list[str]]:
        env = dict(os.environ)
        env["QA_PG_PORT"] = str(self.port)
        if self.mode == "psql":
            env["PGPASSWORD"] = env["QA_PG_PASSWORD"]
            cmd = [
                "psql", "-X", "-q", "-At", "-F", "\t",
                "-h", env.get("QA_PG_HOST", "localhost"), "-p", str(self.port),
                "-U", env["QA_PG_USER"], "-d", env.get("QA_PG_DB", "pos_db"), "-c", query,
            ]
            out = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=120)
            if out.returncode != 0:
                raise RuntimeError(out.stderr.strip()[:300])
            return [line.split("\t") for line in out.stdout.splitlines() if line != ""]
        tool = Path(__file__).resolve().parent / "rig" / "PgQuery.java"
        cmd = ["java", "-cp", env["QA_PGJDBC_JAR"], str(tool), query]
        out = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=180)
        if out.returncode != 0:
            raise RuntimeError(out.stderr.strip()[:300])
        lines = out.stdout.splitlines()
        return [line.split("\t") for line in lines[1:]]  # drop the header line

    def scalar(self, query: str) -> str:
        rows = self.rows(query)
        return rows[0][0] if rows and rows[0] else ""


# --------------------------------------------------------------------------------------------
# context shared between checks
# --------------------------------------------------------------------------------------------


class Ctx:
    def __init__(self, args, api: Api, sql: Sql, res: Results) -> None:
        self.args = args
        self.api = api
        self.sql = sql
        self.res = res
        self._zbon_summary = None
        self._zbon_summary_loaded = False
        self.dsfinvk_cache: dict[tuple[str, str], dict] = {}

    def zbon_summary(self):
        """The Z-Bon under test: --zbon-number within the --date window, else the latest one."""
        if self._zbon_summary_loaded:
            return self._zbon_summary
        self._zbon_summary_loaded = True
        day = self.args.date
        lo = (dt.date.fromisoformat(day) - dt.timedelta(days=self.args.zbon_window_days)).isoformat()
        items = self.api.json("GET", "/api/z-bon/history", params={"startDate": lo, "endDate": day}) or []
        if self.args.zbon_number is not None:
            items = [z for z in items if int(z.get("zbonNummer", 0)) == self.args.zbon_number]
        else:
            items = [z for z in items if str(z.get("endTime", ""))[:10] == day] or items
        if items:
            self._zbon_summary = max(items, key=lambda z: (int(z.get("zbonNummer", 0)), z.get("endTime", "")))
        return self._zbon_summary

    def zbon_detail(self):
        summary = self.zbon_summary()
        if summary is None:
            return None
        return self.api.json("GET", f"/api/z-bon/{summary['id']}")


def no_zbon(ctx: Ctx, check: str) -> bool:
    if ctx.zbon_summary() is None:
        ctx.res.add(check, SKIP, f"no Z-Bon found for --date {ctx.args.date} (closing needed first)")
        return True
    return False


# --------------------------------------------------------------------------------------------
# checks
# --------------------------------------------------------------------------------------------


def check_setup(ctx: Ctx) -> None:
    status = ctx.api.json("GET", "/api/setup/status", auth=False)
    state = status.get("systemState") or {}
    counts = ", ".join(f"{k}={state[k]}" for k in ("productCount", "tableCount", "categoryCount") if k in state)
    if status.get("isInitialized"):
        ctx.res.add("setup", PASS, f"isInitialized=true ({counts})")
    else:
        ctx.res.add("setup", FAIL, f"server is not initialised ({counts}); bootstrap or restore first")


def check_release_notes(ctx: Ctx) -> None:
    page = ctx.api.json("GET", "/api/v1/changelog", params={"page": 0, "size": 50})
    items = page.get("items", []) if isinstance(page, dict) else page
    if not items:
        ctx.res.add("release-notes", FAIL, "changelog_release is empty (fresh install must seed it)")
        return
    problems = []
    for rel in items:
        ver = rel.get("releaseVersion")
        hl = [len(rel.get(f"highlights{lang}") or []) for lang in ("En", "De", "Zh")]
        ki = [len(rel.get(f"knownIssues{lang}") or []) for lang in ("En", "De", "Zh")]
        if len(set(hl)) != 1:
            problems.append(f"{ver} highlights EN/DE/ZH = {hl}")
        if len(set(ki)) != 1:
            problems.append(f"{ver} knownIssues EN/DE/ZH = {ki}")
        if hl[0] == 0:
            problems.append(f"{ver} has no highlights")
        for lang in ("En", "De", "Zh"):
            if not (rel.get(f"title{lang}") or "").strip():
                problems.append(f"{ver} title{lang} empty")
    versions = [r.get("releaseVersion") for r in items]
    if ctx.args.expect_release and ctx.args.expect_release not in versions:
        problems.append(f"expected release {ctx.args.expect_release} missing (have {versions})")
    summary = "; ".join(
        f"{r.get('releaseVersion')}: {len(r.get('highlightsDe') or [])} highlights / "
        f"{len(r.get('knownIssuesDe') or [])} known issues" for r in items[:4]
    )
    ctx.res.add("release-notes", FAIL if problems else PASS, "; ".join(problems) if problems else summary)


def check_restored_paths(ctx: Ctx) -> None:
    review = ctx.api.json("GET", "/api/v1/backup/restored-paths")
    pending = bool(review.get("pending"))
    paths = review.get("paths") or []
    if pending:
        kinds = ", ".join(str(p.get("kind") or p.get("type") or p) for p in paths)[:160]
        ctx.res.add("restored-paths", FAIL, f"restored-path review still pending ({kinds}); redirect paths first")
    else:
        ctx.res.add("restored-paths", PASS, "pending=false, no restored path awaits review")


def check_restore_sql(ctx: Ctx) -> None:
    if not ctx.sql.available:
        ctx.res.add("restore-sql", SKIP, f"SQL checks skipped: {ctx.sql.reason}")
        return
    last_end = ctx.sql.scalar("select coalesce(max(end_time)::text, '') from zbon")
    if not last_end:
        ctx.res.add("restore-sql", SKIP, "no Z-Bon in the database yet")
        return
    for table, col, severity in (("t_order", "order_time", FAIL), ("bill", "end_time", FAIL)):
        n = int(ctx.sql.scalar(
            f"select count(*) from {table} where zbon_id is null and {col} <= (select max(end_time) from zbon)"
        ))
        total = ctx.sql.scalar(f"select count(*) from {table}")
        ctx.res.add(f"restore-sql:{table}", PASS if n == 0 else severity,
                    f"{n} of {total} rows closed before the last Z-Bon ({last_end}) carry no zbon_id")
    kb_rows = ctx.sql.rows(
        "select coalesce(b.is_fiscal, true)::text, count(*) from kassenbuch_header h "
        "left join cash_book b on b.id = h.cash_book_id where h.zbon_id is null "
        "and h.created_at <= (select max(end_time) from zbon) group by 1"
    )
    status, detail = kassenbuch_unclosed_verdict(kb_rows)
    ctx.res.add("restore-sql:kassenbuch_header", status, detail)
    aggregates = ctx.sql.scalar("select count(*) from kassenbuch_header where is_zbon_aggregate")
    closings = ctx.sql.scalar("select count(*) from zbon")
    ctx.res.add("restore-sql:zbon-aggregate", PASS,
                f"{aggregates} Kassenbuch aggregates flagged for {closings} closings (info)")
    staging = ctx.sql.rows("select datname from pg_database where datname like 'pos_restore_staging_%'")
    ctx.res.add("restore-sql:staging-db", PASS if not staging else FAIL,
                "no pos_restore_staging_* database left" if not staging
                else f"staging database left behind: {[r[0] for r in staging]}")
    raw = ctx.sql.scalar("select value::text from shop_configuration where key = 'tse_config' limit 1")
    if not raw:
        ctx.res.add("restore-sql:tse-secrets", WARN, "no tse_config row found")
    else:
        try:
            keys = set(json.loads(raw).keys())
        except (ValueError, AttributeError):
            keys = set(re.findall(r'"([A-Za-z]+)"\s*:', raw))
        leaked = sorted(keys & {"adminPin", "adminPuk", "credentialSeed"})
        ctx.res.add("restore-sql:tse-secrets", FAIL if leaked else PASS,
                    f"tse_config still holds {leaked} (values not printed)" if leaked
                    else "tse_config holds no adminPin / adminPuk / credentialSeed")


def kassenbuch_unclosed_verdict(rows: list[list[str]]) -> tuple[str, str]:
    """Kassenbuch headers without zbon_id, split by cash_book.is_fiscal.

    Only the fiscal book is closed by a Z-Bon; a non-fiscal book (Tresor, Bank) keeps its own
    close interval, so its rows never carry a zbon_id and are reported for information only.
    """
    counts = {"true": 0, "false": 0}
    for flag, n in rows:
        key = "true" if flag.strip().lower() in ("t", "true") else "false"
        counts[key] += int(n)
    fiscal, other = counts["true"], counts["false"]
    note = f"; {other} on non-fiscal books (not closed by a Z-Bon, ignored)" if other else ""
    if fiscal == 0:
        return PASS, f"0 fiscal Kassenbuch headers before the last Z-Bon lack a zbon_id{note}"
    return WARN, (f"{fiscal} fiscal Kassenbuch headers before the last Z-Bon carry no zbon_id "
                  f"(compare with the pre-upgrade count){note}")


def read_dsfinvk(ctx: Ctx, start: str, end: str) -> dict:
    key = (start, end)
    if key in ctx.dsfinvk_cache:
        return ctx.dsfinvk_cache[key]
    validation = ctx.api.json("GET", "/api/v1/dsfinvk/validate", params={"startDate": start, "endDate": end})
    blob = ctx.api.bytes("POST", "/api/v1/dsfinvk/export", body={"startDate": start, "endDate": end})
    tables: dict[str, list[dict]] = {}
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        for name in zf.namelist():
            if not name.endswith(".csv"):
                continue
            text = zf.read(name).decode("utf-8", errors="replace")
            tables[Path(name).name] = list(csv.DictReader(io.StringIO(text), delimiter=";", quotechar='"'))
    data = {"validation": validation, "size": len(blob), "tables": tables}
    ctx.dsfinvk_cache[key] = data
    return data


def check_dsfinvk_range(ctx: Ctx, start: str, end: str) -> None:
    tag = f"dsfinvk[{start}..{end}]" if start != end else f"dsfinvk[{start}]"
    data = read_dsfinvk(ctx, start, end)
    v = data["validation"] or {}
    if v.get("isValid") and not v.get("errors"):
        ctx.res.add(f"{tag}:validate", PASS, f"isValid=true, {len(v.get('warnings') or [])} warnings")
    else:
        ctx.res.add(f"{tag}:validate", FAIL, f"isValid={v.get('isValid')} errors={(v.get('errors') or [])[:3]}")
    t = data["tables"]
    closings = t.get("cashpointclosing.csv", [])
    if not closings:
        ctx.res.add(tag, WARN, f"no closing in the export ({data['size']} bytes) - nothing to reconcile")
        return
    tx_by_z: dict[str, list[dict]] = {}
    for row in t.get("transactions.csv", []):
        tx_by_z.setdefault(row["Z_NR"], []).append(row)
    zbon_starts = zbon_start_times(ctx, start, end)
    for c in closings:
        z = c["Z_NR"]
        bc = [r for r in t.get("businesscases.csv", []) if r["Z_NR"] == z]
        pay = [r for r in t.get("payment.csv", []) if r["Z_NR"] == z]
        sum_bc = sum((money(r["Z_UMS_BRUTTO"]) for r in bc), Decimal("0"))
        sum_pay = sum((money(r["Z_ZAHLART_BETRAG"]) for r in pay), Decimal("0"))
        se = money(c["Z_SE_ZAHLUNGEN"])
        ok = same(sum_bc, se) and same(sum_pay, se)
        ctx.res.add(f"{tag}:Z{z}:sum", PASS if ok else FAIL,
                    f"sum businesscases {fmt(sum_bc)} / Z_SE_ZAHLUNGEN {fmt(se)} / sum payment {fmt(sum_pay)}")
        cash = sum((money(r["Z_ZAHLART_BETRAG"]) for r in pay if r["ZAHLART_TYP"] == "Bar"), Decimal("0"))
        se_bar = money(c["Z_SE_BARZAHLUNGEN"])
        ctx.res.add(f"{tag}:Z{z}:cash", PASS if same(cash, se_bar) else FAIL,
                    f"Z_SE_BARZAHLUNGEN {fmt(se_bar)} vs sum payment[Bar] {fmt(cash)}")
        double = [r for r in bc if r["GV_TYP"] == "Umsatz" and r["GV_NAME"] == "Umsatz"]
        per_rate: dict[str, Decimal] = {}
        for r in bc:
            if r["GV_TYP"] == "Umsatz":
                per_rate[r["UST_SCHLUESSEL"]] = per_rate.get(r["UST_SCHLUESSEL"], Decimal("0")) + money(r["Z_UMS_BRUTTO"])
        negative = {k: fmt(vv) for k, vv in per_rate.items() if vv < 0}
        bad = []
        if double:
            bad.append(f"{len(double)} 'Umsatz/Umsatz' rows (admin16 FAIL-6 signature)")
        if negative:
            bad.append(f"negative Umsatz per UST_SCHLUESSEL {negative}")
        ctx.res.add(f"{tag}:Z{z}:umsatz", FAIL if bad else PASS,
                    "; ".join(bad) if bad else f"Umsatz per key {{{', '.join(f'{k}: {fmt(vv)}' for k, vv in sorted(per_rate.items()))}}}")
        txs = tx_by_z.get(z, [])
        day = c.get("Z_BUCHUNGSTAG", "")
        want = expected_buchungstag(c, txs)
        ctx.res.add(f"{tag}:Z{z}:buchungstag", PASS if day == want else FAIL,
                    f"Z_BUCHUNGSTAG {day!r}, expected {want!r} (first Bon's date when it differs from the "
                    f"creation date {(c.get('Z_ERSTELLUNG') or '')[:10]!r}, else empty)")
        ids = {r["BON_ID"] for r in txs}
        ends_ok = c["Z_START_ID"] in ids and c["Z_ENDE_ID"] in ids
        ctx.res.add(f"{tag}:Z{z}:start-end-id", PASS if ends_ok else FAIL,
                    f"Z_START_ID {c['Z_START_ID']} / Z_ENDE_ID {c['Z_ENDE_ID']} "
                    f"{'both' if ends_ok else 'NOT both'} among the {len(txs)} transactions")
        zstart = zbon_starts.get(z)
        if zstart is None:
            ctx.res.add(f"{tag}:Z{z}:scope", SKIP, "Z-Bon start time unknown (not in /api/z-bon/history)")
        else:
            floor = zstart - dt.timedelta(days=1)
            old = [r["BON_ID"] for r in txs if r.get("BON_START") and parse_ts(r["BON_START"]) < floor]
            ctx.res.add(f"{tag}:Z{z}:scope", PASS if not old else FAIL,
                        f"{len(txs)} transactions; none starts > 1 day before the closing's start {zstart:%Y-%m-%d %H:%M}"
                        if not old else f"{len(old)} transactions start before {floor:%Y-%m-%d} "
                        f"(admin16 FAIL-5 signature), e.g. {old[:3]}")
        refs = [r for r in t.get("references.csv", []) if r["Z_NR"] == z and r.get("REF_TYP") == "Transaktion"]
        all_ids = {r["BON_ID"] for r in t.get("transactions.csv", [])}
        dangling = [f"{r['BON_ID']}->{r['REF_BON_ID']}" for r in refs if r.get("REF_BON_ID") not in all_ids]
        ctx.res.add(f"{tag}:Z{z}:references", PASS if not dangling else WARN,
                    f"{len(refs)} Transaktion references resolve inside the export" if not dangling
                    else f"{len(dangling)} references point outside this export (fine only for a Storno of an "
                    f"earlier day's receipt): {dangling[:3]}")


def parse_ts(text: str) -> dt.datetime:
    return dt.datetime.fromisoformat(text.strip().replace("Z", "")[:19])


def zbon_start_times(ctx: Ctx, start: str, end: str) -> dict[str, dt.datetime]:
    lo = (dt.date.fromisoformat(start) - dt.timedelta(days=ctx.args.zbon_window_days)).isoformat()
    try:
        items = ctx.api.json("GET", "/api/z-bon/history", params={"startDate": lo, "endDate": end}) or []
    except ApiError:
        return {}
    return {str(z.get("zbonNummer")): parse_ts(z["startTime"]) for z in items if z.get("startTime")}


def check_dsfinvk(ctx: Ctx) -> None:
    days = [ctx.args.date] + list(ctx.args.dsfinvk_day or [])
    for day in days:
        try:
            check_dsfinvk_range(ctx, day, day)
        except ApiError as exc:
            if exc.code == 404:
                ctx.res.add(f"dsfinvk[{day}]", SKIP,
                            f"no closing was created on {day} (export answered 404; the export selects "
                            "closings by creation date)")
            else:
                ctx.res.add(f"dsfinvk[{day}]", FAIL, str(exc))


def check_zbon_totals(ctx: Ctx) -> None:
    if no_zbon(ctx, "zbon-totals"):
        return
    z = ctx.zbon_detail()
    nr = z.get("zbonNummer")
    tag = f"zbon-totals[#{nr}]"
    total = money(z.get("totalRevenue"))
    vat_gross = sum((money(g.get("grossAmount")) for g in z.get("vatGroups") or []), Decimal("0"))
    ctx.res.add(f"{tag}:vat", PASS if same(vat_gross, total) else FAIL,
                f"sum VAT gross {fmt(vat_gross)} vs Gesamtumsatz {fmt(total)}"
                + ("" if same(vat_gross, total) else " (legacy closings with Punktekarte promotions differ by design, NOTE-21)"))
    pay = sum((money(p.get("amount")) for p in z.get("paymentSummary") or []), Decimal("0"))
    emp_tips = money(z.get("totalEmployeeTips"))
    cleared = money(z.get("totalReceivableCleared"))
    expected_pay = total + emp_tips
    status = PASS if same(pay, expected_pay) else FAIL
    if status == FAIL and cleared != 0:
        status = WARN
    ctx.res.add(f"{tag}:payments", status,
                f"sum payments {fmt(pay)} vs Gesamtumsatz {fmt(total)} + employee tips {fmt(emp_tips)}"
                + (f" (receivable cleared {fmt(cleared)})" if cleared else ""))
    summaries = z.get("employeeSummaries") or []
    rev = sum((money(e.get("totalRevenue")) for e in summaries), Decimal("0"))
    admin_tip = money(z.get("adminTip"))
    ok = same(rev + admin_tip, total)
    ctx.res.add(f"{tag}:employee-rows", PASS if ok else FAIL,
                f"sum employee Umsatz {fmt(rev)} + Inhabertrinkgeld {fmt(admin_tip)} vs Gesamtumsatz {fmt(total)}")
    tips = sum((money(e.get("amount")) for e in summaries if not e.get("ownerTip")), Decimal("0"))
    ctx.res.add(f"{tag}:tip-block", PASS if same(tips, emp_tips) else FAIL,
                f"sum per-employee tips {fmt(tips)} vs Trinkgeld gesamt {fmt(emp_tips)}")
    promo = sum((money(p.get("totalDeducted")) for p in z.get("promotionBreakdown") or []), Decimal("0"))
    total_promo = money(z.get("totalPromotions"))
    ctx.res.add(f"{tag}:promotions", PASS if same(promo, total_promo) else FAIL,
                f"sum promotion rows {fmt(promo)} vs Aktionen gesamt {fmt(total_promo)}")
    refund, rebook = money(z.get("refundAmount")), money(z.get("rebookStornoAmount"))
    ok = rebook <= refund and int(z.get("rebookStornoCount") or 0) <= int(z.get("stornoBillCount") or 0)
    ctx.res.add(f"{tag}:refunds", PASS if ok else FAIL,
                f"Rueckerstattungen ({z.get('stornoBillCount')}) {fmt(refund)} >= davon Zahlartwechsel "
                f"({z.get('rebookStornoCount')}) {fmt(rebook)}")
    opening, closing = money(z.get("openingBalance")), money(z.get("closingBalance"))
    ctx.res.add(f"{tag}:balance", PASS, f"Anfangsbestand {fmt(opening)} -> Endbestand {fmt(closing)} (info)")


def check_zbon_pdf_stable(ctx: Ctx) -> None:
    if no_zbon(ctx, "zbon-pdf-stable"):
        return
    zid = ctx.zbon_summary()["id"]
    first = ctx.api.bytes("GET", f"/api/z-bon/{zid}/pdf")
    second = ctx.api.bytes("GET", f"/api/z-bon/{zid}/pdf")
    h1, h2 = hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest()
    if not first.startswith(b"%PDF"):
        ctx.res.add("zbon-pdf-stable", FAIL, f"response is not a PDF ({first[:8]!r})")
    elif h1 != h2:
        ctx.res.add("zbon-pdf-stable", FAIL, f"two fetches differ: {h1[:12]} vs {h2[:12]}")
    else:
        ctx.res.add("zbon-pdf-stable", PASS, f"two fetches byte-identical ({len(first)} bytes, sha256 {h1[:12]})")


def check_stats_vs_zbon(ctx: Ctx) -> None:
    if no_zbon(ctx, "stats-vs-zbon"):
        return
    z = ctx.zbon_detail()
    tag = f"stats-vs-zbon[#{z.get('zbonNummer')}]"
    start, end = zbon_stats_window(z["startTime"], z["endTime"])
    ov = ctx.api.json("GET", "/api/statistics/sales/overview", params={"start_date": start, "end_date": end})
    pairs = [
        ("Umsatz", money(ov.get("totalRevenue")), money(z.get("totalRevenue"))),
        ("Rabatte", money(ov.get("totalDiscounts")), money(z.get("totalDiscounts"))),
        ("Rueckerstattungen ohne ZW", money(ov.get("refundRevenue")),
         money(z.get("refundAmount")) - money(z.get("rebookStornoAmount"))),
        ("Trinkgeld", money(ov.get("totalTips")), money(z.get("totalEmployeeTips")) + money(z.get("adminTip"))),
    ]
    for name, stat, zb in pairs:
        ctx.res.add(f"{tag}:{name}", PASS if same(stat, zb) else FAIL,
                    f"statistics {fmt(stat)} vs Z-Bon {fmt(zb)}")


def zbon_window(summary: dict) -> tuple[str, str]:
    """The closing's own window at FULL precision. Cutting it to whole seconds drops every receipt
    closed in the last fraction of a second before the Z-Bon - invisible at human pace, but a scripted
    pass (api_smoke.py) bills and closes within one second, and then the list came back empty."""
    return summary["startTime"], summary["endTime"]


def walk_bills(ctx: Ctx, start: str, end: str, include_training: bool = False) -> list[dict]:
    """Every receipt closed in the window, paged. `include_training` asks for Schulungsbelege too —
    the receipt list of a Z-Bon (the adminApp "Rechnungen" tab) does, as `bill.zbon_id` and the
    DSFinV-K export do (emu20 NOTE-t); the server's default leaves them out."""
    bills, offset, limit = [], 0, 50
    while True:
        params = {"startDate": start, "endDate": end, "includeDeleted": "true", "offset": offset, "limit": limit}
        if include_training:
            params["includeTraining"] = "true"
        page = ctx.api.json("GET", "/api/v1/bills/search", params=params) or []
        bills.extend(page)
        if len(page) < limit or offset > 100_000:
            return bills
        offset += limit


def walk_sessions(ctx: Ctx, start: str, end: str) -> list[dict]:
    sessions, offset, limit = [], 0, 50
    while True:
        page = ctx.api.json("GET", "/api/tables/sessions", params={
            "startDate": start, "endDate": end, "offset": offset, "limit": limit,
        }) or []
        sessions.extend(page)
        if len(page) < limit or offset > 100_000:
            return sessions
        offset += limit


def check_zbon_bills(ctx: Ctx) -> None:
    if no_zbon(ctx, "zbon-bills"):
        return
    s = ctx.zbon_summary()
    tag = f"zbon-bills[#{s.get('zbonNummer')}]"
    # The list the Z-Bon "Rechnungen" tab shows: every signed Beleg, Schulungsbelege included
    # (badged via isTraining). bill.zbon_id and DSFinV-K's B-* both count them too.
    bills = walk_bills(ctx, *zbon_window(s), include_training=True)
    ids = [b["billId"] for b in bills]
    dup = len(ids) - len(set(ids))
    training = sum(1 for b in bills if b.get("isTraining"))
    ctx.res.add(f"{tag}:paging", PASS if dup == 0 else FAIL,
                f"{len(ids)} receipts ({training} training) over {len(ids) // 50 + 1} page(s) of 50, {dup} duplicates")
    if ctx.sql.available:
        n_training = int(ctx.sql.scalar(
            f"select count(*) from bill where zbon_id = {int(s['id'])} and is_training"))
        ctx.res.add(f"{tag}:training-flagged", PASS if n_training == training else FAIL,
                    f"API {training} receipts flagged isTraining vs DB {n_training} training bills of the closing "
                    "(a server without includeTraining lists none)")
    if ctx.sql.available:
        n = int(ctx.sql.scalar(f"select count(*) from bill where zbon_id = {int(s['id'])}"))
        ctx.res.add(f"{tag}:vs-db", PASS if n == len(set(ids)) else FAIL,
                    f"API {len(set(ids))} receipts vs DB bill.zbon_id={s['id']}: {n}")
    day = s["endTime"][:10]
    try:
        tx = read_dsfinvk(ctx, day, day)["tables"].get("transactions.csv", [])
        belege = [r for r in tx if r["Z_NR"] == str(s.get("zbonNummer")) and r["BON_ID"].startswith("B-")]
        ctx.res.add(f"{tag}:vs-dsfinvk", PASS if len(belege) == len(set(ids)) else FAIL,
                    f"API {len(set(ids))} receipts vs {len(belege)} Beleg transactions (B-*) in the DSFinV-K export")
    except ApiError as exc:
        ctx.res.add(f"{tag}:vs-dsfinvk", SKIP, f"DSFinV-K export unavailable: {exc}")


def check_zbon_sessions(ctx: Ctx) -> None:
    if no_zbon(ctx, "zbon-sessions"):
        return
    s = ctx.zbon_summary()
    tag = f"zbon-sessions[#{s.get('zbonNummer')}]"
    start, end = zbon_window(s)
    sessions = walk_sessions(ctx, start, end)
    sids = [x["id"] for x in sessions]
    dup = len(sids) - len(set(sids))
    ctx.res.add(f"{tag}:paging", PASS if dup == 0 else FAIL,
                f"{len(sids)} sessions over {len(sids) // 50 + 1} page(s) of 50, {dup} duplicates")
    billed = {b.get("tableSessionId") for b in walk_bills(ctx, start, end)}
    missing = sorted(x for x in billed - set(sids) if x is not None)
    ctx.res.add(f"{tag}:billed-sessions-listed", PASS if not missing else WARN,
                f"all {len(billed)} sessions with a receipt are listed" if not missing
                else f"{len(missing)} billed sessions not in the list (a Storno can reopen a session from "
                f"before the closing's start; else the list is truncated): {missing[:5]}")
    ctx.session_ids = sorted(set(sids) | {x for x in billed if x is not None})


def check_session_receipts(ctx: Ctx) -> None:
    if no_zbon(ctx, "session-receipts"):
        return
    if not hasattr(ctx, "session_ids"):
        s = ctx.zbon_summary()
        sessions = walk_sessions(ctx, *zbon_window(s))
        ctx.session_ids = sorted({x["id"] for x in sessions})
    ids = ctx.session_ids[: ctx.args.max_sessions]
    bad, relations_missing, checked = [], 0, 0
    for sid in ids:
        full = ctx.api.json("GET", f"/api/v1/bills/table-session/{sid}", params={"includeDeleted": "true"}) or []
        default = ctx.api.json("GET", f"/api/v1/bills/table-session/{sid}") or []
        if not full:
            continue
        checked += 1
        fid = {b["billId"] for b in full}
        if not {b["billId"] for b in default} <= fid:
            bad.append(f"session {sid}: default list not a subset of includeDeleted list")
        rel = [(b, b.get("relations") or {}) for b in full]
        if any(money(b.get("paidAmount")) < 0 and r.get("kind", "SALE") == "SALE" for b, r in rel):
            relations_missing += 1
            continue
        for b, r in rel:
            if r.get("kind") == "STORNO" and r.get("reversesBill") and r["reversesBill"]["billId"] not in fid:
                bad.append(f"session {sid}: {b['billNumber']} reverses {r['reversesBill']['billNumber']}, "
                           "which the session list does not show (admin17 FAIL-2 signature)")
            if r.get("kind") == "REBOOKING" and r.get("rebooksBill") and r["rebooksBill"]["billId"] not in fid:
                bad.append(f"session {sid}: {b['billNumber']} rebooks {r['rebooksBill']['billNumber']}, not listed")
        net_all = sum((money(b.get("paidAmount")) for b in full), Decimal("0"))
        standing = sum((money(b.get("paidAmount")) for b, r in rel
                        if r.get("kind", "SALE") != "STORNO" and not r.get("isCancelled")), Decimal("0"))
        if not same(net_all, standing):
            bad.append(f"session {sid}: sum of all receipts {fmt(net_all)} != sum of standing receipts {fmt(standing)}")
    if relations_missing:
        ctx.res.add("session-receipts:relations", WARN,
                    f"{relations_missing} sessions carry negative receipts without relations; invariants skipped there")
    more = f" (first {len(ids)} of {len(ctx.session_ids)}; raise --max-sessions)" if len(ctx.session_ids) > len(ids) else ""
    ctx.res.add("session-receipts", FAIL if bad else PASS,
                "; ".join(bad[:4]) + (f" ... +{len(bad) - 4}" if len(bad) > 4 else "") if bad
                else f"{checked} sessions: every Storno/Neubuchung original listed, all receipts net to the standing ones{more}")


def balance_text(raw: bytes) -> str:
    """The balance exactly as the server wrote it. Parsing a plain-text `37.50` as JSON turned it into
    the float 37.5, so the check could never see the scale (gs1/gh2, 2026-10-09)."""
    text = raw.decode(errors="replace").strip()
    if text.startswith("{"):
        m = re.search(r'"(?:balance|currentBalance)"\s*:\s*"?([^",}]+)"?', text)
        return m.group(1).strip() if m else text
    return text.strip('"')


def check_kassenbuch_balance(ctx: Ctx) -> None:
    text = balance_text(ctx.api.bytes("GET", "/api/v1/kassenbuch/balance"))
    ok = re.fullmatch(r"-?\d+(\.\d+)?", text) is not None
    ctx.res.add("kassenbuch-balance", PASS if ok else FAIL,
                f"balance {text!r} is {'a canonical decimal' if ok else 'NOT canonical (locale-formatted? admin16 NOTE-5)'}")


CHECKS = {
    "setup": check_setup,
    "release-notes": check_release_notes,
    "restored-paths": check_restored_paths,
    "restore-sql": check_restore_sql,
    "dsfinvk": check_dsfinvk,
    "zbon-totals": check_zbon_totals,
    "zbon-pdf-stable": check_zbon_pdf_stable,
    "stats-vs-zbon": check_stats_vs_zbon,
    "zbon-bills": check_zbon_bills,
    "zbon-sessions": check_zbon_sessions,
    "session-receipts": check_session_receipts,
    "kassenbuch-balance": check_kassenbuch_balance,
}


def parse_args(argv):
    p = argparse.ArgumentParser(
        description="API/SQL release checks for a QA pos-server (see docs/testing/release-qa-catalogue.md).",
        epilog="Credentials: QA_USER / QA_PASSWORD (login), QA_PG_USER / QA_PG_PASSWORD (+ QA_PGJDBC_JAR "
               "when psql is absent) for --pg-port. Exit: 0 ok, 1 a check FAILED, 2 unreachable / login failed.",
    )
    p.add_argument("--base", default=os.environ.get("QA_BASE", "http://localhost:8080"),
                   help="server base URL (default $QA_BASE or http://localhost:8080)")
    p.add_argument("--date", default=dt.date.today().isoformat(),
                   help="business day under test, YYYY-MM-DD: its DSFinV-K export and its Z-Bon (default today)")
    p.add_argument("--zbon-number", type=int, help="Z-Bon number to check (default: the last one closed on --date)")
    p.add_argument("--zbon-window-days", type=int, default=14,
                   help="how far back /api/z-bon/history is searched (default 14)")
    p.add_argument("--dsfinvk-day", action="append", metavar="YYYY-MM-DD",
                   help="extra day to export and reconcile, e.g. a restored legacy closing (repeatable)")
    p.add_argument("--expect-release", help="release version that must be in /api/v1/changelog, e.g. 1.1.0")
    p.add_argument("--checks", help=f"comma list to run (default all): {','.join(ALL_CHECKS)}")
    p.add_argument("--skip", help="comma list of checks to skip")
    p.add_argument("--max-sessions", type=int, default=300, help="cap for session-receipts (default 300)")
    p.add_argument("--pg-port", type=int, help="embedded Postgres port; enables the SQL checks")
    p.add_argument("--timeout", type=int, default=180, help="HTTP timeout in seconds (default 180)")
    p.add_argument("--json", metavar="FILE", help="also write the results as JSON to FILE")
    args = p.parse_args(argv)
    try:
        dt.date.fromisoformat(args.date)
        for d in args.dsfinvk_day or []:
            dt.date.fromisoformat(d)
    except ValueError as exc:
        p.error(f"bad date: {exc}")
    selected = args.checks.split(",") if args.checks else list(ALL_CHECKS)
    skipped = set(args.skip.split(",")) if args.skip else set()
    unknown = [c for c in selected + list(skipped) if c not in CHECKS]
    if unknown:
        p.error(f"unknown check(s): {unknown}")
    args.selected = [c for c in selected if c not in skipped]
    return args


def main(argv=None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    res = Results()
    api = Api(args.base, os.environ.get("QA_DEVICE_ID", "qa-release-checks"), args.timeout)
    sql = Sql(args.pg_port)
    ctx = Ctx(args, api, sql, res)
    print(f"release_checks: {args.base}  date={args.date}  checks={','.join(args.selected)}")
    try:
        api.json("GET", "/api/setup/status", auth=False)  # reachability probe before any login
        api.login()
    except ServerUnreachable as exc:
        print(f"\nERROR: server not reachable - {exc}\nIs the QA server running, and is --base right?",
              file=sys.stderr)
        return 2
    except (LoginFailed, ApiError) as exc:
        print(f"\nERROR: {exc}", file=sys.stderr)
        return 2
    for name in args.selected:
        try:
            CHECKS[name](ctx)
        except ServerUnreachable as exc:
            res.add(name, FAIL, f"server became unreachable: {exc}")
            break
        except ApiError as exc:
            res.add(name, FAIL, str(exc))
        except Exception as exc:  # a check must never take the others down with it
            res.add(name, FAIL, f"{type(exc).__name__}: {exc}")
    print("\n" + res.table())
    counts = {s: sum(1 for _, st, _ in res.rows if st == s) for s in (PASS, FAIL, WARN, SKIP)}
    print(f"\n{counts[PASS]} PASS, {counts[FAIL]} FAIL, {counts[WARN]} WARN, {counts[SKIP]} SKIP")
    if args.json:
        Path(args.json).write_text(json.dumps(
            [{"check": c, "status": s, "detail": d} for c, s, d in res.rows], indent=2))
    return 1 if res.failed() else 0


if __name__ == "__main__":
    sys.exit(main())
