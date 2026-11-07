#!/usr/bin/env python3
"""Pick and print cases from docs/testing/release-qa-catalogue.md without reading all of it.

    cases.py list [--section N ...] [--tier api|ui|restore|hw] [--new]
    cases.py show ID [ID ...]            print the case blocks verbatim (SMK-01 ZB-03 ...)
    cases.py select [--since REF]        sections touched by the changes since REF (default: last v* tag)
    cases.py sheet PASS ID [ID ...]      results-<PASS>.md skeleton on stdout (one row per case)
    cases.py record RESULTS.md [--write] fold a filled results file into the catalogue: appends
                                         "<STATUS> <pass>." to each case's History/Last line and adds the
                                         pass to the section 0.2 table (dry run unless --write)

Tiers (what a case needs to run):
  api      HTTP/SQL only: runs against any isolated server
  ui       a till/station emulator or adminApp in a browser
  restore  a restored production backup (operator-only restore; qa-rig.md section 5)
  hw       the real-TSE machine or an operator-typed PIN (catalogue section 18, BRW-02)

The catalogue stays the single source of truth; this script only slices it.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOGUE = REPO / "docs/testing/release-qa-catalogue.md"

SECTION_RE = re.compile(r"^## (\d+)\. (.+)$")
CASE_RE = re.compile(r"^### ([A-Z]+-\d+[a-z]?) (.+)$")
MODE_RE = re.compile(r"\*\*Mode:\*\*\s*([^·\n]+)")
OPERATOR_RE = re.compile(r"\*\*Operator:\*\*\s*([^·\n]+)")
AUTO_RE = re.compile(r"Auto:\*{0,2}\s*([^·\n]+)")

# Changed path (regex, matched against `git diff --name-only`) -> catalogue sections it puts in scope.
# Section 1 (smoke) is always in scope. Order does not matter; every match adds its sections.
# Old-path alternates (^pos-export/, ^feature-*/, ^einvoice/, ^money/) are a harmless safety net: changed_paths() runs
# `git diff --name-only` WITHOUT --no-renames, so rename detection applies and a moved file shows only under its NEW path;
# old paths matter only for entries that rename detection leaves as plain deletions/changes. Removable after the first release spanning the move.
PATH_SECTIONS: list[tuple[str, tuple[int, ...], str]] = [  # matched case-insensitively
    (r"^pos-tse/|^fiscal/process-data/|/tse/|tse[A-Z_]?\w*\.kt$", (10, 13, 14, 18), "TSE"),
    (r"zbon|z-bon|kassenmeldung", (10, 9, 12), "Z-Bon"),
    (r"dsfinvk|^fiscal/export/|^pos-export/", (14, 10), "DSFinV-K / export"),
    (r"/features/(bill|payment)/|bill[a-z]*\.kt$|rebook|storno|cancellation", (4, 5, 6, 10), "billing / Storno / Zahlartwechsel"),
    (r"/features/order/", (3, 4, 6), "orders"),
    (r"promotion", (7, 8, 10), "promotions"),
    (r"salesinvoice|/features/document/|^accounting/document/|^accounting/einvoice/|^accounting/banking/|^feature-document/|^einvoice/|/features/(allocation|receivable)", (8, 13), "invoices / documents / receivables"),
    (r"print|escpos|^ecr/printer/|^ecr/receipt/|^feature-printer/", (9,), "printing"),
    (r"kassenbuch|cashbook|cash-book|/tip|trinkgeld", (11, 10), "Kassenbuch / cash books / tips"),
    (r"statistic|report|analytics", (12, 4, 13), "statistics / reports"),
    (r"datev", (13, 11), "DATEV exports"),
    (r"customexport|custom-export", (13,), "Eigene Exporte (BO-23)"),
    (r"/features/auth/|session|token|capabilit|permission", (2, 3, 15), "auth / sessions / permissions"),
    (r"terminal|station|pairing|/features/table/|rotation", (3, 19), "terminals / order station / tables"),
    (r"product|categor|choice|extra|employee|voucher|paymentmethod|paymenttype", (15, 7), "master data"),
    (r"/features/(config|settings|setup)/|setting|openinghours", (15, 14), "settings / setup"),
    (r"install-service|provision-node|usb-mount|datadirectory|osutils|mountpointguard|backupcopy|backupconfigjson|zenbon_data_dir|logdirectory", (22, 14), "standard Linux paths / backup second copy"),
    (r"backup|restore|/db/migration/v\d+__(?!release_)|flyway|reconciler", (14,), "restore / upgrade / migrations"),
    (r"/db/migration/v\d+__release_", (13,), "release-notes marker (BO-20, release_checks release-notes)"),
    (r"^platform/money/|^money/", (1, 4, 10), "money kernel (amounts everywhere)"),
    (r"^ui/backoffice/|^ui/shared/", (13, 15), "back office (adminApp / shared-ui)"),
    (r"^ui/till/|^ui/till-android/", (1, 2, 3, 4, 9), "till / station app"),
    (r"browserstation|/wasmjsmain/|posweb", (19,), "browser stations"),
    (r"startpage|webaccess|apidocs", (20,), "start page / API docs"),
    (r"(^|/)build\.gradle\.kts$|^gradle/|^settings\.gradle", (1,), "build (covered by the L0 gate)"),
]
IGNORED_PATHS = re.compile(r"^(docs/|scripts/qa/|\.claude/|\.github/|README|CLAUDE\.md)|/manual/|\.md$")
# Tests change with features but are proven by the L0 gate, not by catalogue cases.
TEST_PATHS = re.compile(r"/src/(test|\w*Test)/")
# Only product code can be an untested feature; the marketing site, lockfiles etc. cannot.
# A module sits at the root (pos-server/, pos-tse/) or one level under an area folder (platform/server/,
# ecr/printer/, ...): since Stage B2-1b the platform server code lives in platform/server/, and a
# root-only pattern dropped its unmapped changes (scheduler, maintenance, update, ...) silently.
PRODUCT_CODE = re.compile(r"^(?!website/)(?:[^/]+/){1,2}src/.*\.(kt|java|sql)$")
FEATURE_DIR = re.compile(r"/features/([A-Za-z]+)/")


@dataclass
class Case:
    id: str
    title: str
    section: int
    section_title: str
    text: str
    mode: str = ""
    auto: str = ""
    operator: str = ""
    tier: str = field(default="")

    @property
    def new(self) -> bool:
        return "NEW" in self.title


def tier_of(case: Case) -> str:
    if case.section == 18 or case.operator.lower().startswith("yes"):
        return "hw"
    if case.id.startswith(("RST-", "UPG-")):
        return "restore"
    if "UI" in case.mode or not case.mode:
        return "ui"
    return "api"


def parse() -> tuple[dict[int, str], list[Case]]:
    sections: dict[int, str] = {}
    cases: list[Case] = []
    current_section, current_title = 0, ""
    block: list[str] | None = None
    head: re.Match | None = None

    def flush() -> None:
        if block is None or head is None:
            return
        text = "\n".join(block).rstrip()
        case = Case(head.group(1), head.group(2).strip(), current_section, current_title, text)
        if m := MODE_RE.search(text):
            case.mode = m.group(1).strip()
        if m := AUTO_RE.search(text):
            case.auto = m.group(1).strip().rstrip(".")
        if m := OPERATOR_RE.search(text):
            case.operator = m.group(1).strip()
        case.tier = tier_of(case)
        cases.append(case)

    for line in CATALOGUE.read_text(encoding="utf-8").splitlines():
        if m := SECTION_RE.match(line):
            flush()
            block, head = None, None
            current_section, current_title = int(m.group(1)), m.group(2).strip()
            sections[current_section] = current_title
        elif m := CASE_RE.match(line):
            flush()
            block, head = [line], m
        elif line.startswith("### "):
            flush()
            block, head = None, None
        elif block is not None:
            block.append(line)
    flush()
    return sections, cases


def last_release_tag() -> str:
    # Highest version, not `git describe`: master gets squash merges, so a release tag is never an
    # ancestor of dev and describe would name a much older tag.
    out = subprocess.run(["git", "-C", str(REPO), "tag", "--list", "v*", "--sort=-v:refname"],
                         capture_output=True, text=True)
    tags = out.stdout.split()
    if out.returncode != 0 or not tags:
        sys.exit("no v* tag found; pass --since <ref>")
    return tags[0]


def changed_paths(since: str) -> list[str]:
    out = subprocess.run(["git", "-C", str(REPO), "-c", "core.quotepath=off", "diff", "--name-only", since, "HEAD"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"git diff {since} HEAD failed: {out.stderr.strip()}")
    return [p for p in out.stdout.splitlines() if p]


def cmd_list(args, sections, cases) -> None:
    rows = [c for c in cases
            if (not args.section or c.section in args.section)
            and (not args.tier or c.tier == args.tier)
            and (not args.new or c.new)]
    for c in rows:
        auto = f"  auto={c.auto}" if c.auto and c.auto.lower() != "none" else ""
        print(f"{c.id:<9} {c.tier:<8} §{c.section:<3} {c.title}{auto}")
    print(f"-- {len(rows)} case(s)", file=sys.stderr)


def cmd_show(args, sections, cases) -> None:
    by_id = {c.id: c for c in cases}
    missing = [i for i in args.ids if i not in by_id]
    for i in args.ids:
        if i in by_id:
            c = by_id[i]
            print(f"<!-- §{c.section} {c.section_title} · tier={c.tier} -->")
            print(c.text)
            print()
    if missing:
        sys.exit(f"unknown case id(s): {' '.join(missing)}")


def cmd_select(args, sections, cases) -> None:
    since = args.since or last_release_tag()
    paths = [p for p in changed_paths(since) if not IGNORED_PATHS.search(p)]
    hits: dict[int, dict[str, list[str]]] = {1: {"always (smoke)": []}}
    unmapped: dict[str, list[str]] = {}
    for p in paths:
        matched = False
        for pattern, secs, area in PATH_SECTIONS:
            if re.search(pattern, p, re.IGNORECASE):
                matched = True
                for s in secs:
                    hits.setdefault(s, {}).setdefault(area, []).append(p)
        if not matched and PRODUCT_CODE.match(p) and not TEST_PATHS.search(p):
            m = FEATURE_DIR.search(p)
            module = p.split("/src/", 1)[0]
            area = f"{module} features/{m.group(1)}" if m else module
            unmapped.setdefault(area, []).append(p)
    print(f"changes since {since}: {len(paths)} file(s) outside docs/scripts")
    for s in sorted(hits):
        in_sec = [c for c in cases if c.section == s]
        tiers = {t: sum(1 for c in in_sec if c.tier == t) for t in ("api", "ui", "restore", "hw")}
        tier_txt = " ".join(f"{t}={n}" for t, n in tiers.items() if n)
        print(f"\n§{s} {sections.get(s, '?')}  [{tier_txt}]")
        for area, files in hits[s].items():
            sample = ", ".join(files[:3]) + (f" … (+{len(files) - 3})" if len(files) > 3 else "")
            print(f"   because: {area}" + (f" — {sample}" if files else ""))
    if unmapped:
        n = sum(len(v) for v in unmapped.values())
        print(f"\nUNMAPPED - {n} changed file(s) no catalogue section covers. Likely a NEW feature or area:")
        for area, files in sorted(unmapped.items(), key=lambda kv: -len(kv[1])):
            print(f"   {area}: {len(files)} file(s), e.g. {files[0]}")
        print("   -> write NEW cases for it (SKILL.md 'New features') and add its path to PATH_SECTIONS in this script.")
    print("\nNext: `cases.py list --section <N>` per section, then `cases.py show <ids>`.")


def cmd_sheet(args, sections, cases) -> None:
    by_id = {c.id: c for c in cases}
    missing = [i for i in args.ids if i not in by_id]
    if missing:
        sys.exit(f"unknown case id(s): {' '.join(missing)}")
    print(f"# QA pass {args.pass_id}\n")
    print("| Field | Value |\n|---|---|")
    for f in ("Commit under test", "Rig (server name, PG port, TSE v1/v2)", "Fixture (fresh F / restored R)",
              "Model / agent", "Date", "Deviations from the catalogue"):
        print(f"| {f} | |")
    print("\nStatuses: PASS · PARTIAL · FAIL · REG · NOTE · N/A · BLOCKED · NOT RUN (out of scope). "
          "No personal data: receipts by number, staff by employee number, no credentials.\n")
    print("| Id | Title | Tier | Status | Evidence (API call / SQL / screenshot file, values seen) |")
    print("|---|---|---|---|---|")
    for i in args.ids:
        c = by_id[i]
        print(f"| {c.id} | {c.title.replace('|', '/')} | {c.tier} | | |")
    print("\n## FAIL / REG details\n\nFor each: repro steps · seen vs expected · read-only cause pointer (file:line).")


STATUSES = ("PASS", "PARTIAL", "FAIL", "REG", "NOTE", "N/A", "BLOCKED", "NOT RUN")
RESULT_ROW_RE = re.compile(r"^\|\s*([A-Z]+-\d+[a-z]?)\s*\|")


def read_results(path: Path) -> tuple[dict[str, str], list[tuple[str, str]]]:
    """Header fields and (case id, status) rows of a results file made by `sheet`."""
    header: dict[str, str] = {}
    rows: list[tuple[str, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if RESULT_ROW_RE.match(line) and len(cells) >= 4:
            status = cells[3].upper()
            status = "NOT RUN" if status.startswith("NOT RUN") else (status.split()[0] if status else "")
            if status:
                if status not in STATUSES:
                    sys.exit(f"{cells[0]}: unknown status {cells[3]!r} (use one of {', '.join(STATUSES)})")
                rows.append((cells[0], status))
        elif len(cells) == 2 and cells[0] and not cells[0].startswith("-"):
            header[cells[0]] = cells[1]
    return header, rows


def case_ranges(lines: list[str]) -> dict[str, tuple[int, int]]:
    """Line range [start, end) of every case block."""
    ranges: dict[str, tuple[int, int]] = {}
    current, start = None, 0
    for i, line in enumerate(lines + ["## end"]):
        if line.startswith("### ") or line.startswith("## "):
            if current:
                ranges[current] = (start, i)
            m = CASE_RE.match(line)
            current, start = (m.group(1), i) if m else (None, i)
    return ranges


def cmd_record(args, sections, cases) -> None:
    path = Path(args.results)
    header, rows = read_results(path)
    pass_id = path.stem.removeprefix("results-")
    if not rows:
        sys.exit(f"{path}: no case row carries a status yet")
    lines = CATALOGUE.read_text(encoding="utf-8").splitlines()
    ranges = case_ranges(lines)
    unknown = [cid for cid, _ in rows if cid not in ranges]
    if unknown:
        sys.exit(f"unknown case id(s) in {path}: {' '.join(unknown)}")
    inserts: list[tuple[int, str]] = []
    for cid, status in rows:
        if status == "NOT RUN":  # out of scope this pass: nothing to say in the History
            print(f"{cid:<9} NOT RUN  skipped (no History entry)")
            continue
        start, end = ranges[cid]
        hist = [i for i in range(start, end) if "**History:**" in lines[i] or "**Last:**" in lines[i]]
        if hist:
            lines[hist[-1]] = lines[hist[-1]].rstrip() + f" {status} {pass_id}."
            print(f"{cid:<9} {status:<8} appended to line {hist[-1] + 1}")
        else:
            last = max(i for i in range(start, end) if lines[i].strip())
            inserts.append((last + 1, f"- **History:** {status} {pass_id}."))
            print(f"{cid:<9} {status:<8} new History line after line {last + 1}")
    # the pass itself, as the last row of the section 0.2 table
    sec = next(i for i, l in enumerate(lines) if l.startswith("### 0.2"))
    table_end = next(i for i in range(sec + 1, len(lines)) if lines[i].startswith("|") and not lines[i + 1].startswith("|"))
    counts = ", ".join(f"{n} {s}" for s in STATUSES if (n := sum(1 for _, st in rows if st == s)))
    sha = header.get("Commit under test", "?").strip("` ") or "?"
    fixture = header.get("Fixture (fresh F / restored R)", "") or "?"
    row = (f"| {pass_id} | {header.get('Date') or '?'} | `{sha}` | {fixture}; "
           f"{len(rows)} cases: {counts}; results-{pass_id}.md |")
    inserts.append((table_end + 1, row))
    print(f"§0.2      new row: {row}")
    for at, text in sorted(inserts, reverse=True):
        lines.insert(at, text)
    non_pass = [cid for cid, st in rows if st in ("FAIL", "REG")]
    if non_pass:
        print(f"\nBy hand: describe {' '.join(non_pass)} in section 17 (repro, seen vs expected, cause pointer).")
    if args.write:
        CATALOGUE.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"\nwrote {CATALOGUE.relative_to(REPO)} - review with `git diff`")
    else:
        print("\ndry run - re-run with --write to apply")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    pl = sub.add_parser("list")
    pl.add_argument("--section", type=int, action="append")
    pl.add_argument("--tier", choices=["api", "ui", "restore", "hw"])
    pl.add_argument("--new", action="store_true")
    ps = sub.add_parser("show")
    ps.add_argument("ids", nargs="+")
    pe = sub.add_parser("select")
    pe.add_argument("--since")
    ph = sub.add_parser("sheet")
    ph.add_argument("pass_id")
    ph.add_argument("ids", nargs="+")
    pr = sub.add_parser("record")
    pr.add_argument("results")
    pr.add_argument("--write", action="store_true")
    args = p.parse_args()
    sections, cases = parse()
    {"list": cmd_list, "show": cmd_show, "select": cmd_select, "sheet": cmd_sheet,
     "record": cmd_record}[args.cmd](args, sections, cases)


if __name__ == "__main__":
    main()
