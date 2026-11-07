#!/usr/bin/env python3
"""Generate docs/claude/code-map.md: where each business feature lives across the Gradle modules.

A feature is spread over several modules under the same package name (tables and interfaces in
the <area>-data modules, implementations and routes in pos-server, DTOs and API clients in shared, screens in the
clients). This script lists, per domain and feature, the directory in every module plus the route
files, permission rules, Exposed tables and API clients, so a feature can be found without searching.

Usage:  python3 scripts/gen-code-map.py          # rewrite docs/claude/code-map.md
        python3 scripts/gen-code-map.py --check  # exit 1 if the committed file is out of date

Reads `git ls-files`, so untracked and ignored files never appear. No dependencies beyond Python 3.
"""

import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "claude" / "code-map.md"
PKG = "org/example/project/"

# Domain grouping used for navigation only; it does not change any package.
DOMAINS = {
    "POS and ordering": "order table tables floorplan bill payment promotion pfand rotation reservation openinghours cardterminal printer voucher customer",
    "People and access": "employee employees staff auth terminal permissions timetracking launch main",
    "Catalogue": "product category choicegroup extras",
    "Fiscal": "tse zbon kassenbuch dsfinvk monthlyclose",
    "Accounting and documents": "document documents salesinvoice salesinvoices invoices partner supplier debtor debtors creditor contract contracts bank allocation allocations matching datev datevexportlog datevxml customexport dataexport export finance financialdocuments",
    "Reporting": "statistics reports report dashboard dailyreport",
    "Platform": "setup update appupdate backup maintenance scheduler issuereport startpage changelog clienterror admin audit auditlog settings bootstrap firstterminal notifications websocket sync extensions",
}

# (module label, path prefix, feature-directory marker) in reading order.
LOCATIONS = [
    ("pos-server", "pos-server/src/main/kotlin/" + PKG, "features/"),
    ("platform-server", "platform/server/src/main/kotlin/" + PKG, "features/"),
    ("platform-data", "platform/data/src/main/kotlin/" + PKG, "features/"),
    ("ecr-server", "ecr/server/src/main/kotlin/" + PKG, "features/"),
    ("ecr-data", "ecr/data/src/main/kotlin/" + PKG, "features/"),
    ("ecr-api (dto)", "ecr/api/src/commonMain/kotlin/" + PKG + "core/domain/dto/", ""),
    ("fiscal-server", "fiscal/server/src/main/kotlin/" + PKG, "features/"),
    ("fiscal-data", "fiscal/data/src/main/kotlin/" + PKG, "features/"),
    ("fiscal-api (dto)", "fiscal/api/src/commonMain/kotlin/" + PKG + "core/domain/dto/", ""),
    ("accounting-server", "accounting/server/src/main/kotlin/" + PKG, "features/"),
    ("accounting-data", "accounting/data/src/main/kotlin/" + PKG, "features/"),
    ("accounting-api (dto)", "accounting/api/src/commonMain/kotlin/" + PKG + "core/domain/dto/", ""),
    ("backoffice-server", "backoffice/server/src/main/kotlin/" + PKG, "features/"),
    ("backoffice-data", "backoffice/data/src/main/kotlin/" + PKG, "features/"),
    ("backoffice-api (dto)", "backoffice/api/src/commonMain/kotlin/" + PKG + "core/domain/dto/", ""),
    ("platform-api (dto)", "platform/api/src/commonMain/kotlin/" + PKG + "core/domain/dto/", ""),
    ("ui-till", "ui/till/src/commonMain/kotlin/" + PKG, "features/"),
    ("ui-backoffice", "ui/backoffice/src/wasmJsMain/kotlin/" + PKG + "admin/", "features/"),
    ("ui-shared", "ui/shared/src/commonMain/kotlin/" + PKG, "features/"),
]
# Where the per-feature API clients live: the area api modules.
CLIENT_ROOTS = (
    "platform/api/src/commonMain/kotlin/" + PKG + "core/data/remote/",
    "ecr/api/src/commonMain/kotlin/" + PKG + "core/data/remote/",
    "fiscal/api/src/commonMain/kotlin/" + PKG + "core/data/remote/",
    "accounting/api/src/commonMain/kotlin/" + PKG + "core/data/remote/",
    "backoffice/api/src/commonMain/kotlin/" + PKG + "core/data/remote/",
)
OTHER_MODULES = {
    "accounting/document": "documents",
    "ecr/customer": "customer",
    "ecr/voucher": "voucher",
    "platform/audit": "audit",
    "ecr/reservation": "reservation",
    "backoffice/timetracking": "timetracking",
    "ecr/printer": "printer",
    "ecr/printer-core": "printer",
}


def short_root(directory):
    """`<module root>/.../core/data/remote/`: the module is named, so a client is found under the root that holds it."""
    module = directory.split("/src/", 1)[0]
    return module + "/.../core/data/remote/"


def tracked():
    out = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True)
    return [line for line in out.split("\n") if line]


def norm(name):
    return name.lower()


def feature_of(path, prefix, marker):
    if not path.startswith(prefix) or not path.endswith(".kt"):
        return None
    rest = path[len(prefix):]
    if marker:
        if not rest.startswith(marker):
            return None
        rest = rest[len(marker):]
    parts = rest.split("/")
    return parts[0] if len(parts) > 1 else None


def build(files):
    dirs = defaultdict(lambda: defaultdict(int))  # feature -> "module: dir" -> file count
    routes = defaultdict(set)
    tables = defaultdict(set)
    clients = defaultdict(set)
    for path in files:
        for label, prefix, marker in LOCATIONS:
            feat = feature_of(path, prefix, marker)
            if feat is None:
                continue
            key = norm(feat)
            where = prefix + marker + feat
            dirs[key][(label, where)] += 1
            name = path.rsplit("/", 1)[1]
            if label in ("pos-server", "platform-server") and name.endswith("Routes.kt"):
                routes[key].add(path)
            if label == "platform-data" and "/database/tables/" in path and name.endswith("Table.kt"):
                tables[key].add(name[:-3])
        root = next((r for r in CLIENT_ROOTS if path.startswith(r)), None)
        if root and path.endswith("ApiClient.kt"):
            clients[path.rsplit("/", 1)[1][:-3]].add(root)
        for module, feat in OTHER_MODULES.items():
            if path.startswith(module + "/src/main/") and path.endswith(".kt"):
                dirs[feat][(module, module + "/src/main")] += 1
    return dirs, routes, tables, clients


def rules_for(files):
    rules = {}
    for path in files:
        if "/infrastructure/security/rules/" in path and path.endswith("Rules.kt"):
            rules[path.rsplit("/", 1)[1][:-8].lower()] = path
    return rules


def domain_of(feature):
    for domain, names in DOMAINS.items():
        if feature in names.split():
            return domain
    return "Unassigned"


def render(files):
    dirs, routes, tables, clients = build(files)
    rules = rules_for(files)
    by_domain = defaultdict(list)
    for feature in sorted(dirs):
        by_domain[domain_of(feature)].append(feature)

    lines = [
        "# Code map",
        "",
        "Generated by `python3 scripts/gen-code-map.py` from `git ls-files` — do not edit by hand.",
        "A feature is spread over several modules under the **same package name**; this lists where.",
        "Counts are tracked Kotlin files under each directory (main source sets only, tests excluded).",
        "",
        "How to read a row: `pos-server` holds routes and implementations, the `<area>-data` modules the Exposed tables,",
        "models and repository/service interfaces, the `<area>-api` modules the DTOs, the client columns the screens.",
        "",
    ]
    for domain in list(DOMAINS) + ["Unassigned"]:
        feats = by_domain.get(domain)
        if not feats:
            continue
        lines += [f"## {domain}", ""]
        for feature in feats:
            lines.append(f"### {feature}")
            order = [m[0] for m in LOCATIONS] + list(OTHER_MODULES)
            for (label, where), count in sorted(dirs[feature].items(), key=lambda kv: (order.index(kv[0][0]), kv[0][1])):
                lines.append(f"- {label}: `{where}/` ({count} file{'' if count == 1 else 's'})")
            for r in sorted(routes.get(feature, ())):
                lines.append(f"- route: `{r}`")
            for name, path in rules.items():
                if name == feature or (len(feature) > 4 and (name.startswith(feature) or feature.startswith(name))):
                    lines.append(f"- permission rules: `{path}`")
            if tables.get(feature):
                lines.append("- tables: " + ", ".join(f"`{t}`" for t in sorted(tables[feature])))
            api = sorted(n for n in clients if n.lower().startswith(feature) or feature in n.lower())
            for root in sorted({d for n in api for d in clients[n]}):
                named = ", ".join(f"`{n}`" for n in api if root in clients[n])
                lines.append(f"- api clients (`{short_root(root)}`): {named}")
            lines.append("")
    lines += [
        "## Not feature-scoped",
        "",
        "- Server wiring: `pos-server/src/main/kotlin/" + PKG + "infrastructure/` (plugins, DI modules, security rules)",
        "- Server config, websocket/SSE hub, security helpers: `pos-server/.../core/`",
        "- Flyway migrations: `pos-server/src/main/resources/db/migration/`",
        "- Operator manual pages: `pos-server/src/main/resources/manual/`",
        "- Money kernel: `platform/money/`; e-invoice: `accounting/einvoice/`; bank statements: `accounting/banking/`; DATEV writers: `fiscal/export/`",
        "- TSE driver (protected, do not modify): `pos-tse/`",
        "",
    ]
    return "\n".join(lines)


def main():
    text = render(tracked())
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        if current != text:
            print("docs/claude/code-map.md is out of date; run python3 scripts/gen-code-map.py", file=sys.stderr)
            sys.exit(1)
        return
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)} ({len(text.splitlines())} lines)")


if __name__ == "__main__":
    main()
