#!/usr/bin/env python3
"""Pull-down closure for one area (ZenBon restructuring spec sections 12/13).

Policy (applied to `ecr`): a DATA-kind type (enum, DTO, model, table: extraction_blockers.classify == upward-data)
imported by a file owned by the area from a HIGHER area is moved down to the area at FILE level, transitively: the
imports of every moved file that are themselves data-kind and in a higher area move too, until nothing more is
pulled (a fixed point). A BEHAVIOUR type (service, repository, factory, ...) is never pulled down: it is reported
as a PORT, a candidate for an interface in the lower area. The result is written as file-level overrides
(config/structure/file-overrides.tsv, read by upward_refs / move_manifest / extraction_blockers) so the package
rule no longer applies to those files.

Two limits keep the closure from swallowing other areas: `ui/*` sources never seed it (spec 5.3: ui may depend on any
area api), and only data owned by the area's ELIGIBLE_SOURCES moves (for ecr: fiscal, the area directly above). A data
type owned by a still higher area (accounting, backoffice) is NOT moved: it is listed as KEPT - a placement decision
(the ecr row should hold an id, or the type belongs lower), not a mechanical pull-down.

Hand placements (config/structure/placement-overrides.tsv) are decisions this tool never revisits: a placed file is
not moved by the closure and not re-emitted (Policy.placed); this tool still writes only file-overrides.tsv. With
--emit-overrides, a generated row (of any area) whose path now has a placement row is skipped (`skip_placed`): the
placement supersedes it, so regenerating removes the stale row instead of failing on the merge conflict.

Idempotent by construction: `stabilise` drops every override already carrying the area before computing, so a run
that sees its own output returns the same set. EXCLUDED_FROM_PULL_DOWN lists files that must stay put despite
looking like data; they are reported as ports. An override path that matches no file is an error (CLI exit 1).

Usage: python3 -I scripts/structure/pull_down.py --area ecr [--emit-overrides config/structure/file-overrides.tsv] [--out docs/audit/2026-10-26-pull-down-ecr.md]
"""
from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path
from typing import NamedTuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extraction_blockers as eb  # noqa: E402
import upward_refs as ur  # noqa: E402

ROOT = ur.ROOT
# Paths (relative to src/<set>/kotlin/org/example/project) that look like data but carry behaviour and must not move.
# Each entry needs a comment saying why.
EXCLUDED_FROM_PULL_DOWN: frozenset[str] = frozenset({
    # Controller ruling (Task 3 review): whole fiscal report containers consumed by the ecr printer's formatting code
    # (ZBonFormatter, PrinterService), not data ecr records need. Printing fiscal reports is fiscal behaviour (the
    # formatters move or sit behind a port, per the fiscal plan). Their imports are not pulled through them.
    "core/domain/dto/kassenbuch/KassenbuchDto.kt",
    "core/domain/dto/report/ZBonDto.kt",
})
# Areas whose data may be pulled down into the key area. ecr pulls from fiscal only (spec 12/13: the till records
# own their enums/receipt DTOs); data of accounting/backoffice is never dragged into ecr.
ELIGIBLE_SOURCES: dict[str, frozenset[str]] = {"ecr": frozenset({"fiscal"})}


class Policy(NamedTuple):
    table: dict[str, str]
    overrides: dict[str, str]
    file_overrides: dict[str, str]
    excluded: frozenset[str] = frozenset()
    eligible: frozenset[str] | None = None  # None = every higher area
    placed: frozenset[str] = frozenset()  # hand-placed paths (placement-overrides.tsv): decided by a human, never moved or re-emitted


class PullDown(NamedTuple):
    moves: list[str]                 # files (relative paths) to override to the area, sorted
    ports: list[tuple[str, str]]     # (importing file, imported behaviour type), sorted
    pulled_by: dict[str, str] = {}   # moved file -> the first file that pulled it
    kept: list[tuple[str, str, str]] = []  # (importing file, imported data type, its area): data of a non-eligible area, not moved


def is_data(fqn: str) -> bool:
    return eb.classify(fqn) == eb.KIND_DATA


def pull_down(area: str, refs: list[ur.Ref], files: dict[str, str], policy: Policy) -> PullDown:
    by_rel = {ur.rel_path(p): (p, t) for p, t in files.items()}
    rel_files = set(by_rel)
    moves: dict[str, str] = {}
    ports: set[tuple[str, str]] = set()
    kept: set[tuple[str, str, str]] = set()
    queue: list[str] = []

    def visit(from_file: str, fqn: str, to_area: str) -> None:
        resolved = ur.import_file(fqn)
        if resolved in policy.placed and resolved in rel_files:
            kept.add((from_file, fqn, to_area))  # a hand placement stands; the reference stays visible
        elif is_data(fqn) and resolved in rel_files and resolved not in policy.excluded and policy.eligible is not None and to_area not in policy.eligible:
            kept.add((from_file, fqn, to_area))
        elif is_data(fqn) and resolved in rel_files and resolved not in policy.excluded:
            if resolved not in moves:
                moves[resolved] = from_file
                queue.append(resolved)
        else:
            ports.add((from_file, fqn))

    for ref in refs:
        if ref.from_area == area and ur._RANK[ref.to_area] > ur._RANK[area] and not eb.is_ui_source(ref.from_file):
            visit(ref.from_file, ref.imported_fqn, ref.to_area)
    while queue:
        path, text = by_rel[queue.pop()]
        for fqn in ur._IMPORT.findall(text):
            if fqn.startswith("org.example.project.tse."):
                to_area = ur.package_area_for("tse", policy.table, policy.overrides)
            else:
                to_area = ur.import_area(fqn, policy.table, policy.overrides, policy.file_overrides, rel_files)
            resolved = ur.import_file(fqn)
            if resolved in moves or to_area is None or ur._RANK[to_area] <= ur._RANK[area]:
                continue
            visit(path, fqn, to_area)
    return PullDown(sorted(moves), sorted(ports), moves, sorted(kept))


def stabilise(area: str, files: dict[str, str], policy: Policy) -> PullDown:
    """Closure computed without the overrides already carrying `area` (they are this tool's own output)."""
    base = {k: v for k, v in policy.file_overrides.items() if v != area or k in policy.placed}
    refs = ur.find_upward(files, policy.table, policy.overrides, base)
    return pull_down(area, refs, files, policy._replace(file_overrides=base))


def skip_placed(existing: dict[str, str], placements: dict[str, "ur.Placement"]) -> dict[str, str]:
    """The generated file-overrides without the paths a hand placement now decides: a placement supersedes the row, so
    regenerating drops it instead of raising the merge conflict (Stage B2-2: Part A places files this tool pulled down)."""
    return {path: area for path, area in existing.items() if path not in placements}


def emitted_overrides(area: str, moves: list[str], existing: dict[str, str], placements: dict[str, "ur.Placement"]) -> str:
    """The `--emit-overrides` file: the area's regenerated rows, then the other areas' rows, all without placed paths."""
    others = "".join(f"{p}\t{a}\n" for p, a in sorted(skip_placed(existing, placements).items()) if a != area)
    return render_overrides(area, [m for m in moves if m not in placements]) + others


def render_overrides(area: str, moves: list[str]) -> str:
    lines = [f"# File-level area overrides (spec sections 12/13). Generated by scripts/structure/pull_down.py --area {area}; do not edit by hand.",
             f"# {area}-owned code imports these data types from higher areas, so they are pulled down to {area} at file level.",
             "# path (relative to src/<set>/kotlin/org/example/project)\tarea"]
    return "\n".join(lines + [f"{path}\t{area}" for path in sorted(moves)]) + "\n"


def remaining_references(result: PullDown) -> dict[tuple[str, str], list[str]]:
    """(kept type, its area) -> sorted importing files (relative); each is an upward reference that survives the pull-down."""
    found: dict[tuple[str, str], set[str]] = defaultdict(set)
    for src, fqn, to_area in result.kept:
        found[(fqn, to_area)].add(ur.rel_path(src))
    return {key: sorted(v) for key, v in sorted(found.items())}


def render_report(area: str, result: PullDown) -> str:
    by_dir: dict[str, list[str]] = defaultdict(list)
    for path in result.moves:
        by_dir[path.rsplit("/", 1)[0]].append(path)
    out = [f"# Pull-down closure for {area}", "",
           f"Generated by `scripts/structure/pull_down.py --area {area}`. Data-kind types (enums, DTOs, models, tables) imported by {area}-owned files "
           f"from higher areas move down to {area} at file level, transitively; behaviour types are never moved and are listed as ports.", "",
           f"{len(result.moves)} files pulled down, {len(result.ports)} ports, {len(result.kept)} kept references, {len(remaining_references(result))} kept types still referenced upward.", "", f"## Pulled down ({len(result.moves)})", "",
           "| directory | files |", "|---|---|"]
    out += [f"| `{d}` | {len(ps)} |" for d, ps in sorted(by_dir.items())]
    out += ["", "| file | first pulled by |", "|---|---|"]
    out += [f"| `{p}` | `{result.pulled_by[p]}` |" for p in result.moves]
    out += ["", f"## Ports - behaviour types and excluded report DTOs {area} imports from higher areas ({len(result.ports)})", ""]
    out += [f"- `{src}` imports `{fqn}`" for src, fqn in result.ports] or ["None."]
    out += ["", f"## Kept - data of areas not eligible for pull-down ({len(result.kept)})", "",
            f"Data-kind types owned by an area above the eligible sources. Not moved into {area}: each is a placement decision (hold an id instead, or move the owner down).", ""]
    out += [f"- `{src}` imports `{fqn}` ({to_area})" for src, fqn, to_area in result.kept] or ["None."]
    remaining = remaining_references(result)
    moved = set(result.moves)
    out += ["", "## Remaining upward references", "",
            f"Kept types are NOT pulled down, so these {area} -> higher-area references REMAIN after the pull-down (each is a placement decision):", ""]
    out += [f"- `{fqn}` ({to_area}) still imported by " + ", ".join(f"`{f}`" + (" (pulled down)" if f in moved else f" ({area}-owned)") for f in files)
            for (fqn, to_area), files in remaining.items()] or ["None."]
    out += ["", f"{len(remaining)} kept types still referenced upward.", "", f"> **Caveat.** {ur.SAME_PACKAGE_CAVEAT}"]
    return "\n".join(out) + "\n"


def _option(argv: list[str], name: str) -> str | None:
    return argv[argv.index(name) + 1] if name in argv else None


def main(argv: list[str]) -> int:
    area = _option(argv, "--area")
    if area not in ur.AREAS:
        sys.stderr.write("pull_down: --area <platform|ecr|fiscal|accounting|backoffice> is required\n")
        return 1
    table = ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
    overrides = ur.load_overrides((ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8"))
    existing = ur.load_file_overrides_file()
    placements = ur.load_placement_overrides_file()
    emit, out = _option(argv, "--emit-overrides"), _option(argv, "--out")
    if emit:
        existing = skip_placed(existing, placements)  # regenerated output: a placed path's stale row is dropped, not a conflict
    files = ur.collect_files()
    # The area's own previous rows are generated output: they are dropped and regenerated (not validated) when --emit-overrides runs.
    base = {k: v for k, v in existing.items() if v != area}
    try:
        ur.merge_placements(placements, base)
        effective = ur.merge_placements(placements, existing)
    except ValueError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    problems = ur.validate_file_overrides(base, files) + ur.validate_placement_overrides(placements, files) + [f"excluded path matches no file: {p}" for p in sorted(EXCLUDED_FROM_PULL_DOWN)
                                                          if p not in {ur.rel_path(f) for f in files}]
    if problems:
        sys.stderr.write("\n".join(problems) + "\n")
        return 1
    result = stabilise(area, files, Policy(table, overrides, effective, EXCLUDED_FROM_PULL_DOWN, ELIGIBLE_SOURCES.get(area), frozenset(placements)))
    if emit:
        (ROOT / emit).write_text(emitted_overrides(area, result.moves, existing, placements), encoding="utf-8")  # rows of other areas are kept
    if out:
        (ROOT / out).write_text(render_report(area, result), encoding="utf-8")
    if not emit and not out:
        sys.stdout.write(render_report(area, result))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
