#!/usr/bin/env python3
"""Upward-reference inventory (ZenBon restructuring spec sections 4, 5, 10).

Lists every `import org.example.project...` whose target feature/dto package belongs to a HIGHER
area (platform < ecr < fiscal < accounting < backoffice) than the importing file. Packages missing
from config/structure/package-areas.tsv are reported separately, never dropped.

A FILE-level override (config/structure/file-overrides.tsv, keyed by the path below src/<set>/kotlin/org/example/project)
(and the hand-maintained config/structure/placement-overrides.tsv, which wins over it and may also name a tier or `host`)
beats the package rule on both sides: for the importing file and for the imported type (`a.b.C` resolves to the file
`a/b/C.kt` when it exists, else to its package).

A file's area comes from its own `/features/<name>/` or `/dto/<name>/` path segment; otherwise from
its module (MODULE_AREAS), otherwise `host/wiring` (reported separately from `platform`). An import targets a package through the same
`features.<name>` / `dto.<name>` / `core.domain.enums.<name>` / `core.domain.models.<name>` segment (the shared type packages the move manifest files into area api modules; a file under `core/domain/enums/<name>/` takes that feature's area), or the root package `org.example.project.tse` (pos-tse).

Usage: python3 -I scripts/structure/upward_refs.py [--out docs/audit/....md]
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import NamedTuple

AREAS = ["platform", "ecr", "fiscal", "accounting", "backoffice"]
WIRING = "host/wiring"  # files with no features/dto segment: composition root, DI, schema, plugins, utils
_RANK = {WIRING: 0, **{a: i for i, a in enumerate(AREAS)}}  # wiring ranks with platform: only references above it count
ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOTS = [
    "pos-server", "ui/shared", "fiscal/export", "fiscal/process-data", "ecr/receipt", "ecr/printer", "ecr/printer-core", "ecr/reservation", "ecr/voucher", "ecr/customer", "backoffice/timetracking", "accounting/einvoice", "accounting/banking", "accounting/document",
    "platform/server-common", "platform/money", "platform/secure-xml", "platform/audit",
    "platform/api", "platform/data", "platform/server", "ecr/api", "ecr/data", "ecr/server", "fiscal/api", "fiscal/data", "fiscal/server", "accounting/api", "accounting/data", "accounting/server", "backoffice/api", "backoffice/data", "backoffice/server",  # Stage B2-1b: the extracted blob parts (module-dirs.tsv); absent until the mover has run
]
# Module-wide default area for files with no features/dto segment (spec section 4).
MODULE_AREAS = {"fiscal": "fiscal", "accounting": "accounting", "ecr": "ecr", "backoffice": "backoffice"}
# A package family is "feature-keyed" when its next path/package segment is the feature name: features/<f>/, dto/<f>/ and the
# shared type packages the move manifest files into area api modules (core/domain/enums/<f>/, core/domain/models/<f>/).
_FAMILY = r"features|dto|core/domain/enums|core/domain/models"
_FAMILY_DOTTED = _FAMILY.replace("/", r"\.")
_SEGMENT = re.compile(rf"/(?:{_FAMILY})/([^/]+)/")
_IMPORT = re.compile(r"^import\s+(org\.example\.project\.[\w.]+)", re.MULTILINE)
_WILDCARD = re.compile(r"^import\s+(org\.example\.project\.[\w.]+)\.\*", re.MULTILINE)
_PACKAGE_LINE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
_TARGET = re.compile(rf"\.(?:{_FAMILY_DOTTED})\.(\w+)\.")
_TEST_DIR = re.compile(r"/src/(?:test|\w*[Tt]est)/")


@dataclass(frozen=True)
class Ref:
    from_file: str
    from_area: str
    imported_fqn: str
    to_area: str


def load_table(text: str) -> dict[str, str]:
    table: dict[str, str] = {}
    for line in text.splitlines():
        if line.strip() and not line.startswith("#"):
            name, area = line.split("\t")[:2]
            table[name] = area
    return table


def load_overrides(text: str) -> dict[str, str]:
    """`<dotted sub-package path>\t<area>` rows; raises ValueError naming the line on a bad row."""
    overrides: dict[str, str] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0].strip():
            raise ValueError(f"package-overrides line {number}: expected '<sub-package>\\t<area>', got {line!r}")
        if parts[1].strip() not in AREAS:
            raise ValueError(f"package-overrides line {number}: unknown area {parts[1].strip()!r}")
        overrides[parts[0].strip()] = parts[1].strip()
    return overrides


def package_area_for(package: str, features: dict[str, str], overrides: dict[str, str]) -> str | None:
    """Longest matching override (dot-boundary prefix) wins, else the feature-level row."""
    best = max((k for k in overrides if package == k or package.startswith(k + ".")), key=len, default=None)
    if best is not None:
        return overrides[best]
    return features.get(package.split(".", 1)[0])


def load_file_overrides(text: str) -> dict[str, str]:
    """`<path relative to src/<set>/kotlin/org/example/project>\t<area>` rows; ValueError naming the line on a bad row."""
    overrides: dict[str, str] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0].strip().endswith(".kt"):
            raise ValueError(f"file-overrides line {number}: expected '<path>.kt\\t<area>', got {line!r}")
        if parts[1].strip() not in AREAS:
            raise ValueError(f"file-overrides line {number}: unknown area {parts[1].strip()!r}")
        overrides[parts[0].strip()] = parts[1].strip()
    return overrides


HOST_AREA = "host"  # placement-only area: the file stays in the host module (reported as host/wiring by the scans)
PLACEMENT_AREAS = (*AREAS, HOST_AREA)
TIERS = ("api", "data", "server")


class Placement(NamedTuple):
    area: str            # one of PLACEMENT_AREAS
    tier: str | None     # api | data | server; None = the tier derived from the source module (None for host)


def load_placement_overrides(text: str) -> dict[str, Placement]:
    """Hand-maintained `<path>\t<area>[:<tier>]\t<reason>` rows (config/structure/placement-overrides.tsv).

    The path is relative to src/<set>/kotlin/org/example/project. A tier suffix replaces the tier derived from the
    source module (shared -> api, core-data -> data, pos-server -> server); `host` takes no tier. ValueError names the line."""
    placements: dict[str, Placement] = {}
    first_line: dict[str, int] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("\t")]
        where = f"placement-overrides line {number}"
        if len(parts) < 3 or not parts[0].endswith(".kt") or not parts[1] or not parts[2]:
            raise ValueError(f"{where}: expected '<path>.kt\\t<area>[:<tier>]\\t<reason>', got {line!r}")
        area, colon, tier = parts[1].partition(":")
        if area not in PLACEMENT_AREAS:
            raise ValueError(f"{where}: unknown area {area!r}")
        if colon and tier not in TIERS:
            raise ValueError(f"{where}: unknown tier {tier!r}")
        if area == HOST_AREA and colon:
            raise ValueError(f"{where}: host takes no tier")
        placement = Placement(area, tier if colon else None)
        if parts[0] in placements and placements[parts[0]] != placement:
            raise ValueError(f"{where}: {parts[0]!r} already placed differently on line {first_line[parts[0]]}")
        placements.setdefault(parts[0], placement)
        first_line.setdefault(parts[0], number)
    return placements


def merge_placements(placements: dict[str, Placement], generated: dict[str, str]) -> dict[str, str]:
    """Effective file -> area map: hand placements win over the generated file-overrides (`host` -> host/wiring).

    A path in both with a DIFFERENT area is a ValueError (no silent shadowing); the same area is compatible (a tier only
    refines where the file goes)."""
    merged = dict(generated)
    for path, placement in sorted(placements.items()):
        if path in generated and generated[path] != placement.area:
            raise ValueError(f"placement-overrides: {path!r} is placed in {placement.area!r} but file-overrides.tsv has {generated[path]!r}; remove one of them")
        merged[path] = WIRING if placement.area == HOST_AREA else placement.area
    return merged


SAME_PACKAGE_CAVEAT = ("Files moved by a file-level override leave siblings in their package; same-package references carry no import statement, "
                       "so this scan cannot see them - the mover must check for unqualified use of the moved types.")
_REL_PATH = re.compile(r"/kotlin/org/example/project/(.+)$")


def rel_path(path: str) -> str:
    """Path relative to `src/<set>/kotlin/org/example/project` (the key of a file override); unchanged when outside it."""
    match = _REL_PATH.search("/" + path)
    return match.group(1) if match else path


def validate_file_overrides(overrides: dict[str, str] | dict[str, Placement], files: dict[str, str], label: str = "file-overrides") -> list[str]:
    """One problem per override path that matches no file of the scanned tree (no silent miss)."""
    existing = {rel_path(p) for p in files}
    return [f"{label}: {path!r} matches no file in the tree" for path in sorted(overrides) if path not in existing]


def validate_placement_overrides(placements: dict[str, Placement], files: dict[str, str]) -> list[str]:
    return validate_file_overrides(placements, files, "placement-overrides")


def file_area(path: str, package_area: str | None, file_overrides: dict[str, str] | None) -> str | None:
    """A file-level override beats the package rule."""
    return (file_overrides or {}).get(rel_path(path), package_area)


def import_file(fqn: str) -> str | None:
    """`org.example.project.a.b.C[.Inner]` -> `a/b/C.kt`; None for a function import or a foreign package."""
    prefix = "org.example.project."
    if not fqn.startswith(prefix):
        return None
    packages: list[str] = []
    for segment in fqn[len(prefix):].split("."):
        if segment[:1].isupper():
            return "/".join([*packages, segment]) + ".kt"
        packages.append(segment)
    return None


def _segment(path: str) -> str | None:
    match = _SEGMENT.search("/" + path)
    return match.group(1) if match else None


def _subpackage_of_file(path: str) -> str | None:
    """Dotted directory path below features/ or dto/, starting with the feature name."""
    match = re.search(rf"/(?:{_FAMILY})/(.+)/[^/]+$", "/" + path)
    return match.group(1).replace("/", ".") if match else None


def area_of_file(path: str, table: dict[str, str], overrides: dict[str, str] | None = None) -> str | None:
    """None when the file has a features/dto segment whose package is unmapped."""
    name = _segment(path)
    if name is not None:
        return package_area_for(_subpackage_of_file(path) or name, table, overrides or {})
    return MODULE_AREAS.get(path.split("/", 1)[0], WIRING)


def target_package(fqn: str) -> str | None:
    if fqn.startswith("org.example.project.tse."):
        return "tse"
    match = _TARGET.search(fqn)
    return match.group(1) if match else None


_TARGET_PATH = re.compile(rf"\.(?:{_FAMILY_DOTTED})\.(\w[\w.]*)$")


def target_path(fqn: str) -> str | None:
    """Dotted path after `features.`/`dto.`/`core.domain.enums.`/`core.domain.models.` (feature name first); the pos-tse root package maps to `tse...`."""
    if fqn.startswith("org.example.project.tse."):
        return fqn[len("org.example.project."):]
    match = _TARGET_PATH.search(fqn)
    return match.group(1) if match else None


def import_area(fqn: str, table: dict[str, str], overrides: dict[str, str], file_overrides: dict[str, str], rel_files: set[str]) -> str | None:
    """Area of an imported type: its file's override when that file exists, else its package's area."""
    resolved = import_file(fqn)
    if resolved is not None and resolved in file_overrides and resolved in rel_files:
        return file_overrides[resolved]
    target = target_path(fqn)
    return package_area_for(target, table, overrides) if target else None


def find_upward(files: dict[str, str], table: dict[str, str], overrides: dict[str, str] | None = None,
                file_overrides: dict[str, str] | None = None) -> list[Ref]:
    refs: list[Ref] = []
    ov = overrides or {}
    fo = file_overrides or {}
    rel_files = {rel_path(p) for p in files}
    by_package: dict[str, list[str]] = {}
    for path, text in sorted(files.items()):
        match = _PACKAGE_LINE.search(text)
        if match:
            by_package.setdefault(match.group(1), []).append(path)
    for path, text in sorted(files.items()):
        from_area = file_area(path, area_of_file(path, table, ov), fo)
        if from_area is None:
            continue
        wildcards = {package for package in _WILDCARD.findall(text) if by_package.get(package)}
        for package in sorted(wildcards):  # a wildcard import is an edge to every file of the package, each at its own area
            for target in by_package[package]:
                if target == path:
                    continue
                to_area = file_area(target, area_of_file(target, table, ov), fo)
                if to_area is not None and _RANK[to_area] > _RANK[from_area]:
                    refs.append(Ref(path, from_area, f"{package}.* [{target.rsplit('/', 1)[-1]}]", to_area))
        for fqn in _IMPORT.findall(text):
            if fqn.endswith(".") and fqn[:-1] in wildcards:
                continue
            to_area = import_area(fqn, table, ov, fo, rel_files)
            if to_area is not None and _RANK[to_area] > _RANK[from_area]:
                refs.append(Ref(path, from_area, fqn, to_area))
    return refs


def find_unmapped(files: dict[str, str], table: dict[str, str]) -> dict[str, list[str]]:
    """Package name -> files that mention it (own path segment or imported)."""
    found: dict[str, list[str]] = defaultdict(list)
    for path, text in sorted(files.items()):
        names = [n for n in [_segment(path)] if n]
        names += [t for t in (target_package(f) for f in _IMPORT.findall(text)) if t]
        for name in dict.fromkeys(names):
            if name not in table:
                found[name].append(path)
    return dict(found)


CAVEATS = """> **Read before using the counts**
>
> - **`host/wiring`** is every file with no `features/<name>/` or `dto/<name>/` path segment (DI modules, `SchemaManager`, plugins, `core/data`, utilities). It is reported as its own group and is NOT part of `platform`. A composition root legitimately sees every area, so `host/wiring` rows are informational, not layering defects by themselves. Only `platform` rows (files inside a mapped platform package such as `features/auth`) are candidate defects.
> - Packages listed under **Unmapped** (settings, reports, websocket, admin, csv, extensions, rotation, sync, ...) are **EXCLUDED** from every count: as sources they are skipped and as import targets they are ignored.
> - composeApp, adminApp and androidApp were not scanned, and test sources were not scanned."""


def render_report(refs: list[Ref], unmapped: dict[str, list[str]], scanned: int) -> str:
    pairs: dict[tuple[str, str], list[Ref]] = defaultdict(list)
    for ref in refs:
        pairs[(ref.from_area, ref.to_area)].append(ref)
    out = ["# Upward references", "", CAVEATS, "", f"Generated by `scripts/structure/upward_refs.py`. {scanned} production Kotlin files scanned, "
           f"{len(refs)} upward references.", "", "## Counts per pair", "", "| from | to | references | files |", "|---|---|---|---|"]
    for (src, dst), items in sorted(pairs.items()):
        out.append(f"| {src} | {dst} | {len(items)} | {len({r.from_file for r in items})} |")
    for (src, dst), items in sorted(pairs.items()):
        out += ["", f"## {src} -> {dst} ({len(items)})", ""]
        out += [f"- `{r.from_file}` imports `{r.imported_fqn}`" for r in items]
    out += ["", f"## Unmapped packages ({len(unmapped)})", ""]
    out += [f"- `{name}`: {len(paths)} file(s), e.g. `{paths[0]}`" for name, paths in sorted(unmapped.items())] or ["None."]
    return "\n".join(out) + "\n"


def collect_files() -> dict[str, str]:
    dirs = SOURCE_ROOTS
    files: dict[str, str] = {}
    for module in dirs:
        for path in (ROOT / module / "src").rglob("*.kt"):
            rel = path.relative_to(ROOT).as_posix()
            if not _TEST_DIR.search("/" + rel):
                files[rel] = path.read_text(encoding="utf-8")
    return files


def load_file_overrides_file() -> dict[str, str]:
    path = ROOT / "config/structure/file-overrides.tsv"
    return load_file_overrides(path.read_text(encoding="utf-8")) if path.exists() else {}


def load_placement_overrides_file() -> dict[str, Placement]:
    path = ROOT / "config/structure/placement-overrides.tsv"
    return load_placement_overrides(path.read_text(encoding="utf-8")) if path.exists() else {}


def main(argv: list[str]) -> int:
    table = load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
    overrides = load_overrides((ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8"))
    files = collect_files()
    generated = load_file_overrides_file()
    placements = load_placement_overrides_file()
    try:
        file_overrides = merge_placements(placements, generated)
    except ValueError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    problems = validate_file_overrides(generated, files) + validate_placement_overrides(placements, files)
    report = render_report(find_upward(files, table, overrides, file_overrides), find_unmapped(files, table), len(files))
    if "--out" in argv:
        Path(ROOT / argv[argv.index("--out") + 1]).write_text(report, encoding="utf-8")
    else:
        sys.stdout.write(report)
    for problem in problems:
        sys.stderr.write(problem + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
