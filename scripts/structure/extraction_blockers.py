#!/usr/bin/env python3
"""Extraction blockers per target area (ZenBon restructuring spec sections 4, 5, 10, 11, 12).

For each area that will be extracted into its own Gradle modules (platform, ecr, fiscal, accounting,
backoffice) this lists what stops it from compiling on its own: every upward reference
(upward_refs.find_upward, with the section 12 overrides) plus every package split across two modules
of the same area. Pure functions take rows/refs; only main() reads files.

Blocker = (from_file, from_target_module, imported_fqn, to_target_module, kind). The dict returned by
`blockers` is keyed by the area that OWNS the blocker, i.e. the area of the importing file's MANIFEST
target (`platform-server` -> platform, `ecr-data` -> ecr; the area that cannot be extracted until the
reference is resolved). Only a file whose manifest target is `host` is host/wiring (informational). A
file absent from the manifest (an already-extracted module such as `ecr/printer`) keeps the area the
upward-reference scan gave it. A reference whose destination is not above the owner after this
re-attribution is dropped. Sources under `ui/` are not blockers at all (spec 5.3: ui may depend on any
area api); `ui_reference_count` counts them for one informational line. Only non-empty areas appear,
in extraction order, with `host/wiring` last.

How kind is decided
- "upward-data": the imported type's package contains a segment in DATA_SEGMENTS (`tables`, `table`,
  `models`, `model`, `dto`, `enums`, `entities`): a database table, domain model, DTO or enum. The
  candidate fix is to move the type down or reference it by id.
- "upward-behaviour": every other upward import: `service`, `repository`, `factory`, `mapper`, `routes`,
  `provider`, `presentation` and also any package the rule does not recognise (deliberately
  conservative: an unrecognised package is treated as behaviour, the candidate fix being a port).
- "same-area-split": not an import. A package (non-test rows of the manifest) whose directories occur
  in two or more source modules and map to two or more target modules of the SAME area (for example
  `kassenbuch.domain` -> fiscal-server and fiscal-data). One blocker per such package and area:
  from_file is `<source module>/<package>` of the first occurrence, imported_fqn the package, the two
  modules are the first two targets in sorted order. It is a blocker only while the split is PENDING
  (a row whose source module is not its target, i.e. a file still to move) or the package has a
  `collision` (the same file name in two modules). A split between modules of one area with nothing
  pending is SETTLED: R6 lets server -> data -> api depend on each other inside an area, so it is
  legitimate and is listed under the informational "Split packages (settled)" section instead.
  A row whose every file is placed to another target by a file row (`fully_placed_rows`) holds nothing of
  its package's target: it is left out of the split, so it neither adds that target nor keeps it pending
  (Task 9: pos-server's `core.config`/`utils` residue, only accounting/fiscal/ecr files after the server move).

File-level overrides (config/structure/file-overrides.tsv): `file_targets` maps an overridden file (path below
org/example/project) to its own target module (move_manifest.file_rows). Such a file reports that module as
from_target_module (so it is owned by that file's area, not its package's), and an import of it reports the same
module as to_target_module. The refs themselves already carry the file-level areas (upward_refs.find_upward).

Module resolution
- from_target_module: the manifest target of the importing file's (source module, package), the package
  taken from the file path (directory); a file outside shared/core-data/pos-server (not in the manifest)
  reports its source root, e.g. `ecr/printer`.
- to_target_module: the manifest target of the imported package (leading lower-case segments of the
  FQN). If the package occurs in several source modules, the target in the importer's own source-module
  layer (api/data/server) wins, else the first in sorted order. Imports from the pos-tse root package
  report `pos-tse`. A package absent from the manifest but present in an already-extracted module
  (accounting/document, ecr/voucher, ...) reports that module's source root; a package found nowhere
  reports `<area>-?`.

- dest_area: the area of a to_target_module (`fiscal-server`, `accounting/document` and `pos-tse` -> fiscal),
  used for the per-area "blockers by destination area" table and the decisions block.

Usage: python3 -I scripts/structure/extraction_blockers.py [--out docs/audit/2026-10-26-extraction-blockers.md]
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import NamedTuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import move_manifest as mm  # noqa: E402
import upward_refs as ur  # noqa: E402

ROOT = ur.ROOT
AREAS = ur.AREAS
HOST = ur.WIRING
HOST_TARGET = "host"
DATA_SEGMENTS = frozenset({"tables", "table", "models", "model", "dto", "enums", "entities"})
KIND_DATA, KIND_BEHAVIOUR, KIND_SPLIT = "upward-data", "upward-behaviour", "same-area-split"
KINDS = (KIND_DATA, KIND_BEHAVIOUR, KIND_SPLIT)
PLANS = (("B2-1", "platform"), ("B2-2", "ecr"), ("B2-3", "fiscal"), ("B2-4", "accounting"), ("B2-5", "backoffice"), ("B2-6", HOST))
_PROJECT_PREFIX = mm.PROJECT_PACKAGE + "."
_PACKAGE_DIR = re.compile(r"/kotlin/org/example/project/(.*)/[^/]+$")
_SOURCE_ROOT = re.compile(r"^(.*?)/src/")
_EXTRACTED_ROOT = re.compile(r"^(.*?)/src/[^/]+/kotlin/")


class Blocker(NamedTuple):
    from_file: str
    from_target_module: str
    imported_fqn: str
    to_target_module: str
    kind: str


def imported_package(fqn: str) -> str:
    """Package of an imported FQN relative to org.example.project: segments up to the first upper-case one,
    or all but the last for a top-level function import."""
    segments = fqn.removeprefix(_PROJECT_PREFIX).split(".")
    package: list[str] = []
    for segment in segments:
        if segment[:1].isupper():
            return ".".join(package)
        package.append(segment)
    return ".".join(package[:-1])


def classify(fqn: str) -> str:
    return KIND_DATA if DATA_SEGMENTS & set(imported_package(fqn).split(".")) else KIND_BEHAVIOUR


def _suffix(target: str) -> str:
    return target.rsplit("-", 1)[-1]


def _target_index(manifest: list[mm.Row]) -> dict[tuple[str, str], set[str]]:
    index: dict[tuple[str, str], set[str]] = defaultdict(set)
    for module, source_set, package, target, _ in manifest:
        if not mm.is_test(source_set):
            index[(module, package)].add(target)
    return index


def _from_module(path: str, index: dict[tuple[str, str], set[str]], file_targets: dict[str, str] | None = None) -> str:
    if file_targets and ur.rel_path(path) in file_targets:  # a file-level override moves the file out of its package's target
        return file_targets[ur.rel_path(path)]
    located = mm.package_of(path, "")
    if located is not None:
        targets = index.get((located[0], located[2] or mm.ROOT_PACKAGE))
        if targets:
            return sorted(targets)[0]
    root = _SOURCE_ROOT.match(path)
    return root.group(1) if root else path


def _to_module(fqn: str, to_area: str, from_module: str, index: dict[tuple[str, str], set[str]],
               extracted: dict[str, str] | None = None, file_targets: dict[str, str] | None = None) -> str:
    if fqn.startswith(_PROJECT_PREFIX + "tse."):
        return "pos-tse"
    resolved = ur.import_file(fqn)
    if file_targets and resolved in file_targets:
        return file_targets[resolved]
    package = imported_package(fqn)
    targets = sorted({t for (_, p), ts in index.items() if p == package for t in ts})
    if not targets:
        return (extracted or {}).get(package, f"{to_area}-?")
    same_layer = [t for t in targets if _suffix(t) == _suffix(from_module)]
    return (same_layer or targets)[0]


class Settled(NamedTuple):
    package: str
    area: str
    targets: tuple[str, ...]


def colliding_packages(files: dict[str, str]) -> frozenset[str]:
    """Packages (relative to org.example.project) holding the same non-test file name in two or more source modules."""
    modules: dict[tuple[str, str], set[str]] = defaultdict(set)
    for path, text in files.items():
        located = mm.package_of(path, text)
        if located is not None and not mm.is_test(located[1]):
            modules[(located[2] or mm.ROOT_PACKAGE, path.rsplit("/", 1)[-1])].add(located[0])
    return frozenset(package for (package, _), found in modules.items() if len(found) > 1)


def fully_placed_rows(manifest: list[mm.Row], file_rows: list[mm.FileRow]) -> frozenset[tuple[str, str, str]]:
    """`(module, source set, package)` of the manifest rows whose every file has a file row (move_manifest owns the rule)."""
    return mm.fully_placed_rows(manifest, file_rows)


def _split_candidates(manifest: list[mm.Row], collisions: frozenset[str],
                      fully_placed: frozenset[tuple[str, str, str]] = frozenset()) -> list[tuple[str, str, list[str], dict[str, str], bool]]:
    """(package, area, area targets, target -> first source module, pending-or-colliding) for every package split inside one area."""
    by_package: dict[str, dict[str, str]] = defaultdict(dict)  # package -> target -> first source module
    pending: set[str] = set()
    for module, source_set, package, target, _ in manifest:
        if not mm.is_test(source_set) and (module, source_set, package) not in fully_placed:
            by_package[package].setdefault(target, module)
            if module != target:
                pending.add(package)
    found = []
    for package, targets in sorted(by_package.items()):
        per_area: dict[str, list[str]] = defaultdict(list)
        for target in sorted(targets):
            if target not in ("host", mm.UNMAPPED):
                per_area[target.rsplit("-", 1)[0]].append(target)
        for area, area_targets in per_area.items():
            if len(area_targets) > 1:
                found.append((package, area, area_targets, targets, package in pending or package in collisions))
    return found


def same_area_splits(manifest: list[mm.Row], collisions: frozenset[str] = frozenset(),
                     fully_placed: frozenset[tuple[str, str, str]] = frozenset()) -> dict[str, list[Blocker]]:
    found: dict[str, list[Blocker]] = defaultdict(list)
    for package, area, area_targets, targets, blocking in _split_candidates(manifest, collisions, fully_placed):
        if blocking:
            first, second = area_targets[:2]
            found[area].append(Blocker(f"{targets[first]}/{package}", first, package, second, KIND_SPLIT))
    return found


def settled_splits(manifest: list[mm.Row], collisions: frozenset[str] = frozenset(),
                   fully_placed: frozenset[tuple[str, str, str]] = frozenset()) -> list[Settled]:
    """Packages split between modules of one area with no pending move and no collision (informational)."""
    return [Settled(package, area, tuple(area_targets))
            for package, area, area_targets, _, blocking in _split_candidates(manifest, collisions, fully_placed) if not blocking]


def is_ui_source(path: str) -> bool:
    return path.startswith("ui/")


def ui_reference_count(refs: list[ur.Ref]) -> int:
    return sum(1 for ref in refs if is_ui_source(ref.from_file))


def owner_area(ref: ur.Ref, from_module: str) -> str:
    """Area of the importing file's manifest target; host for a `host` target; the scanned area when outside the manifest."""
    if from_module == HOST_TARGET:
        return HOST
    area = from_module.rsplit("-", 1)[0]
    return area if area in AREAS and "/" not in from_module else ref.from_area


def blockers(manifest: list[mm.Row], refs: list[ur.Ref], extracted: dict[str, str] | None = None,
             file_targets: dict[str, str] | None = None, collisions: frozenset[str] = frozenset(),
             fully_placed: frozenset[tuple[str, str, str]] = frozenset()) -> dict[str, list[Blocker]]:
    index = _target_index(manifest)
    found: dict[str, list[Blocker]] = defaultdict(list)
    for ref in refs:
        if is_ui_source(ref.from_file):
            continue
        from_module = _from_module(ref.from_file, index, file_targets)
        owner = owner_area(ref, from_module)
        if owner != HOST and ur._RANK[ref.to_area] <= ur._RANK[owner]:
            continue
        found[owner].append(Blocker(ref.from_file, from_module, ref.imported_fqn,
                                    _to_module(ref.imported_fqn, ref.to_area, from_module, index, extracted, file_targets), classify(ref.imported_fqn)))
    for area, items in same_area_splits(manifest, collisions, fully_placed).items():
        found[area].extend(items)
    return {area: found[area] for area in (*AREAS, HOST) if found.get(area)}


def counts_by_kind(items: list[Blocker]) -> dict[str, int]:
    return {kind: sum(1 for b in items if b.kind == kind) for kind in KINDS}


def _from_package(blocker: Blocker) -> str:
    if "/kotlin/" not in blocker.from_file:
        return blocker.from_file
    match = _PACKAGE_DIR.search(blocker.from_file)
    if match:
        return match.group(1).replace("/", ".")
    return mm.ROOT_PACKAGE


def artefact_packages(manifest: list[mm.Row], files: dict[str, str], areas: dict[str, str], overrides: dict[str, str],
                      file_overrides: dict[str, str] | None = None, placements: dict[str, ur.Placement] | None = None) -> frozenset[str]:
    """Packages whose placement is a package-level artefact: the move manifest's mixed packages and its split packages."""
    mixed = {m.package for m in mm.mixed_packages(files, areas, overrides, file_overrides, placements)}
    return frozenset(mixed | set(mm.split_packages(manifest)))


def is_artefact(blocker: Blocker, packages: frozenset[str]) -> bool:
    """True when the importing file sits in a mixed or split package of the move manifest (or in a raw `ui/*` root)."""
    return _from_package(blocker) in packages or blocker.from_target_module.startswith("ui/")


def manifest_gaps(found: dict[str, list[Blocker]]) -> list[str]:
    """Distinct imported packages whose target module fell back to `<area>-?` (no manifest entry)."""
    return sorted({imported_package(b.imported_fqn) for items in found.values() for b in items if b.to_target_module.endswith("-?")})


def host_split(items: list[Blocker]) -> tuple[int, int]:
    """(sourced from files whose manifest target is host, sourced from files outside the manifest)."""
    at_host = sum(1 for b in items if b.from_target_module == HOST_TARGET)
    return at_host, len(items) - at_host


def clusters(items: list[Blocker]) -> list[tuple[tuple[str, str], list[Blocker]]]:
    """Group by (from package, imported package), largest first (ties by name)."""
    grouped: dict[tuple[str, str], list[Blocker]] = defaultdict(list)
    for b in items:
        grouped[(_from_package(b), imported_package(b.imported_fqn))].append(b)
    return sorted(grouped.items(), key=lambda kv: (-len(kv[1]), kv[0]))


def dest_area(target_module: str) -> str | None:
    """Area of a destination module: `fiscal-server`, `accounting/document`, `pos-tse` (fiscal); None for host/platform-less names."""
    if target_module == "pos-tse":
        return "fiscal"
    head = re.split(r"[-/]", target_module, maxsplit=1)[0]
    return head if head in AREAS else None


def by_destination(area: str, items: list[Blocker]) -> list[tuple[str, int]]:
    """(destination area, references), largest first; same-area splits are not cross-area references."""
    counts: dict[str, int] = defaultdict(int)
    for b in items:
        dest = dest_area(b.to_target_module)
        if b.kind != KIND_SPLIT and dest is not None:
            counts[dest] += 1
    return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))


def kassenbuch_data_imports(found: dict[str, list[Blocker]]) -> list[Blocker]:
    """ecr-owned data/api files importing a type of the Kassenbuch family (package segment `kassenbuch`)."""
    return sorted((b for b in found.get("ecr", []) if b.from_target_module in ("ecr-data", "ecr-api")
                   and "kassenbuch" in imported_package(b.imported_fqn).split(".")), key=lambda b: (b.from_file, b.imported_fqn))


def fiscal_to_accounting(found: dict[str, list[Blocker]]) -> list[Blocker]:
    return [b for b in found.get("fiscal", []) if b.kind != KIND_SPLIT and dest_area(b.to_target_module) == "accounting"]


def _decisions(found: dict[str, list[Blocker]]) -> list[str]:
    kassenbuch, accounting = kassenbuch_data_imports(found), fiscal_to_accounting(found)
    if not kassenbuch and not accounting:
        return []
    out = ["## Decisions needed before B2-2 / B2-3", "",
           "Pinned detections generated from the data above; each is a placement decision, not a mechanical port.", ""]
    if kassenbuch:
        files = sorted({b.from_file for b in kassenbuch})
        out += [f"- **ecr data owning Kassenbuch-family types ({len(kassenbuch)} references in {len(files)} files).** ecr-data / ecr-api files import types of the "
                "Kassenbuch family (package `kassenbuch`), e.g. ZBon and the tip-payout tables. This undermines the spec section 12 placement of those rows in ecr "
                "unless the Kassenbuch header moves down or ZBon holds only an id.", ""]
        out += [f"  - `{b.from_file}` ({b.from_target_module}) imports `{b.imported_fqn}` ({b.to_target_module})" for b in kassenbuch]
        out.append("")
    if accounting:
        plural = "reference" if len(accounting) == 1 else "references"
        out += [f"- **fiscal -> accounting ({len(accounting)} {plural}).** This contradicts the area order fiscal < accounting and is a placement decision "
                "(DATEV Buchungsstapel and Document services), not a set of ports.", ""]
    return out


def _summary(items: list[Blocker]) -> str:
    c = counts_by_kind(items)
    return f"{len(items)} blockers ({c[KIND_DATA]} upward-data, {c[KIND_BEHAVIOUR]} upward-behaviour, {c[KIND_SPLIT]} same-area-split)"


def _caveats(found: dict[str, list[Blocker]], packages: frozenset[str], ui_refs: int) -> list[str]:
    at_host, elsewhere = host_split(found.get(HOST, []))
    per_area = ", ".join(f"{a} {sum(1 for b in found.get(a, []) if is_artefact(b, packages))}/{len(found.get(a, []))}" for a in (*AREAS, HOST))
    gaps = manifest_gaps(found)
    return ["## Caveats", "",
            f"- **Ownership follows the manifest target.** A blocker belongs to the area of the importing file's move-manifest target, so a file without a `features/<name>/` segment "
            f"(`utils.builder.tse`, `core.utils.Bill*`, `core.data.remote*`) is a blocker of the platform module it moves into. Only files whose manifest target is `host` are host/wiring. "
            f"{at_host + elsewhere} host-sourced references: {at_host} from files whose manifest target is `host`, {elsewhere} from files outside the manifest; the B2-6 count is informational.",
            f"- **Placement artefacts.** A blocker marked `artefact?` has its importing file in a mixed package (several features' files under one package row, e.g. `core.data.remote*`) "
            f"or a split package of the move manifest. It may come from package-level placement, not from a real dependency of that area; the mover splits such packages by file. "
            f"Artefacts / blockers per area: {per_area}.",
            f"- **`ui/*` sources are excluded.** Spec section 5.3 lets ui modules depend on any area api, so their upward references are not blockers. Excluded: {ui_refs} reference(s) from `ui/*` sources.",
            f"- **`<area>-?` target.** It marks an imported package that is in neither the move manifest nor an already-extracted module outside it (accounting/document, ecr/voucher, "
            f"ecr/customer, ecr/printer, ...); packages of those modules are reported with their source root instead. Distinct unresolved packages ({len(gaps)}): "
            + (", ".join(f"`{g}`" for g in gaps) if gaps else "none") + ".", ""]


def _plan_note(plan: str, items: list[Blocker], packages: frozenset[str]) -> str:
    if plan == "B2-1":
        flagged = sum(1 for b in items if is_artefact(b, packages))
        return f" - {flagged} of them `artefact?` (files in mixed or split packages, mostly `core.data.remote*` API clients), {len(items) - flagged} not flagged (real platform dependencies)"
    if plan == "B2-6":
        return " - excludes platform-targeted files (counted under B2-1) and `ui/*` sources"
    return ""


def _settled_section(settled: list[Settled]) -> list[str]:
    out = [f"## Split packages (settled) ({len(settled)})", "",
           "Informational, not blockers: a package split between modules of one area with no file left to move and no file-name collision. "
           "R6 lets server -> data -> api depend on each other inside an area, so these splits are legitimate.", ""]
    out += [f"- `{s.package}` ({s.area}): {', '.join(s.targets)}" for s in settled] or ["None."]
    return out + [""]


def render_report(found: dict[str, list[Blocker]], packages: frozenset[str] = frozenset(), ui_refs: int = 0,
                  settled: list[Settled] | tuple[()] = ()) -> str:
    total = sum(len(v) for a, v in found.items() if a != HOST)
    out = ["# Extraction blockers per area", "",
           "Generated by `scripts/structure/extraction_blockers.py` from the move manifest and the upward-reference scan (spec section 12 overrides applied). "
           "A blocker is owned by the area of the importing file's move-manifest target: that area cannot be extracted until the reference is removed (data moved down or referenced by id; "
           "behaviour put behind a port). Kinds are decided as described in the script docstring.", "",
           f"{total} blockers in the five extractable areas. `host/wiring` references are counted separately and are **informational**: the composition root and DI wiring legitimately see every area.", ""]
    out += _caveats(found, packages, ui_refs)
    for area in (*AREAS, HOST):
        items = found.get(area, [])
        label = f"{area} (informational)" if area == HOST else area
        out += [f"## {label}: {len(items)}", ""]
        if not items:
            out += ["No blockers.", ""]
            continue
        c = counts_by_kind(items)
        out += ["| kind | count |", "|---|---|"] + [f"| {k} | {c[k]} |" for k in KINDS]
        out += ["", f"Placement artefacts (`artefact?`): {sum(1 for b in items if is_artefact(b, packages))} of {len(items)}."]
        destinations = by_destination(area, items)
        if destinations:
            out += ["", "Blockers by destination area:", "", "| from -> to | references |", "|---|---|"]
            out += [f"| {area if area != HOST else 'host'} -> {dest} | {n} |" for dest, n in destinations]
        if area == HOST:
            at_host, elsewhere = host_split(items)
            out += ["", f"Host vs other source: {at_host} from files whose manifest target is `host`, {elsewhere} from files outside the manifest (real composition-root work is the first figure)."]
        out += ["", "Three largest clusters (from package -> imported package):", ""]
        ranked = clusters(items)
        out += [f"- {len(bs)}: `{src}` -> `{dst}` ({', '.join(sorted({f'{b.from_target_module} -> {b.to_target_module}' for b in bs}))})" for (src, dst), bs in ranked[:3]]
        if area == HOST:
            out += ["", f"All {len(ranked)} clusters (individual references are not listed for host/wiring):", ""]
            out += [f"- {len(bs)}: `{src}` -> `{dst}`" for (src, dst), bs in ranked]
        else:
            out += ["", "Complete list:", ""]
            out += [f"- [{b.kind}]{' [artefact?]' if is_artefact(b, packages) else ''} `{b.from_file}` ({b.from_target_module}) imports `{b.imported_fqn}` ({b.to_target_module})" for b in sorted(items, key=lambda b: (b.kind, b.from_file, b.imported_fqn))]
        out.append("")
    out += _decisions(found)
    out += _settled_section(list(settled))
    out += ["## Proposed plans", ""]
    for plan, area in PLANS:
        items = found.get(area, [])
        note = " - informational (composition root and cleanup)" if area == HOST else ""
        name = "host/cleanup" if area == HOST else area
        split = ""
        if area == HOST:
            at_host, elsewhere = host_split(items)
            split = f" - of which {at_host} from `host`-targeted files, {elsewhere} from files outside the manifest"
        out.append(f"- **{plan} {name}**: {_summary(items)}{note}{split}{_plan_note(plan, items, packages)}")
    return "\n".join(out) + "\n"


def extracted_packages(files: dict[str, str]) -> dict[str, str]:
    """Package (relative to org.example.project) -> source root, for already-extracted modules outside the move manifest."""
    found: dict[str, str] = {}
    for path, text in sorted(files.items()):
        root = _EXTRACTED_ROOT.match(path)
        line = mm._PACKAGE_LINE.search(text)
        if root is None or line is None or is_ui_source(path) or root.group(1) in mm.source_roots():
            continue
        found.setdefault(mm._relative(line.group(1).replace("`", "")), root.group(1))
    return found


def main(argv: list[str]) -> int:
    areas = ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
    overrides_text = (ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8")
    overrides = ur.load_overrides(overrides_text)
    tree = mm.collect_files()
    generated = ur.load_file_overrides_file()
    placements = ur.load_placement_overrides_file()
    try:
        file_overrides = ur.merge_placements(placements, generated)
    except ValueError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    manifest = mm.build_manifest(tree, areas, overrides)
    rows = mm.file_rows(tree, areas, overrides, generated, placements)
    file_targets = {r.path: r.target for r in rows}
    fully_placed = fully_placed_rows(manifest, rows)
    scanned = ur.collect_files()
    refs = ur.find_upward(scanned, areas, overrides, file_overrides)
    collisions = colliding_packages(tree)
    report = render_report(blockers(manifest, refs, extracted_packages(scanned), file_targets, collisions, fully_placed),
                           artefact_packages(manifest, tree, areas, overrides, generated, placements), ui_reference_count(refs),
                           settled_splits(manifest, collisions, fully_placed))
    if "--out" in argv:
        (ROOT / argv[argv.index("--out") + 1]).write_text(report, encoding="utf-8")
    else:
        sys.stdout.write(report)
    return mm.exit_code(manifest, mm.validate_overrides(overrides_text, areas) + ur.validate_file_overrides(generated, tree)
                        + ur.validate_placement_overrides(placements, tree))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
