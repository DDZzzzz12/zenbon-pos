#!/usr/bin/env python3
"""Move manifest (ZenBon restructuring spec sections 3, 4, 10, 11, 12).

Classifies every PACKAGE of three source modules into the module it moves to when the areas are
extracted. A row is `(source_module, source_set, package, target_module, file_count)`:

- `shared` (KMP, source set kept): DTO / enum / model / `features.<name>` wire packages -> `<area>-api`;
  everything else (core.utils, core.network, error envelope, presentation strings, core.data.remote
  API clients, ...) -> `platform-api`.
- `core-data`: `features.<name>...` -> `<area>-data`; everything else (core.*, utils.*) -> `platform-data`.
- `pos-server`: `features.<name>...` and `export.<name>...` -> `<area>-server`; `infrastructure.*`,
  `database`, the root package and any package with no feature/area segment -> `host`;
  `core.*` and `utils.*` -> `platform-server` (borderline: listed in the report for review).

The area comes from upward_refs.package_area_for on the feature-relative package (longest override
wins before the feature row), so `zBon.database.tables` is `ecr-data` while `zBon.domain.service` is
`fiscal-*`. A feature missing from package-areas.tsv gives target `UNMAPPED` (CLI exits 1).

Packages are read from each file's `package` line (fallback: the directory under src/<set>/kotlin),
relative to `org.example.project` (the root package is `(root)`).

In-place modules (config/structure/module-dirs.tsv, Stage B2-1b): once the mover has physically extracted an area
tier (`platform/api`, `platform/data`, `platform/server`), its files are scanned too. Such a module is a source module whose
name is its Gradle name without the colon (`platform-server`); a file under it has the module itself as target ("in place"),
whatever its package, and a row of it is SETTLED (source module == target). Its package area still comes from the package
rows and placements exactly as before the move, so a moved file keeps the classification it had in its blob module. The
loader is `move_area.load_module_dirs` (one parser); this module only loads it lazily to avoid an import cycle.

Test source sets (src/test, src/commonTest, src/jvmTest, ...) are scanned too. They are NOT a separate
column in the Row: the source set already says it (`is_test(source_set)`), and the TSV/report add a
`kind` column (`main`|`test`) derived from it. A test package follows the same rules as production code,
i.e. it is attributed to its subject's target; the mover decides per file later. Test packages that
match no rule (e.g. pos-server `routes`, `service`) fall back to `host` and appear in the borderline list.

Mixed packages: a package is MIXED (flagged in the report, "file-level handling required") when it is
a non-feature package (target `platform-*` or `host`) whose files belong to several features, because a
package row cannot be split and would file them all in one module (e.g. `core.data.remote` API clients
for fiscal and accounting DTOs under `platform-api` = an upward dependency). Detection is mechanical:
a file's feature is the EARLIEST camel-case word-start in its simple name that begins a feature name from
package-areas.tsv (longest name on a tie; `KtorTseSettingsApiClient` -> `tse`); its area comes from
package_area_for. Files matching no feature count as `platform/unknown`. The package is mixed when the
matched files resolve to more than one area, or when it is in MIXED_PACKAGE_NAMES (aggregate packages
that are mixed by construction even if names do not reveal it). Heuristic only: names are a hint, the
mover still reads the files.

Hand-maintained placements (config/structure/placement-overrides.tsv) work the same way and win over the generated file
overrides; a placement may also name the tier (`ecr:data` -> `ecr-data` whatever the source module) or `host` (target `host`).

File-level overrides (config/structure/file-overrides.tsv, spec sections 12/13): a file whose override area differs
from its package's target gets an additional `file` row (module, source set, package, target, path) beside the
package row, which keeps counting every file of the package (a file row is a MOVE OUT of its package row, not a
second package). The invariant "never two targets per package directory" is therefore relaxed ONLY for packages
that have listed file rows: the package row keeps its single target and the file rows carry the exceptions. Such
packages are always listed under "Mixed packages" (the mover must split them by file). An override path matching
no file is a problem and makes the CLI exit 1. A package row whose every file has a file row (`fully_placed_rows`) is
omitted from the TSV and the report: no file follows its target (Stage B2-2). The exit code still checks every row.

Usage: python3 -I scripts/structure/move_manifest.py [--tsv config/structure/move-manifest.tsv] [--report docs/audit/....md]
Exit 1 on any UNMAPPED row, any package with two different targets, or any override-table problem.
"""
from __future__ import annotations

import contextlib
import functools
import importlib.util
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parents[2]
PROJECT_PACKAGE = "org.example.project"
MODULES = ("shared", "core-data", "pos-server")
UNMAPPED = "UNMAPPED"
ROOT_PACKAGE = "(root)"

Row = tuple[str, str, str, str, int]  # source_module, source_set, package, target_module, file_count


def _load_sibling(name: str):
    existing = sys.modules.get(name)
    if existing is not None:
        return existing
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/structure/{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _load_upward_refs():
    return _load_sibling("upward_refs")


ur = _load_upward_refs()


class InPlace(NamedTuple):
    name: str           # Gradle name without the colon
    source_module: str  # the blob module its files came from


def parse_in_place(text: str) -> dict[str, InPlace]:
    """Directory -> InPlace for the text of a module-dirs.tsv, through `move_area.load_module_dirs` (one parser; move_area imports this
    module, so it is loaded lazily to avoid an import cycle)."""
    move_area = _load_sibling("move_area")
    return {m.directory: InPlace(m.gradle.lstrip(":"), m.source_module) for m in move_area.load_module_dirs(text).values()}


@functools.cache
def _default_in_place() -> dict[str, InPlace]:
    return parse_in_place((ROOT / "config/structure/module-dirs.tsv").read_text(encoding="utf-8"))


_override: list[dict[str, InPlace]] = []


@contextlib.contextmanager
def use_module_dirs(text: str):
    """Make every function that takes no explicit `in_place` use the module-dirs `text` (tests; not thread-safe)."""
    _override.append(parse_in_place(text))
    try:
        yield
    finally:
        _override.pop()


def in_place_dirs(in_place: dict[str, InPlace] | None = None) -> dict[str, InPlace]:
    return in_place if in_place is not None else (_override[-1] if _override else _default_in_place())


def in_place_modules(in_place: dict[str, InPlace] | None = None) -> dict[str, str]:
    """Directory -> module name (Gradle name without the colon) for every row of config/structure/module-dirs.tsv."""
    return {directory: row.name for directory, row in in_place_dirs(in_place).items()}


def in_place_of(source_module: str, in_place: dict[str, InPlace] | None = None) -> list[str]:
    """Names of the in-place modules whose files came from `source_module` (`shared` -> `platform-api`)."""
    return sorted(row.name for row in in_place_dirs(in_place).values() if row.source_module == source_module)


def source_roots(in_place: dict[str, InPlace] | None = None) -> list[str]:
    """Directories whose Kotlin files the manifest scans: the blob modules plus every in-place module directory."""
    return [*MODULES, *sorted(in_place_dirs(in_place))]


def is_in_place(module: str, in_place: dict[str, InPlace] | None = None) -> bool:
    return module in in_place_modules(in_place).values()


def _tier(module: str) -> str:
    """api / data / server of a source module: the blob's suffix, or the tail of an in-place module's name."""
    return _SUFFIX.get(module) or module.rsplit("-", 1)[-1]

_PACKAGE_LINE = re.compile(r"^package\s+([\w.`]+)", re.MULTILINE)
_SRC_PATH = re.compile(r"^(.+?)/src/([^/]+)/kotlin/(.*)/[^/]+$")
_SUFFIX = {"shared": "api", "core-data": "data", "pos-server": "server"}
_SHARED_FEATURE_PREFIXES = (("core", "domain", "dto"), ("core", "domain", "enums"), ("core", "domain", "models"), ("features",))


# Aggregate packages that hold many features' files by construction; always reported as mixed.
MIXED_PACKAGE_NAMES = frozenset({
    "core.data.remote", "core.data.remote.impl", "core.utils.enumStrings",
    "core.presentation.strings.keys", "core.presentation.strings", "core.presentation.strings.translations",
})
UNKNOWN_AREA = "platform/unknown"


class Mixed(NamedTuple):
    module: str
    source_set: str
    package: str
    target: str
    file_count: int
    areas: dict[str, int]


def feature_of_filename(path: str, areas: dict[str, str]) -> str | None:
    """Earliest camel-case word-start of the file stem that begins a known feature name (longest on a tie)."""
    stem = path.rsplit("/", 1)[-1].removesuffix(".kt")
    lowered = stem.lower()
    names = sorted(areas, key=len, reverse=True)
    for index, char in enumerate(stem):
        if index > 0 and not char.isupper():
            continue
        for name in names:
            if lowered.startswith(name.lower(), index):
                return name
    return None


class FileRow(NamedTuple):
    module: str
    source_set: str
    package: str
    target: str
    path: str  # relative to src/<set>/kotlin/org/example/project


def file_target(module: str, rel: str, area: str, placements: dict[str, "ur.Placement"]) -> str:
    """`<area>-<tier>`; a placement may set the tier (default: the source module's) or be `host` (no tier)."""
    placement = placements.get(rel)
    if placement is not None and placement.area == ur.HOST_AREA:
        return "host"
    return f"{area}-{(placement.tier if placement else None) or _tier(module)}"


def file_rows(files: dict[str, str], areas: dict[str, str], overrides: dict[str, str], file_overrides: dict[str, str],
              placements: dict[str, "ur.Placement"] | None = None) -> list[FileRow]:
    """One row per overridden or placed file whose target differs from its package's target.

    `file_overrides` is the generated map; `placements` (hand-maintained) win over it."""
    rows: list[FileRow] = []
    placements = placements or {}
    file_overrides = ur.merge_placements(placements, file_overrides)
    for path, text in sorted(files.items()):
        rel = ur.rel_path(path)
        located = package_of(path, text)
        if rel not in file_overrides or located is None:
            continue
        module, source_set, package = located
        target = file_target(module, rel, file_overrides[rel], placements)
        if target != target_for(module, package, areas, overrides):
            rows.append(FileRow(module, source_set, package or ROOT_PACKAGE, target, rel))
    return rows


def fully_placed_rows(manifest: list[Row], frows: list[FileRow]) -> frozenset[tuple[str, str, str]]:
    """`(module, source set, package)` of the manifest rows whose every file has a file row (a target other than the
    package's): such a row has no file left that follows the package target."""
    placed: dict[tuple[str, str, str], int] = defaultdict(int)
    for row in frows:
        placed[(row.module, row.source_set, row.package)] += 1
    return frozenset((module, source_set, package) for module, source_set, package, _, count in manifest
                     if count > 0 and placed.get((module, source_set, package), 0) >= count)


def omit_fully_placed(manifest: list[Row], frows: list[FileRow]) -> list[Row]:
    """The manifest without its fully placed rows (`fully_placed_rows`): a package row that no file follows would name a
    stale target (Stage B2-2: `utils.config -> platform-*`, whose only files are placed into ecr). The file rows carry them."""
    placed = fully_placed_rows(manifest, frows)
    return [row for row in manifest if row[:3] not in placed]


def mixed_packages(files: dict[str, str], areas: dict[str, str], overrides: dict[str, str],
                   file_overrides: dict[str, str] | None = None, placements: dict[str, "ur.Placement"] | None = None) -> list[Mixed]:
    split_out = {(r.module, r.source_set, r.package.replace(ROOT_PACKAGE, "")) for r in file_rows(files, areas, overrides, file_overrides or {}, placements)}
    grouped: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    for path, text in files.items():
        located = package_of(path, text)
        if located is not None:
            grouped[located].append(path)
    result: list[Mixed] = []
    for (module, source_set, package), paths in sorted(grouped.items()):
        target = target_for(module, package, areas, overrides)
        has_file_rows = (module, source_set, package) in split_out
        if is_in_place(module) or not (target.startswith("platform-") or target == "host" or has_file_rows):
            continue  # an extracted module is settled: nothing left for the mover to split
        counts: dict[str, int] = defaultdict(int)
        for path in paths:
            feature = feature_of_filename(path, areas)
            area = (ur.package_area_for(feature, areas, overrides) if feature else None) or UNKNOWN_AREA
            counts[area] += 1
        matched = {a for a in counts if a != UNKNOWN_AREA}
        if has_file_rows or len(matched) > 1 or package in MIXED_PACKAGE_NAMES:
            result.append(Mixed(module, source_set, package, target, len(paths), dict(sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])))))
    return result


def is_test(source_set: str) -> bool:
    return source_set == "test" or source_set.endswith("Test")


def _relative(package: str) -> str:
    if package == PROJECT_PACKAGE:
        return ""
    if package.startswith(PROJECT_PACKAGE + "."):
        return package[len(PROJECT_PACKAGE) + 1:]
    return package


def package_of(path: str, text: str, in_place: dict[str, InPlace] | None = None) -> tuple[str, str, str] | None:
    """(module, source_set, package relative to org.example.project), or None for a path outside src/<set>/kotlin or outside
    the scanned modules (the blobs and the module-dirs directories; a `ui/...` or `ecr/...` module is not part of the manifest)."""
    match = _SRC_PATH.match(path)
    if match is None:
        return None
    module, source_set, directory = match.groups()
    named = in_place_modules(in_place)
    if module not in MODULES and module not in named:
        return None
    module = named.get(module, module)
    line = _PACKAGE_LINE.search(text)
    package = line.group(1).replace("`", "") if line else directory.replace("/", ".")
    return module, source_set, _relative(package)


def feature_key(module: str, package: str, areas: dict[str, str]) -> str | None:
    """Feature-relative package (feature name first) or None when the package is not feature code."""
    parts = package.split(".") if package else []
    if not parts:
        return None
    if module == "shared":
        for prefix in _SHARED_FEATURE_PREFIXES:
            if tuple(parts[:len(prefix)]) == prefix and len(parts) > len(prefix):
                return ".".join(parts[len(prefix):])
    else:
        for head in ("features", "export") if module == "pos-server" else ("features",):
            if parts[0] == head and len(parts) > 1:
                return ".".join(parts[1:])
    if parts[0] in areas:  # top-level feature package (shared `auth.data`, pos-server test `audit`)
        return package
    return None


def _non_feature_target(module: str, package: str) -> str:
    if module == "shared":
        return "platform-api"
    if module == "core-data":
        return "platform-data"
    head = package.split(".")[0] if package else ""
    if head in ("core", "utils"):
        return "platform-server"
    return "host"  # infrastructure, database, root package, unclassified test packages


def target_for(module: str, package: str, areas: dict[str, str], overrides: dict[str, str],
               in_place: dict[str, InPlace] | None = None) -> str:
    if is_in_place(module, in_place):
        return module  # already extracted: the module is its own target, whatever the package
    key = feature_key(module, package, areas)
    if key is None:
        return _non_feature_target(module, package)
    area = ur.package_area_for(key, areas, overrides)
    if area is None:
        return UNMAPPED
    return f"{area}-{_SUFFIX[module]}"


def build_manifest(files: dict[str, str], areas: dict[str, str], overrides: dict[str, str]) -> list[Row]:
    counts: dict[tuple[str, str, str], int] = defaultdict(int)
    for path, text in files.items():
        located = package_of(path, text)
        if located is not None:
            counts[located] += 1
    return [
        (module, source_set, package or ROOT_PACKAGE, target_for(module, package, areas, overrides), count)
        for (module, source_set, package), count in sorted(counts.items())
    ]


def split_packages(rows: list[Row]) -> dict[str, list[Row]]:
    """Package name -> rows, for packages whose directories occur in more than one source module."""
    by_package: dict[str, list[Row]] = defaultdict(list)
    for row in rows:
        by_package[row[2]].append(row)
    return {p: rs for p, rs in sorted(by_package.items()) if len({r[0] for r in rs}) > 1}


def unmapped(rows: list[Row]) -> list[Row]:
    return [r for r in rows if r[3] == UNMAPPED]


def conflicting_targets(rows: list[Row]) -> dict[str, set[str]]:
    """`module/source_set/package` -> targets, for packages that carry two different targets."""
    targets: dict[str, set[str]] = defaultdict(set)
    for module, source_set, package, target, _ in rows:
        targets[f"{module}/{source_set}/{package}"].add(target)
    return {k: v for k, v in sorted(targets.items()) if len(v) > 1}


def validate_overrides(text: str, areas: dict[str, str]) -> list[str]:
    """Problems the loader silently accepts: duplicate keys and keys whose first segment is no known feature."""
    problems: list[str] = []
    seen: dict[str, int] = {}
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        key = line.split("\t")[0].strip()
        if key in seen:
            problems.append(f"package-overrides line {number}: duplicate key {key!r} (first on line {seen[key]})")
        seen.setdefault(key, number)
        if key.split(".")[0] not in areas:
            problems.append(f"package-overrides line {number}: first segment of {key!r} is not a feature in package-areas.tsv")
    return problems


def exit_code(rows: list[Row], override_problems: list[str]) -> int:
    return 1 if unmapped(rows) or conflicting_targets(rows) or override_problems else 0


def is_borderline(row: Row, areas: dict[str, str]) -> bool:
    """Targets decided by the non-feature fallback rather than by a feature row (listed for human review)."""
    module, _, package, target, _ = row
    if module == "pos-server":
        head = package.split(".")[0]
        return target in ("host", "platform-server") and head not in ("infrastructure", "features", "export") and head not in areas
    if module == "shared":
        return package.startswith("core.data.remote")  # per-feature API clients filed under platform-api
    return False


def render_tsv(rows: list[Row], frows: list[FileRow] | tuple[()] = ()) -> str:
    lines = ["# source_module\tsource_set\tpackage\ttarget_module\tfile_count\tkind\tfile (generated by scripts/structure/move_manifest.py; "
             "kind `file` rows move one file out of its package row, the 7th column is the path)"]
    lines += [f"{m}\t{s}\t{p}\t{t}\t{n}\t{'test' if is_test(s) else 'main'}" for m, s, p, t, n in rows]
    lines += [f"{r.module}\t{r.source_set}\t{r.package}\t{r.target}\t1\tfile\t{r.path}" for r in frows]
    return "\n".join(lines) + "\n"


def render_report(rows: list[Row], override_problems: list[str], areas: dict[str, str], mixed: list[Mixed],
                  frows: list[FileRow] | tuple[()] = ()) -> str:
    out = ["# Move manifest", "",
           "Generated by `scripts/structure/move_manifest.py` from `config/structure/package-areas.tsv` and "
           "`package-overrides.tsv`. Every package of `shared`, `core-data` and `pos-server` (production and test source sets) "
           f"has one target; {len(rows)} package rows, {sum(r[4] for r in rows)} files. A test package follows its subject's target "
           "(the `kind` column is `test` for src/test, *Test source sets); the mover decides per file.", "",
           "## Files per target module", "", "| target | main files | test files | package rows |", "|---|---|---|---|"]
    per_target: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
    for _, s, _, t, n in rows:
        per_target[t][1 if is_test(s) else 0] += n
        per_target[t][2] += 1
    for row in rows:  # a file row moves one file from its package's target to its own
        if any(r.module == row[0] and r.source_set == row[1] and r.package == row[2] for r in frows):
            per_target[row[3]][0] -= sum(1 for r in frows if (r.module, r.source_set, r.package) == row[:3])
    for r in frows:
        per_target[r.target][0] += 1
    for target, (main_n, test_n, rows_n) in sorted(per_target.items()):
        out.append(f"| {target} | {main_n} | {test_n} | {rows_n} |")
    if frows:
        out += ["", f"## File rows ({len(frows)})", "",
                "Files whose file-level override (`config/structure/file-overrides.tsv`) puts them in a different target than their package. "
                "The package row still counts them; the table above counts them under their own target. Their packages are listed under Mixed packages.", "",
                "| module/source set | package | file | target |", "|---|---|---|---|"]
        out += [f"| {r.module}/{r.source_set} | `{r.package}` | `{r.path}` | {r.target} |" for r in frows]
    split = split_packages(rows)
    out += ["", f"## Split packages ({len(split)})", "",
            "The same package name occurs in more than one source module; after the move the package is spread over several Gradle "
            "modules (a JPMS-style split) unless renamed.", "", "| package | occurrences (module/source set -> target, files) |", "|---|---|"]
    for package, rs in split.items():
        out.append(f"| `{package}` | " + "; ".join(f"{m}/{s} -> {t} ({n})" for m, s, _, t, n in rs) + " |")
    border = [r for r in rows if is_borderline(r, areas)]
    out += ["", f"## Borderline packages ({len(border)})", "",
            "Placed by a fallback rule, not by a feature row. pos-server `core.*`/`utils.*` -> `platform-server`; other pos-server "
            "packages outside `infrastructure.*` with no feature segment (`database`, root, unclassified test packages) -> `host`; "
            "shared `core.data.remote*` holds per-feature API clients filed under `platform-api` - see Mixed packages below.", "", "| module/source set | package | target | files |", "|---|---|---|---|"]
    out += [f"| {m}/{s} | `{p}` | {t} | {n} |" for m, s, p, t, n in border]
    provisional = sum(r[4] for r in rows if r[0] == "pos-server" and is_test(r[1]) and r[3] == "host")
    caveat = [f"> **Caveat.** {ur.SAME_PACKAGE_CAVEAT}", ""] if frows else []
    out += ["", f"## Mixed packages - file-level handling required ({len(mixed)})", ""] + caveat + [""] + [
            "**FLAG: these packages hold several features' files. A package row files them all under one target; the mover must split by file.** "
            "Detection is by file-name feature match (see the script docstring); `platform/unknown` = no feature in the name. "
            "**This list is a filename-heuristic review worklist, not a verdict:** a package can be flagged by coincidental name matches (for example `features.employee.database.tables`), so review each row before splitting.", "",
            "| module/source set | package | files | areas (files) | consequence |", "|---|---|---|---|---|"]
    for m in mixed:
        spread = ", ".join(f"{a} {n}" for a, n in m.areas.items())
        out.append(f"| {m.module}/{m.source_set} | `{m.package}` | {m.file_count} | {spread} | "
                   f"a package-level move would place {m.file_count} files in {m.target}; the mover must split by file |")
    out += ["", "## Provisional test targets", "",
            f"The pos-server test packages that fall back to `host` (routes, service, repository, database, testutils, docs; {provisional} files) are "
            "**PROVISIONAL**: the mover assigns each test file to its subject's feature module at file level."]
    problems = [f"unmapped: {m}/{s} `{p}` ({n} files)" for m, s, p, _, n in unmapped(rows)]
    problems += [f"two targets: {k} -> {sorted(v)}" for k, v in conflicting_targets(rows).items()] + override_problems
    out += ["", f"## Problems ({len(problems)})", ""] + ([f"- {p}" for p in problems] or ["None."])
    return "\n".join(out) + "\n"


def collect_files() -> dict[str, str]:
    files: dict[str, str] = {}
    for module in source_roots():
        for path in (ROOT / module / "src").rglob("*.kt"):
            files[path.relative_to(ROOT).as_posix()] = path.read_text(encoding="utf-8")
    return files


def _option(argv: list[str], name: str, default: str) -> str:
    return argv[argv.index(name) + 1] if name in argv else default


def main(argv: list[str]) -> int:
    areas = ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
    overrides_text = (ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8")
    overrides = ur.load_overrides(overrides_text)
    problems = validate_overrides(overrides_text, areas)
    files = collect_files()
    file_overrides = ur.load_file_overrides_file()
    placements = ur.load_placement_overrides_file()
    try:
        ur.merge_placements(placements, file_overrides)
    except ValueError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    problems += ur.validate_file_overrides(file_overrides, files) + ur.validate_placement_overrides(placements, files)
    rows = build_manifest(files, areas, overrides)
    frows = file_rows(files, areas, overrides, file_overrides, placements)
    listed = omit_fully_placed(rows, frows)
    (ROOT / _option(argv, "--tsv", "config/structure/move-manifest.tsv")).write_text(render_tsv(listed, frows), encoding="utf-8")
    (ROOT / _option(argv, "--report", "docs/audit/2026-10-26-move-manifest.md")).write_text(
        render_report(listed, problems, areas, mixed_packages(files, areas, overrides, file_overrides, placements), frows), encoding="utf-8")
    code = exit_code(rows, problems)
    if code:
        sys.stderr.write(f"move manifest: {len(unmapped(rows))} unmapped, {len(conflicting_targets(rows))} conflicting, "
                         f"{len(problems)} override problems - see the report\n")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
