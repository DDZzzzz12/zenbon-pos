#!/usr/bin/env python3
"""Area mover (ZenBon restructuring, Stage B2-1b): turns the move manifest into `git mv` operations for one area/tier.

Inputs
- the effective per-file target of every main file: file rows, then placements, then the package row, exactly as
  `same_package_refs.effective_targets` computes them (reused, not reimplemented);
- `config/structure/module-dirs.tsv`: Gradle name -> directory -> source module -> accepted source sets.

Plan
- A file whose target is `<area>-<tier>` (all tiers of the area when `--tier` is omitted) moves to
  `<directory>/src/<same set>/kotlin/<same relative path>`; packages and FQNs never change. A file already at its
  destination gives no move, so a re-run after `--apply` is a no-op. Only `src/<set>/kotlin/` is planned:
  `resources/` never move. A moving file in a source set the module does not list, or a target with no module-dirs
  row, is an error (exit 1).
- A test file has no row of its own: it follows its SUBJECT (the main file of the same package whose name is the
  test's name without `Test`/`Tests`, else the package rule). It moves only when it is compile-closed in the target
  (`is_closed(path, placement)`, decided as a fixpoint over the moving tests: a test dropped from the set can make
  another one unclosed). Otherwise it stays and is reported as `test-stays` (with the reason). `plan`'s default
  predicate keeps every test in place; the CLI passes `Checker.closure`, the real one: a test stays when it carries
  `@Tag("postgres"|"realdata"|"fatjar")`, looks up resources (itself, through a main file it uses directly that does,
  `YamlConfigLoader` included, or through a chain of main-file references, in any scanned module: "reaches A → … → B,
  which reads resources"), holds a `src/<set>` path literal, loses a reference it has today (host testutils, host code,
  an `internal` declaration or `internal` member across the new boundary), or is referenced by a test that stays.
- A non-Kotlin file under `kotlin/` moves with the Kotlin files of its directory; when only some of them move it is
  a blocking `non-kotlin-split` finding.

Findings are `(kind, path, detail, blocking)`; blocking ones make the run exit 1 and `--apply` refuse.

Checks (`Checker`, over every Kotlin file of every module; a file's module is its destination's, else today's):
- blocking: `collision` (one JVM class, a top-level class FQN or a file facade `FooKt`/`@file:JvmName`, in two modules
  sharing a runtime classpath and a platform), `expect-actual-split`, `internal-crossing` (an `internal` declaration whose
  user shared its module before and does not after), `internal-member-crossing` (the same for an `internal` member of a
  class: the user names the class by import, same package or qualified use and writes `.member`/`::member`), `unresolved-import` (an `org.example.project` import, star import or
  qualified use) and `unresolved-same-package`: a reference that resolves today and is not visible after the move;
- informational: `generated-symbol` (no source declares it, e.g. `BuildConfig`), `resource-lookup`, `string-logger`
  (moving files), `source-path-literal` (any test), `external-imports` (per target and main/test: non-project import
  prefixes, for the build files).
Visibility is the Gradle compile classpath: direct project dependencies plus what they export with `api`, transitively
(read through check_layering from the real build files, merged with `--assume-graph <tsv>` for modules that do not exist
yet: `:from TAB :to [TAB api|implementation]`, or `:from TAB -:to` to drop a real edge; Stage B2-2 passed
`config/structure/ecr-assume-graph.tsv` until its edges landed in the build files and the file was deleted in B2-2b,
so the R-seq now runs without the flag). A `--tier` run assumes the tiers before it (api, data) already moved;
without `--tier` the report also tabulates the three cumulative tier runs.

Package rename (not used in this stage): `--rename-package old=new` rewrites the package line, imports, qualified uses and
KDoc links in every tracked `*.kt`/`*.kts`; dry-run unless `--apply`.

Usage: python3 -I scripts/structure/move_area.py --area platform [--tier api|data|server] [--assume-graph <tsv>] [--apply] [--report <md>]
       python3 -I scripts/structure/move_area.py --rename-package <old.package>=<new.package> [--apply]
       python3 -I scripts/structure/move_area.py --check-jars <jar> <jar>...
Dry-run is the default (prints the plan, writes it to --report). Before anything moves, the remaining moves are
pre-flighted: an untracked source (`untracked-source`), an existing destination (`dst-exists`), a destination parent
that is a file (`dst-parent-is-file`) or a shared destination (`duplicate-dst`) is a blocking finding. `--apply` refuses
on a dirty tree or any blocking finding, then runs `mkdir -p` and `git mv` per move in sorted order.
Resume rule: tracked changes that are all staged renames matching moves of the plan (as computed on the pre-apply view,
i.e. with each staged rename put back at its old path) are NOT dirt: they are counted as done and `--apply` runs the
rest. Any other tracked change is dirt. When a `git mv` fails midway, the done/remaining lists and the reverse
`git mv` commands are written next to the report (`<report>.apply-progress.txt`) and recovery steps are printed.
Jar check (Task 9): `--check-jars <jar>...` lists every class file found in more than one of the given jars (`unzip -Z1`;
`META-INF/` and `module-info.class` excluded). Run it on the `jar` outputs of the modules of one runtime classpath: the
same package plus file name in two modules gives two `FooKt` facades, and shadowJar silently keeps one. Exit 1 on any.
Exit codes: 0 = clean (or nothing to do), 1 = blocking findings / errors / refused apply, 2 = usage.
"""
from __future__ import annotations

import importlib.util
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from types import ModuleType
from typing import Callable, NamedTuple

ROOT = Path(__file__).resolve().parents[2]
AREAS = ("platform", "ecr", "fiscal", "accounting", "backoffice")
TIERS = ("api", "data", "server")
DEFAULT_MODULE_DIRS = "config/structure/module-dirs.tsv"
_LOCATION = re.compile(r"^(.+?)/src/([^/]+)/kotlin/(.+)$")
_TEST_SUFFIXES = ("Tests", "Test")


def _load(name: str) -> ModuleType:
    existing = sys.modules.get(name)
    if existing is not None:
        return existing
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/structure/{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


sp = _load("same_package_refs")
mm = _load("move_manifest")
cl = _load("check_layering")


class ModuleDir(NamedTuple):
    gradle: str
    directory: str
    source_module: str
    sets: tuple[str, ...]


class Move(NamedTuple):
    src: str
    dst: str
    target: str  # `<area>-<tier>`
    kind: str    # main | test


class Finding(NamedTuple):
    kind: str
    path: str
    detail: str
    blocking: bool


class Plan(NamedTuple):
    moves: list[Move]
    findings: list[Finding]


class Options(NamedTuple):
    area: str | None
    tier: str | None
    apply: bool
    report: str | None
    assume_graph: str | None = None
    rename: tuple[str, str] | None = None
    check_jars: tuple[str, ...] = ()


ClosurePredicate = Callable[[str, dict[str, str]], bool]


def gradle(module: str) -> str:
    """The one normalisation point: a bare target (`platform-server`, as in `Move.target`) or a Gradle name
    (`:platform-server`, as in placements and the module graph) -> the Gradle name."""
    return module if module.startswith(":") else f":{module}"


def load_module_dirs(text: str) -> dict[str, ModuleDir]:
    dirs: dict[str, ModuleDir] = {}
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) != 4 or not cells[0].startswith(":") or not all(cells):
            raise ValueError(f"module-dirs line {number}: expected `:gradle TAB directory TAB source-module TAB sets`: {line!r}")
        dirs[cells[0]] = ModuleDir(cells[0], cells[1].rstrip("/"), cells[2], tuple(s.strip() for s in cells[3].split(",")))
    return dirs


def locate(path: str) -> tuple[str, str, str] | None:
    """(module directory, source set, path below kotlin/) or None outside `src/<set>/kotlin/` (e.g. resources)."""
    match = _LOCATION.match(path)
    return (match.group(1), match.group(2), match.group(3)) if match else None


def in_scope(target: str, area: str, tier: str | None) -> bool:
    return target.startswith(f"{area}-") and (tier is None or target == f"{area}-{tier}")


def current_module(path: str, module_dirs: dict[str, ModuleDir]) -> str:
    """Gradle name of the module a path lives in today."""
    located = locate(path)
    directory = located[0] if located else path.split("/", 1)[0]
    for module in module_dirs.values():
        if module.directory == directory:
            return module.gradle
    return gradle(directory)


def destination(path: str, target: str, module_dirs: dict[str, ModuleDir]) -> str:
    located = locate(path)
    assert located is not None
    module = module_dirs.get(gradle(target))
    if module is None:
        raise ValueError(f"{path}: target {target} has no row in module-dirs.tsv")
    _, source_set, below = located
    if source_set not in module.sets:
        raise ValueError(f"{path}: source set {source_set} is not mapped for {module.gradle} (accepts {', '.join(module.sets)})")
    return f"{module.directory}/src/{source_set}/kotlin/{below}"


def _test_stem(path: str) -> str:
    stem = path.rsplit("/", 1)[-1].removesuffix(".kt")
    for suffix in _TEST_SUFFIXES:
        if stem.endswith(suffix) and len(stem) > len(suffix):
            return stem[:-len(suffix)]
    return stem


def subject_targets(files: dict[str, str], targets: dict[str, str], package_target: Callable[[str, str], str]) -> dict[str, str]:
    """Test path -> its subject's target: the main file of the same package named like the test (same module first),
    else `package_target(module, package)`. Only Kotlin tests of the blob modules are considered."""
    mains: dict[tuple[str, str], list[str]] = defaultdict(list)
    tests: list[tuple[str, str, str]] = []
    for path, text in files.items():
        located = mm.package_of(path, text) if path.endswith(".kt") else None
        if located is None:
            continue
        module, source_set, package = located
        if mm.is_test(source_set):
            tests.append((path, module, package))
        elif path in targets:
            mains[(package, path.rsplit("/", 1)[-1].removesuffix(".kt"))].append(path)
    result: dict[str, str] = {}
    for path, module, package in sorted(tests):
        subjects = sorted(mains.get((package, _test_stem(path)), []), key=lambda p: (not p.startswith(f"{module}/"), p))
        result[path] = targets[subjects[0]] if subjects else package_target(module, package)
    return result


def keep_tests_in_place(path: str, placement: dict[str, str]) -> bool:
    """Conservative default: no test is known to be compile-closed outside its current module."""
    return False


def _is_test_path(path: str) -> bool:
    located = locate(path)
    return located is not None and mm.is_test(located[1])


def _placement(files: dict[str, str], module_dirs: dict[str, ModuleDir], moving: dict[str, str]) -> dict[str, str]:
    """path -> Gradle module after the move: `moving` (path -> target) for planned files, the current module otherwise."""
    return {path: gradle(moving[path]) if path in moving else current_module(path, module_dirs) for path in files}


def _closed_tests(candidates: dict[str, str], files: dict[str, str], module_dirs: dict[str, ModuleDir],
                  main_moves: dict[str, str], is_closed: ClosurePredicate) -> set[str]:
    """Fixpoint: drop every test that is not closed given the others still moving, until nothing changes."""
    moving = set(candidates)
    while True:
        placement = _placement(files, module_dirs, {**main_moves, **{p: candidates[p] for p in moving}})
        dropped = {path for path in sorted(moving) if not is_closed(path, placement)}
        if not dropped:
            return moving
        moving -= dropped


def _non_kotlin(files: dict[str, str], module_dirs: dict[str, ModuleDir], moves: list[Move]) -> Plan:
    by_src = {m.src: m for m in moves}
    kotlin_by_dir: dict[str, list[str]] = defaultdict(list)
    for path in files:
        if path.endswith(".kt") and locate(path):
            kotlin_by_dir[path.rsplit("/", 1)[0]].append(path)
    in_place = {m.directory for m in module_dirs.values()}
    extra: list[Move] = []
    findings: list[Finding] = []
    for path in sorted(files):
        located = locate(path)
        if path.endswith(".kt") or located is None:
            continue
        siblings = kotlin_by_dir.get(path.rsplit("/", 1)[0], [])
        moving = {(by_src[s].target, by_src[s].kind) for s in siblings if s in by_src}
        if not siblings and located[0] not in in_place:
            findings.append(Finding("non-kotlin-orphan", path, "no Kotlin file in its directory; stays", False))
        elif len(moving) == 1 and all(s in by_src for s in siblings):
            target, kind = moving.pop()
            extra.append(Move(path, destination(path, target, module_dirs), target, kind))
        elif moving:
            findings.append(Finding("non-kotlin-split", path, "the Kotlin files of its directory do not all move to one module", True))
    return Plan(extra, findings)


def plan(files: dict[str, str], targets: dict[str, str], module_dirs: dict[str, ModuleDir], area: str, tier: str | None,
         is_closed: ClosurePredicate = keep_tests_in_place) -> Plan:
    """files: path -> text (Kotlin and non-Kotlin); targets: path -> `<area>-<tier>`/`host`, main and test files
    (tests carry their subject's target, see `subject_targets`)."""
    main_moves: dict[str, str] = {}
    candidates: dict[str, str] = {}
    findings: list[Finding] = []
    errors: list[str] = []
    for path in sorted(files):
        target = targets.get(path)
        located = locate(path)
        if not path.endswith(".kt") or located is None or target is None or not in_scope(target, area, tier):
            continue
        module = module_dirs.get(gradle(target))
        if module is not None and located[0] == module.directory:
            continue  # already in its module
        if _is_test_path(path):
            stays = _test_stays_reason(located, target, module)
            if stays:
                findings.append(Finding("test-stays", path, stays, False))
            else:
                candidates[path] = target
            continue
        try:
            destination(path, target, module_dirs)
        except ValueError as error:
            errors.append(str(error))
            continue
        main_moves[path] = target
    if errors:
        raise ValueError("\n".join(errors))
    closed = _closed_tests(candidates, files, module_dirs, main_moves, is_closed) if candidates else set()
    moves = [Move(p, destination(p, t, module_dirs), t, "main") for p, t in main_moves.items()]
    moves += [Move(p, destination(p, t, module_dirs), t, "test") for p, t in candidates.items() if p in closed]
    findings += [Finding("test-stays", p, f"subject target {t}; not compile-closed there", False)
                 for p, t in candidates.items() if p not in closed]
    extra = _non_kotlin(files, module_dirs, moves)
    moves = sorted(moves + extra.moves)
    findings = sorted(findings + extra.findings + _foreign_sources(moves, module_dirs))
    return Plan(moves, findings)


def _test_stays_reason(located: tuple[str, str, str], target: str, module: ModuleDir | None) -> str | None:
    """Why a test stays before the compile closure is even consulted: it may only move into a module extracted from the
    module it lives in now, and only into a source set that module has (a test never raises, it stays)."""
    if module is None:
        return f"subject target {target}; no row in module-dirs.tsv"
    if located[0] != module.source_module:
        return f"subject target {target}; subject in another source module ({module.gradle} is extracted from {module.source_module}, the test lives in {located[0]})"
    if located[1] not in module.sets:
        return f"subject target {target}; source set {located[1]} is not mapped for {module.gradle}"
    return None


def _foreign_sources(moves: list[Move], module_dirs: dict[str, ModuleDir]) -> list[Finding]:
    """Informational: a file coming from a module other than the target's declared source module (a tier placement)."""
    findings: list[Finding] = []
    for move in moves:
        located = locate(move.src)
        expected = module_dirs[gradle(move.target)].source_module
        if located is not None and located[0] != expected:
            findings.append(Finding("foreign-source-module", move.src, f"from {located[0]}, {move.target}'s source module is {expected}", False))
    return findings


# ---------------------------------------------------------------------------------------------------------------------
# Mover checks (Task 3): module graph, symbol index, compile closure, collisions, expect/actual, `internal`
# ---------------------------------------------------------------------------------------------------------------------

PROJECT = "org.example.project"
HOST_ONLY_TAGS = frozenset({"postgres", "realdata", "fatjar"})  # their test tasks/fixtures exist only in :pos-server
Edge = tuple[str, bool]  # (dependency, exported: declared with an `api` configuration)


class ModuleGraph(NamedTuple):
    visible: dict[str, frozenset[str]]  # module -> other modules whose MAIN code it compiles against
    runtime: dict[str, frozenset[str]]  # module -> itself plus every module on its runtime classpath
    directories: dict[str, str]         # module directory -> Gradle name


def build_edges(build_text: str, known: list[str]) -> set[Edge]:
    """Project dependencies of one build file with their export flag. The declarations are found and resolved by
    check_layering (`_CONFIG_CALL`, `module_deps`), only the configuration name is read here: `api`/`*Api` exports."""
    code = cl._strip_comments(build_text)
    edges: set[Edge] = set()
    for call in cl._CONFIG_CALL.finditer(code):
        config = re.findall(r"[A-Za-z][A-Za-z0-9]*", call.group(0))[-1]
        args = cl._call_args(code, call.end())
        exported = config == "api" or config.endswith("Api")
        edges.update((dep, exported) for dep in cl.module_deps(f"{config}({args})", known))
    return edges


class AssumeGraph(NamedTuple):
    add: dict[str, set[Edge]]    # module -> edges it will have
    remove: dict[str, set[str]]  # module -> real dependencies it will no longer have


def load_assume_graph(text: str) -> AssumeGraph:
    """`--assume-graph`, the dependency graph the modules WILL have, as a delta on the real build files:
    `:from TAB :to [TAB api|implementation]` adds an edge (default implementation; for modules that do not exist yet or
    gain a dependency), `:from TAB -:to` removes a real edge (a dependency a later task drops, e.g. Task 7's
    `:platform-server-common` api(shared) -> api(platform-api)). A removed edge must exist in the build files."""
    add: dict[str, set[Edge]] = defaultdict(set)
    remove: dict[str, set[str]] = defaultdict(set)
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) == 2 and cells[0].startswith(":") and cells[1].startswith("-:"):
            remove[cells[0]].add(cells[1][1:])
            continue
        if len(cells) not in (2, 3) or not all(c.startswith(":") for c in cells[:2]) or (len(cells) == 3 and cells[2] not in ("api", "implementation")):
            raise ValueError(f"assume-graph line {number}: expected `:from TAB :to [TAB api|implementation]` or `:from TAB -:to`: {line!r}")
        add[cells[0]].add((cells[1], len(cells) == 3 and cells[2] == "api"))
    return AssumeGraph(dict(add), dict(remove))


def module_graph(edges: dict[str, set[Edge]], directories: dict[str, str]) -> ModuleGraph:
    """Compile visibility as Gradle builds it: a module sees its direct dependencies plus everything they export with
    `api`, transitively; `implementation` dependencies of a dependency are not seen (they are on the runtime classpath)."""
    nodes = set(edges) | {d for deps in edges.values() for d, _ in deps} | set(directories.values())

    def closure(start: str, follow: Callable[[bool], bool]) -> set[str]:
        seen: set[str] = set()
        stack = [start]
        while stack:
            for dep, exported in edges.get(stack.pop(), set()):
                if follow(exported) and dep not in seen:
                    seen.add(dep)
                    stack.append(dep)
        return seen

    visible: dict[str, frozenset[str]] = {}
    for node in nodes:
        seen: set[str] = set()
        for dep, _ in edges.get(node, set()):
            seen |= {dep} | closure(dep, lambda exported: exported)
        visible[node] = frozenset(seen - {node})
    runtime = {node: frozenset(closure(node, lambda exported: True) | {node}) for node in nodes}
    return ModuleGraph(visible, runtime, dict(directories))


def load_module_graph(root: Path, assume_text: str = "") -> ModuleGraph:
    """The real project-dependency graph (settings.gradle.kts + every build file, through check_layering), merged with
    the `--assume-graph` edges; directories also cover the module-dirs.tsv rows (modules that may not exist yet)."""
    included = cl.parse_settings((root / "settings.gradle.kts").read_text(encoding="utf-8"))
    edges: dict[str, set[Edge]] = {}
    for path, directory in included.items():
        build = root / directory / "build.gradle.kts"
        edges[path] = build_edges(build.read_text(encoding="utf-8"), list(included)) if build.exists() else set()
    assumed = load_assume_graph(assume_text)
    for source, dropped in assumed.remove.items():
        real = {dep for dep, _ in edges.get(source, set())}
        missing = sorted(dropped - real)
        if missing:
            raise ValueError(f"assume-graph removes {source} -> {', '.join(missing)}, which the build files do not declare")
        edges[source] = {(dep, exported) for dep, exported in edges[source] if dep not in dropped}
    for source, added in assumed.add.items():
        edges.setdefault(source, set()).update(added)
    directories = {directory: path for path, directory in included.items()}
    module_dirs = root / DEFAULT_MODULE_DIRS
    if module_dirs.exists():
        directories.update({m.directory: m.gradle for m in load_module_dirs(module_dirs.read_text(encoding="utf-8")).values()})
    return ModuleGraph(*module_graph(edges, directories))


def module_of(path: str, directories: dict[str, str]) -> str:
    """Gradle module of a path today: the longest module directory that contains it."""
    best = max((d for d in directories if path.startswith(d + "/")), key=len, default=None)
    return directories[best] if best is not None else gradle(path.split("/", 1)[0])


def earlier_tiers(tier: str) -> tuple[str, ...]:
    """Tiers extracted before `tier` (Tasks 7, 8, 9: api, then data, then server); a per-tier run assumes them moved."""
    return TIERS[:TIERS.index(tier)]


_IMPORT_DECL = re.compile(r"^import\s+([\w.`]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*;?\s*$", re.MULTILINE)
_QUALIFIED = re.compile(rf"(?<![\w.]){re.escape(PROJECT)}(?:\.\w+)+")
_JVM_NAME = re.compile(r'^@file:JvmName\(\s*"([^"]+)"\s*\)', re.MULTILINE)
_ANNOTATIONS = r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*"
_MODS = r"(?:(?:public|private|internal|protected|data|sealed|enum|abstract|open|inline|value|annotation|const|suspend|tailrec|operator|infix|expect|actual|lateinit|external|fun)\s+)*"
_CLASS_LIKE = re.compile(_ANNOTATIONS + _MODS + r"(?:class|interface|object)\s+`?(\w+)", re.MULTILINE)  # private too: still a class file
_CALLABLE = re.compile(_ANNOTATIONS + _MODS.replace("|fun)", ")") + r"(?:fun(?!\s+interface\b)|val|var)\s", re.MULTILINE)
_RESOURCE = re.compile(r"\bgetResource(?:AsStream|s)?\s*\(|\bclassLoader\b|\bClassLoader\b")
# Reads application.yaml (a host resource) through the server-common loader: invisible to `_RESOURCE`, same effect.
_HOST_CONFIG = re.compile(r"\bYamlConfigLoader\b")
_STRING_LOGGER = re.compile(r'LoggerFactory\.getLogger\(\s*"([^"]*)"')
_SOURCE_PATH = re.compile(r'"[^"\n]*\bsrc/[A-Za-z]+(?:/[^"\n]*)?"')
_TAG = re.compile(r'@Tag\(\s*"(\w+)"\s*\)')
_ALL_PLATFORMS = frozenset({"jvm", "android", "web", "ios"})


def _top_level_names(code: str) -> set[str]:
    """Every top-level declared name, private included (a file's own private name shadows a sibling's)."""
    return {m.group(1) for m in sp._DECL.finditer(code)} | {m.group(1) for m in re.finditer(r"^private\s+(?:\w+\s+)*(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?`?(\w+)", code, re.MULTILINE)}


def _keyword_names(code: str, keyword: str) -> set[str]:
    return {m.group(1) for m in sp._DECL.finditer(code) if re.search(rf"\b{keyword}\b", code[m.start():m.start(1)])}


def _platforms(source_set: str) -> frozenset[str]:
    """Which compilations a source set's classes end up in (KMP common code lands in every platform's output)."""
    name = source_set.removesuffix("Main")
    if name in ("common", ""):
        return _ALL_PLATFORMS
    if name in ("main", "jvm", "desktop"):
        return frozenset({"jvm"})
    if name.startswith("android"):
        return frozenset({"android"})
    if name.startswith(("wasm", "js", "web")):
        return frozenset({"web"})
    if name.startswith(("ios", "apple", "native")):
        return frozenset({"ios"})
    return frozenset({source_set})


def _facade(stem: str) -> str:
    """Kotlin's file facade class for `Foo.kt` / `Foo.jvm.kt`: `FooKt` / `Foo_jvmKt`."""
    cleaned = re.sub(r"\W", "_", stem)
    return cleaned[:1].upper() + cleaned[1:] + "Kt"


def _import_prefix(fqn: str, star: bool = False) -> str:
    """`io.ktor.server.routing.Route` -> `io.ktor.server`, `kotlin.math.abs` -> `kotlin.math`: the import without its
    imported name (kept for a star import), cut before the first capitalised segment, at most three segments."""
    segments: list[str] = []
    for segment in (fqn.split(".") if star else fqn.split(".")[:-1]):
        if not segment[:1].islower() or len(segments) == 3:
            break
        segments.append(segment)
    return ".".join(segments) or fqn


class Req(NamedTuple):
    """A file needs ONE of `candidates` (declaring files) to be visible."""
    kind: str                    # import | same-package
    detail: str                  # the import / qualified use / same-package name
    candidates: frozenset[str]
    internal: frozenset[str]     # the candidates whose declaration of the name is `internal`
    name: str = ""               # the top-level name it references ("" for a star import that names nothing)


class MemberUse(NamedTuple):
    """A file uses an `internal` member of a class it names (by import, same package or qualified use)."""
    user: str
    declarer: str
    member: str                  # `Class.member` / `Outer.Inner.member`


class Checker:
    """Symbol index and compile closure over every Kotlin file of every module (main and test sets).

    Resolution: an `org.example.project.*` import, wildcard import or fully qualified use names a package plus the
    top-level declaration it starts with (`a.b.Outer.Nested`, `a.b.Obj.member`, `a.b.Cls.Companion.x` and `a.b.f` all
    resolve to the file of package `a.b` declaring `Outer`/`Obj`/`Cls`/`f`; `as Alias` is ignored; imports in comments and
    KDoc are not imports). A same-package name is a top-level name of a sibling file the user names without importing or
    declaring it (same_package_refs' heuristic, extension receivers included). Visibility: a main declaration is visible
    from the same module and from modules that compile against it (`ModuleGraph.visible`); a test declaration only from
    tests of the same module. A requirement is reported only when it was satisfied before the move and is not after:
    the tree compiles today, so a requirement unsatisfied before is a name clash of the heuristic, not a dependency.

    Known blind spots (the compiler of Tasks 7-9 catches them): references inside string templates (`"${Foo.bar()}"`,
    `"$name"`) are invisible, because string literals are blanked before scanning; an `internal` member is seen only when
    the user names its class and writes `.member`/`::member` (a call through an inferred receiver, an extension of the
    class calling it unqualified, or an `internal constructor` are not); and "cannot access class" is not modelled: a file may compile
    against B, yet use a declaration of B whose signature exposes a type of C that B depends on with `implementation`
    only, so the type is not on the file's compile classpath. Only the file's own references are resolved, not the
    types reachable through the signatures it calls."""

    def __init__(self, files: dict[str, str], graph: ModuleGraph) -> None:
        self.graph = graph
        self.texts = {p: t for p, t in files.items() if p.endswith(".kt") and locate(p) is not None}
        self.code = {p: sp.code_only(t) for p, t in self.texts.items()}
        self.package = {p: sp.package_of(t) or "" for p, t in self.texts.items()}
        self.test = {p: mm.is_test(locate(p)[1]) for p in self.texts}  # type: ignore[index]
        self.current = {p: module_of(p, graph.directories) for p in self.texts}
        self.by_package: dict[str, list[str]] = defaultdict(list)
        self.declared: dict[tuple[str, str], list[str]] = defaultdict(list)
        self.internal: dict[str, set[str]] = {}
        for path in sorted(self.texts):
            self.by_package[self.package[path]].append(path)
            for name in sp.declared_names(self.texts[path]):
                self.declared[(self.package[path], name)].append(path)
            self.internal[path] = sp.internal_names(self.texts[path])
        self._receivers: dict[str, dict[str, dict[str, set[str]]]] = {}
        self.stay_reasons: dict[str, str] = {}  # test -> why the last closure call kept it in place
        self.generated: dict[str, list[str]] = {}
        self.reqs = {p: self._requirements(p) for p in sorted(self.texts)}
        # main files that read classpath resources: resources stay in the host, so a test that uses one directly would
        # find nothing on the target module's test classpath (Task 9: JwtConfig/ApiDocsPolicy/MaintenancePolicy tests)
        self.resource_readers = frozenset(p for p in self.texts if not self.test[p]
                                          and (_RESOURCE.search(self.code[p]) or _HOST_CONFIG.search(self.code[p])))
        self.users: dict[str, list[tuple[str, Req]]] = defaultdict(list)
        for user, reqs in self.reqs.items():
            for req in reqs:
                for candidate in req.candidates:
                    self.users[candidate].append((user, req))
        self.internal_members = {p: sp.internal_member_names(t) for p, t in self.texts.items() if "internal" in self.code[p]}
        self.member_uses = {p: self._member_uses(p) for p in sorted(self.texts)}
        self.member_users: dict[str, list[MemberUse]] = defaultdict(list)
        for uses in self.member_uses.values():
            for use in uses:
                self.member_users[use.declarer].append(use)
        self._next_to_reader = self._resource_paths()

    # -- index ----------------------------------------------------------------------------------------------------
    def _resolve(self, fqn: str) -> tuple[str, str] | None:
        parts = fqn.split(".")
        for i in range(len(parts) - 1, 0, -1):
            key = (".".join(parts[:i]), parts[i])
            if key in self.declared:
                return key
        return None

    def _req(self, kind: str, detail: str, keys: list[tuple[str, str]], exclude: str) -> Req | None:
        candidates = {p for key in keys for p in self.declared.get(key, []) if p != exclude}
        if not candidates:
            return None
        internal = {p for key in keys for p in self.declared.get(key, []) if p in candidates and key[1] in self.internal[p]}
        return Req(kind, detail, frozenset(candidates), frozenset(internal), keys[0][1])

    def _is_package(self, name: str) -> bool:
        return name in self.by_package or any(p.startswith(name + ".") for p in self.by_package)

    def _receivers_of(self, package: str) -> dict[str, dict[str, set[str]]]:
        if package not in self._receivers:
            self._receivers[package] = {p: sp.extension_receivers(self.texts[p]) for p in self.by_package[package]}
        return self._receivers[package]

    def _requirements(self, path: str) -> list[Req]:
        code = self.code[path]
        reqs: list[Req] = []
        generated: list[str] = []
        explicit: set[str] = set()
        wildcards: list[str] = []
        for match in _IMPORT_DECL.finditer(code):
            fqn = match.group(1).replace("`", "")
            if fqn != PROJECT and not fqn.startswith(PROJECT + "."):
                continue
            if match.group(2) and fqn in self.by_package:
                wildcards.append(fqn)
                continue
            if not match.group(2):
                explicit.add(match.group(3) or fqn.rsplit(".", 1)[-1])
            hit = self._resolve(fqn)
            req = self._req("import", fqn + (".*" if match.group(2) else ""), [hit], path) if hit else None
            if req:
                reqs.append(req)
            elif not hit and not self._is_package(fqn):
                generated.append(fqn + (".*" if match.group(2) else ""))
        body = sp._IMPORT_LINE.sub("", code)
        for fqn in sorted(set(_QUALIFIED.findall(body))):
            hit = self._resolve(fqn)
            req = self._req("import", f"{fqn} (qualified use)", [hit], path) if hit else None
            if req:
                reqs.append(req)
            elif not hit and not self._is_package(fqn):
                generated.append(fqn)
        body = _QUALIFIED.sub("", body)
        named = self._same_package(path, body, explicit, wildcards)
        reqs += named
        used = {self.package[c] for r in named for c in r.candidates}
        for wildcard in wildcards:  # a star import naming nothing still needs its package to exist on the classpath
            package_files = frozenset(p for p in self.by_package[wildcard] if p != path)
            if wildcard not in used and package_files:
                reqs.append(Req("import", f"{wildcard}.*", package_files, frozenset()))
        self.generated[path] = sorted(set(generated))
        return reqs

    def _same_package(self, path: str, body: str, explicit: set[str], wildcards: list[str]) -> list[Req]:
        """One requirement per name the file uses that a sibling of its package (or a star-imported package) declares;
        explicit imports and the file's own declarations shadow it."""
        package = self.package[path]
        tokens = set(re.findall(r"\w+", body))
        own = _top_level_names(self.code[path])
        reqs: list[Req] = []
        for name in sorted(tokens - own - explicit):
            siblings = {p for p in self.declared.get((package, name), []) if p != path} if package else set()
            if siblings:
                receivers = self._receivers_of(package)
                siblings = {p for p in siblings if sp._is_reference(name, p, receivers, body, tokens)}
            starred = {p for w in wildcards for p in self.declared.get((w, name), []) if p != path}
            candidates = siblings | starred
            if not candidates:
                continue
            kind, detail = ("same-package", name) if siblings else ("import", f"{sorted(w for w in wildcards if (w, name) in self.declared)[0]}.* ({name})")
            internal = frozenset(p for p in candidates if name in self.internal[p])
            reqs.append(Req(kind, detail, frozenset(candidates), internal, name))
        return reqs

    def _member_uses(self, path: str) -> list[MemberUse]:
        """Every `internal` member the file uses: it names the declaring class (a requirement on the class's top-level
        name, with the nested class's own name in the text for `Outer.Inner`) and writes `.member` or `::member`.
        A top-level `internal` class is the requirement's own crossing, not repeated here."""
        code = self.code[path]
        tokens: set[str] | None = None
        uses: set[MemberUse] = set()
        for req in self.reqs[path]:
            for declarer in sorted(req.candidates - req.internal):
                for key, names in self.internal_members.get(declarer, {}).items():
                    parts = key.split(".")
                    if parts[0] != req.name:
                        continue
                    if len(parts) > 1:
                        tokens = tokens if tokens is not None else set(re.findall(r"\w+", code))
                        if parts[-1] not in tokens:
                            continue
                    uses.update(MemberUse(path, declarer, f"{key}.{name}") for name in names
                                if re.search(rf"(?:\.|::)\s*`?{re.escape(name)}\b", code))
        return sorted(uses)

    def _resource_paths(self) -> dict[str, str | None]:
        """Main file -> the next main file on a shortest reference path to a resource reader (None for a reader itself),
        for every main file that reaches one. Edges are a main file's references to main files it sees today, star
        imports that name nothing excluded; ties go to the smallest path, so the chain is deterministic."""
        predecessors: dict[str, set[str]] = defaultdict(set)
        for path, reqs in self.reqs.items():
            if self.test[path]:
                continue
            for req in reqs:
                if req.name:
                    for candidate in req.candidates:
                        if not self.test[candidate] and candidate != path and self._sees(path, candidate, self.current):
                            predecessors[candidate].add(path)
        nearest: dict[str, str | None] = {reader: None for reader in self.resource_readers}
        frontier = sorted(self.resource_readers)
        while frontier:
            found: dict[str, str] = {}
            for node in frontier:
                for user in sorted(predecessors.get(node, ())):
                    if user not in nearest and user not in found:
                        found[user] = node
            nearest.update(found)
            frontier = sorted(found)
        return nearest

    def resource_chain(self, path: str) -> list[str] | None:
        """The shortest chain of main files from `path` to a file that reads resources (`[path]` when it reads them
        itself), or None when no reference path reaches one."""
        if path not in self._next_to_reader:
            return None
        chain = [path]
        while (step := self._next_to_reader[chain[-1]]) is not None:
            chain.append(step)
        return chain

    def _reached_reader(self, path: str) -> list[str] | None:
        """For a test: the shortest chain from a main file it references (and sees today) to a resource reader."""
        chains = [chain for req in self.reqs[path] if req.name for c in sorted(req.candidates)
                  if not self.test[c] and self._sees(path, c, self.current) and (chain := self.resource_chain(c)) is not None]
        return min(chains, key=lambda chain: (len(chain), chain), default=None)

    # -- visibility -----------------------------------------------------------------------------------------------
    def _sees(self, user: str, declarer: str, where: dict[str, str]) -> bool:
        user_module, declarer_module = where[user], where[declarer]
        if self.test[declarer]:
            return self.test[user] and declarer_module == user_module
        return declarer_module == user_module or declarer_module in self.graph.visible.get(user_module, frozenset())

    def _satisfied(self, user: str, req: Req, where: dict[str, str]) -> bool:
        return any(self._sees(user, c, where) for c in req.candidates)

    def _crossings(self, user: str, req: Req, before: dict[str, str], after: dict[str, str]) -> list[str]:
        """`internal` candidates that shared the user's module before and do not after."""
        return sorted(c for c in req.internal if before[c] == before[user] and after[c] != after[user])

    @staticmethod
    def _member_crosses(use: MemberUse, before: dict[str, str], after: dict[str, str]) -> bool:
        return before[use.declarer] == before[use.user] and after[use.declarer] != after[use.user]

    def _broken(self, user: str, req: Req, before: dict[str, str], after: dict[str, str]) -> bool:
        return bool(self._crossings(user, req, before, after)) or (self._satisfied(user, req, before) and not self._satisfied(user, req, after))

    def base(self, assumed: dict[str, str] | None = None) -> dict[str, str]:
        """Placement before this plan: today's modules, with `assumed` (earlier tiers, path -> module) applied."""
        where = dict(self.current)
        where.update({p: gradle(m) for p, m in (assumed or {}).items() if p in where})
        return where

    def closure(self, assumed: dict[str, str] | None = None) -> ClosurePredicate:
        """The `is_closed(path, placement)` predicate for `plan`. `placement` carries Gradle names (or bare targets,
        normalised by `gradle`); entries equal to today's module do not override `assumed`."""
        before = self.base(assumed)

        def is_closed(path: str, placement: dict[str, str]) -> bool:
            reason = self._open_reason(path, placement, before)
            if reason is not None:
                self.stay_reasons[path] = reason
            return reason is None

        return is_closed

    def _open_reason(self, path: str, placement: dict[str, str], before: dict[str, str]) -> str | None:
        """Why a test is not compile-closed under `placement` (None = closed): a host-only tag, a resource lookup (resources
        stay in the host) of its own, of a main file it uses directly, or of a main file reached through a chain of
        main-file references (`resource_chain`, every scanned module, platform/server-common included), a source-path
        literal, a requirement or `internal` member it loses, or a staying user that loses one. Calls the index cannot
        see (reflection, string templates) are still the trial run's to find."""
        if path not in self.texts:
            return "not a Kotlin source file"
        tags = sorted(HOST_ONLY_TAGS & set(_TAG.findall(self.texts[path])))
        if tags:
            return f'@Tag("{tags[0]}")'
        lookup = _RESOURCE.search(self.code[path])
        if lookup:
            return f"loads resources ({lookup.group(0).rstrip('( ')})"
        source_path = _SOURCE_PATH.search(self.texts[path])
        if source_path:  # a source-scanning guard resolves the path against its module directory: moved, it scans the wrong tree
            return f"reads sources by path ({source_path.group(0)})"
        for req in self.reqs[path]:
            readers = sorted(req.candidates & self.resource_readers)
            if readers:
                return f"uses `{req.detail}` from {readers[0]}, which reads host resources"
        chain = self._reached_reader(path)
        if chain is not None:  # a resource read further down the call chain: the moved test would not find it either
            return f"reaches {' → '.join(chain)}, which reads resources"
        after = dict(before)
        after.update({p: gradle(m) for p, m in placement.items() if p in after and gradle(m) != self.current[p]})
        for req in self.reqs[path]:
            if self._broken(path, req, before, after):
                first = sorted(req.candidates)[0]
                return f"needs `{req.detail}` from {first} ({after[first]})"
        for use in self.member_uses[path]:
            if self._member_crosses(use, before, after):
                return f"needs internal member `{use.member}` from {use.declarer} ({after[use.declarer]})"
        for user, req in self.users.get(path, []):
            if self._broken(user, req, before, after):
                return f"`{req.detail}` is used by {user} ({after[user]})"
        for use in self.member_users.get(path, []):
            if self._member_crosses(use, before, after):
                return f"internal member `{use.member}` is used by {use.user} ({after[use.user]})"
        return None

    def is_closed(self, path: str, placement: dict[str, str]) -> bool:
        return self.closure()(path, placement)

    # -- findings -------------------------------------------------------------------------------------------------
    def findings(self, moves: list[Move], assumed: dict[str, str] | None = None) -> list[Finding]:
        before = self.base(assumed)
        after = dict(before)
        moved = {m.src: m for m in moves if m.src in self.texts}
        after.update({p: gradle(m.target) for p, m in moved.items()})
        found: list[Finding] = []
        found += self._closure_findings(moved, before, after)
        found += self._moving_file_findings(moved)
        found += self._external_imports(moved)
        found += self._collisions(moved, after)
        found += self._expect_actual(after)
        found += [Finding("source-path-literal", p, ", ".join(sorted(set(_SOURCE_PATH.findall(self.texts[p])))), False)
                  for p in sorted(self.texts) if self.test[p] and _SOURCE_PATH.search(self.texts[p])]
        return sorted(set(found))

    def _closure_findings(self, moved: dict[str, Move], before: dict[str, str], after: dict[str, str]) -> list[Finding]:
        found: list[Finding] = []
        checked: list[tuple[str, Req]] = [(p, r) for p in moved for r in self.reqs[p]]
        checked += [(u, r) for p in moved for u, r in self.users.get(p, []) if u not in moved]
        for user, req in sorted(set(checked)):
            crossings = self._crossings(user, req, before, after)
            if crossings:
                found += [Finding("internal-crossing", user, f"`{req.detail}` is internal in {c} ({after[c]}), user in {after[user]}", True) for c in crossings]
            elif self._satisfied(user, req, before) and not self._satisfied(user, req, after):
                where = ", ".join(f"{c} ({after[c]})" for c in sorted(req.candidates))
                kind = "unresolved-same-package" if req.kind == "same-package" else "unresolved-import"
                found.append(Finding(kind, user, f"`{req.detail}` -> {where}; not visible from {after[user]}{' tests' if self.test[user] else ''}", True))
        uses = [u for p in moved for u in self.member_uses[p]] + [u for p in moved for u in self.member_users.get(p, []) if u.user not in moved]
        found += [Finding("internal-member-crossing", u.user, f"`{u.member}` is internal in {u.declarer} ({after[u.declarer]}), user in {after[u.user]}", True)
                  for u in sorted(set(uses)) if self._member_crosses(u, before, after)]
        return found

    def _moving_file_findings(self, moved: dict[str, Move]) -> list[Finding]:
        found: list[Finding] = []
        for path in sorted(moved):
            found += [Finding("generated-symbol", path, f"`{fqn}`: no source file declares it", False) for fqn in self.generated[path]]
            lookups = sorted({m.group(0).rstrip("( ") for m in _RESOURCE.finditer(self.code[path])})
            if lookups:
                found.append(Finding("resource-lookup", path, ", ".join(lookups), False))
            loggers = sorted(set(_STRING_LOGGER.findall(self.texts[path])))
            if loggers:
                found.append(Finding("string-logger", path, ", ".join(f'"{n}"' for n in loggers), False))
        return found

    def _external_imports(self, moved: dict[str, Move]) -> list[Finding]:
        prefixes: dict[str, set[str]] = defaultdict(set)
        for path, move in moved.items():
            for match in _IMPORT_DECL.finditer(self.code[path]):
                fqn = match.group(1).replace("`", "")
                if fqn != PROJECT and not fqn.startswith(PROJECT + "."):
                    prefixes[f"{gradle(move.target)} {move.kind}"].add(_import_prefix(fqn, bool(match.group(2))))
        return [Finding("external-imports", key, ", ".join(sorted(values)), False) for key, values in sorted(prefixes.items())]

    def _colocated(self, a: str, b: str) -> bool:
        return any(a in rt and b in rt for rt in self.graph.runtime.values())

    def _collisions(self, moved: dict[str, Move], after: dict[str, str]) -> list[Finding]:
        """Same JVM class (top-level class FQN or file facade, `@file:JvmName` honoured) in two modules that share a
        runtime classpath and a platform. Test classes never reach a jar and are skipped. Reported whether or not a
        moving file is involved: after the move no such pair may exist."""
        classes: dict[str, list[str]] = defaultdict(list)
        for path in sorted(self.texts):
            if self.test[path]:
                continue
            package, code = self.package[path], self.code[path]
            prefix = f"{package}." if package else ""
            names = {f"{prefix}{n}" for n in (m.group(1) for m in _CLASS_LIKE.finditer(code))}
            if _CALLABLE.search(code):
                jvm_name = _JVM_NAME.search(self.texts[path])
                names.add(prefix + (jvm_name.group(1) if jvm_name else _facade(path.rsplit("/", 1)[-1].removesuffix(".kt"))))
            for name in names:
                classes[name].append(path)
        found: list[Finding] = []
        for name, paths in sorted(classes.items()):
            clashes = [(a, b) for i, a in enumerate(paths) for b in paths[i + 1:]
                       if after[a] != after[b] and self._colocated(after[a], after[b])
                       and _platforms(locate(a)[1]) & _platforms(locate(b)[1])]  # type: ignore[index]
            for a, b in clashes:
                path, other = (b, a) if b in moved and a not in moved else (a, b)
                found.append(Finding("collision", path, f"JVM class `{name}` also in {other} ({after[other]}); this file in {after[path]}", True))
        return found

    def _expect_actual(self, after: dict[str, str]) -> list[Finding]:
        found: list[Finding] = []
        for path in sorted(self.texts):
            for name in sorted(_keyword_names(self.code[path], "expect")):
                for other in self.by_package[self.package[path]]:
                    if other != path and name in _keyword_names(self.code[other], "actual") and after[other] != after[path]:
                        found.append(Finding("expect-actual-split", path, f"`expect {name}` in {after[path]}, `actual` in {other} ({after[other]})", True))
        return found


def checks(files: dict[str, str], moves: list[Move], targets: dict[str, str], module_graph: ModuleGraph,
           assumed: dict[str, str] | None = None) -> list[Finding]:
    """All mover checks for `moves` over `files` (every Kotlin file of every module). `targets` (path -> target) is the
    plan's input and is not needed beyond `moves`; `assumed` (path -> module) are earlier tiers' moves taken as done."""
    del targets
    return Checker(files, module_graph).findings(moves, assumed)


# ---------------------------------------------------------------------------------------------------------------------
# Package rename (not used in Stage B2-1b: no package or FQN changes)
# ---------------------------------------------------------------------------------------------------------------------

_STRING_LITERAL = re.compile(r"\"\"\"(?:.|\n)*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"")
_TEMPLATE = re.compile(r"\$\{[^{}]*\}")


def rename_package(files: dict[str, str], old: str, new: str) -> dict[str, str]:
    """path -> new text for every file that changes. The `package old` line of the package's own files; in every file
    (Kotlin of any module and set, `*.kts`): imports, fully qualified uses and KDoc/comment links `old.X` where X is a
    top-level name of `old` (or `*`), including uses inside `${...}` string templates. A subpackage `old.sub` is untouched.
    LIMIT: the plain text of string and char literals is NOT rewritten (`"old.X"` may be a log category, a reflective
    `Class.forName` name or user-visible text, so it needs a human decision); `string_literal_mentions` lists the files
    that still carry such text and the CLI warns about them."""
    members = [p for p, t in files.items() if p.endswith(".kt") and sp.package_of(t) == old]
    if not members:
        return {}
    names = sorted(set().union(*(_top_level_names(sp.code_only(files[p])) for p in members)), key=len, reverse=True)
    member = r"\*" + "".join(f"|{re.escape(n)}\\b" for n in names)
    reference = re.compile(rf"(?<![\w.]){re.escape(old)}\.(?=(?:{member}))")
    pattern = re.compile(
        r"(?P<skip>'(?:\\.|[^'\\\n])'|\"\"\"(?:.|\n)*?\"\"\"|\"(?:\\.|[^\"\\\n])*\")"
        rf"|(?P<ref>{reference.pattern})"
        rf"|(?P<package>^package\s+{re.escape(old)}(?=\s*;?\s*$))", re.MULTILINE)

    def replace(match: re.Match[str]) -> str:
        if match.group("skip") is not None:  # a literal: only its `${...}` template expressions are code
            return _TEMPLATE.sub(lambda t: reference.sub(new + ".", t.group(0)), match.group(0))
        return match.group(0).replace(old, new, 1)

    changed = {path: pattern.sub(replace, text) for path, text in files.items()}
    return {path: text for path, text in changed.items() if text != files[path]}


def string_literal_mentions(files: dict[str, str], old: str) -> list[str]:
    """Files whose string literals still name `old.` outside a `${...}` template (what `rename_package` leaves alone)."""
    mention = re.compile(rf"(?<![\w.]){re.escape(old)}\.")
    return sorted(path for path, text in files.items()
                  if any(mention.search(_TEMPLATE.sub("", m.group(0))) for m in _STRING_LITERAL.finditer(text)))


def untracked_findings(moves: list[Move], tracked: set[str]) -> list[Finding]:
    """Blocking: `git mv` of an untracked source fails (or, worse, a new file would be moved without history)."""
    return [Finding("untracked-source", m.src, "not tracked by git (git ls-files)", True) for m in moves if m.src not in tracked]


PathKind = Callable[[str], str | None]  # path -> "file" | "dir" | None (absent)


def preflight_findings(moves: list[Move], kind_of: PathKind) -> list[Finding]:
    """Blocking, checked before the first `git mv`: every destination is free, no parent of one is a file, no two
    moves share a destination."""
    findings: list[Finding] = []
    counts = Counter(m.dst for m in moves)
    for move in moves:
        if counts[move.dst] > 1:
            findings.append(Finding("duplicate-dst", move.dst, f"{counts[move.dst]} moves target it (one is {move.src})", True))
        if kind_of(move.dst) is not None:
            findings.append(Finding("dst-exists", move.dst, f"destination of {move.src} already exists", True))
        parts = move.dst.split("/")[:-1]
        for depth in range(1, len(parts) + 1):
            parent = "/".join(parts[:depth])
            if kind_of(parent) == "file":
                findings.append(Finding("dst-parent-is-file", move.dst, f"{parent} is a file", True))
                break
    return sorted(set(findings))


class Resume(NamedTuple):
    done: set[tuple[str, str]]  # staged renames that are moves of the plan
    problems: list[str]         # tracked changes that are not (refuse --apply)


def staged_renames(status: str) -> list[tuple[str, str]]:
    """(old, new) of every `R ` line of `git status --porcelain=v1`."""
    renames = []
    for line in status.splitlines():
        if line.startswith("R") and " -> " in line:
            old, new = line[3:].split(" -> ", 1)
            renames.append((old, new))
    return renames


def resume_state(status: str, moves: list[Move]) -> Resume:
    """Resume rule: after a `git mv` failure part of the plan is staged as renames. The tree counts as clean for
    `--apply` when every tracked change is a staged rename (`R` in the index column, nothing in the worktree column)
    whose (old, new) pair is a move of the plan computed on the pre-apply view; those moves are done, the rest resume.
    Anything else (a modification, a rename the plan does not contain, an unstaged change) is dirt."""
    planned = {(m.src, m.dst) for m in moves}
    done: set[tuple[str, str]] = set()
    problems: list[str] = []
    for line in status.splitlines():
        if not line.strip():
            continue
        if line[:2] == "R " and " -> " in line:
            pair = tuple(line[3:].split(" -> ", 1))
            if pair in planned:
                done.add(pair)  # type: ignore[arg-type]
                continue
            problems.append(f"staged rename not in the plan: {line[3:]}")
        else:
            problems.append(f"tracked change: {line}")
    return Resume(done, problems)


def pre_apply_view(files: dict[str, str], renames: list[tuple[str, str]]) -> dict[str, str]:
    """The tree as it was before a partial apply: each staged rename put back at its old path."""
    view = dict(files)
    for old, new in renames:
        if new in view:
            view[old] = view.pop(new)
    return view


def plan_moves(files: dict[str, str], targets: dict[str, str], module_dirs: dict[str, ModuleDir], area: str, tier: str | None,
               is_closed: ClosurePredicate = keep_tests_in_place) -> list[Move]:
    return plan(files, targets, module_dirs, area, tier, is_closed).moves


def apply_refusal(dirty: bool, findings: list[Finding]) -> str | None:
    """Why `--apply` must not run, or None. `dirty` means tracked changes other than the plan's own staged renames
    (see `resume_state`)."""
    if dirty:
        return "refusing --apply: the git tree is dirty (commit or discard tracked changes first; staged renames of this plan are allowed)"
    blocking = [f for f in findings if f.blocking]
    if blocking:
        return f"refusing --apply: {len(blocking)} blocking finding(s)"
    return None


class PartialApply(Exception):
    """A command failed after `done` moves; `remaining` starts with the failed move."""

    def __init__(self, done: list[Move], remaining: list[Move], error: str) -> None:
        super().__init__(error)
        self.done = done
        self.remaining = remaining
        self.error = error


def apply_moves(moves: list[Move], run: Callable[[list[str]], None]) -> None:
    """`mkdir -p` once per destination directory, then `git mv` per move, in sorted order. Raises PartialApply when a
    command fails; the moves before it stay staged."""
    ordered = sorted(moves)
    created: set[str] = set()
    for index, move in enumerate(ordered):
        parent = move.dst.rsplit("/", 1)[0]
        try:
            if parent not in created:
                run(["mkdir", "-p", parent])
                created.add(parent)
            run(["git", "mv", move.src, move.dst])
        except (subprocess.CalledProcessError, OSError) as error:
            raise PartialApply(ordered[:index], ordered[index:], f"{move.src} -> {move.dst}: {error}") from error


def render_progress(partial: PartialApply) -> str:
    lines = ["# move_area.py partial apply", f"# failed: {partial.error}",
             f"# {len(partial.done)} done (staged renames), {len(partial.remaining)} remaining", "", "## done (src -> dst)"]
    lines += [f"{m.src} -> {m.dst}" for m in partial.done]
    lines += ["", "## reverse (run from the repository root to undo the done renames)"]
    lines += [f"git mv {m.dst} {m.src}" for m in reversed(partial.done)]
    lines += ["", "## remaining (src -> dst; the first one failed)"]
    lines += [f"{m.src} -> {m.dst}" for m in partial.remaining]
    return "\n".join(lines) + "\n"


def recovery_message(partial: PartialApply, progress_path: str, command: str) -> str:
    return "\n".join([
        f"git mv failed after {len(partial.done)} of {len(partial.done) + len(partial.remaining)} moves: {partial.error}",
        f"Done/remaining lists and the reverse commands are in {progress_path}.",
        "The completed renames are staged; nothing is committed. Inspect `git status` first, then either:",
        f"  - undo: run the `git mv` lines of the `reverse` section of {progress_path} (in the listed order), or",
        f"  - resume: fix the cause and re-run `{command}`; staged renames that match the plan count as done and only the",
        "    remaining moves run (any other tracked change makes --apply refuse).",
        "Do not discard the index or the worktree wholesale without reading `git status`: other work may be in it.",
    ]) + "\n"


TierPlans = list[tuple[str, Plan]]


def render_report(result: Plan, area: str, tier: str | None, already_done: int = 0, per_tier: TierPlans | None = None,
                  assume_graph: str | None = None) -> str:
    scope = f"`--area {area}`" + (f" `--tier {tier}`" if tier else " (all tiers)") + (f" `--assume-graph {assume_graph}`" if assume_graph else "")
    counts: Counter[tuple[str, str, str]] = Counter()
    for move in result.moves:
        located = locate(move.src)
        counts[(gradle(move.target), located[1] if located else "?", move.kind)] += 1
    kinds = Counter((f.kind, f.blocking) for f in result.findings)
    blocking = sum(1 for f in result.findings if f.blocking)
    lines = ["# Move plan", "",
             f"Generated by `scripts/structure/move_area.py` {scope} (dry-run); do not edit by hand. Each move is a plain `git mv` "
             "to `<module directory>/src/<same set>/kotlin/<same path>`: no package or FQN changes, `resources/` never move, a file "
             "already in place gives no move. A test follows its subject only when it lives in the target's source module and is "
             "compile-closed in the target module; otherwise it stays (`test-stays`). `non-kotlin-orphan` for a directory holding "
             "no Kotlin file (e.g. `features/tse/PROTECTED.md`) is reported on every run on purpose: such a file never moves. "
             "Compile closure: the real build graph (`api` re-exports, `implementation` does not) merged with the assumed edges; "
             "a reference is reported only when it resolves before the move and not after. A `--tier` run assumes the lower "
             "tiers (api, then data) already moved.", "",
             f"{len(result.moves)} moves ({sum(1 for m in result.moves if m.kind == 'main')} main, "
             f"{sum(1 for m in result.moves if m.kind == 'test')} test), {len(result.findings)} findings ({blocking} blocking)."]
    if already_done:
        lines += ["", f"Resuming: {already_done} of these moves are already staged renames; {len(result.moves) - already_done} remain."]
    lines += ["", "| target module | source set | kind | moves |", "|---|---|---|---|"]
    lines += [f"| {module} | {source_set} | {kind} | {n} |" for (module, source_set, kind), n in sorted(counts.items())]
    lines += ["", "## Findings by kind", "", "| kind | blocking | count |", "|---|---|---|"]
    lines += [f"| {kind} | {'yes' if block else 'no'} | {n} |" for (kind, block), n in sorted(kinds.items())]
    if per_tier:
        lines += ["", "## Per tier", "", "Each tier run assumes the tiers before it already moved (Tasks 7, 8, 9: api, data, server).", "",
                  "| tier | moves (main/test) | findings | blocking |", "|---|---|---|---|"]
        lines += [f"| {name} | {len(p.moves)} ({sum(1 for m in p.moves if m.kind == 'main')}/{sum(1 for m in p.moves if m.kind == 'test')}) "
                  f"| {len(p.findings)} | {sum(1 for f in p.findings if f.blocking)} |" for name, p in per_tier]
        lines += ["", "| tier | kind | blocking | count |", "|---|---|---|---|"]
        for name, p in per_tier:
            lines += [f"| {name} | {kind} | {'yes' if block else 'no'} | {n} |" for (kind, block), n in sorted(Counter((f.kind, f.blocking) for f in p.findings).items())]
    lines += ["", "## Findings", "", "| kind | blocking | path | detail |", "|---|---|---|---|"]
    lines += [f"| {f.kind} | {'yes' if f.blocking else 'no'} | `{f.path}` | {f.detail} |" for f in result.findings]
    lines += ["", "## Moves", "", "| target | kind | from | to |", "|---|---|---|---|"]
    lines += [f"| {m.target} | {m.kind} | `{m.src}` | `{m.dst}` |" for m in result.moves]
    return "\n".join(lines) + "\n"


_RENAME = re.compile(r"^([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)=([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)$")


def parse_args(argv: list[str]) -> Options | None:
    values: dict[str, str] = {}
    apply = False
    args = list(argv)
    if args and args[0] == "--check-jars":
        return Options(None, None, False, None, check_jars=tuple(args[1:])) if len(args) > 1 else None
    while args:
        flag = args.pop(0)
        if flag == "--apply":
            apply = True
        elif flag in ("--area", "--tier", "--report", "--assume-graph", "--rename-package") and args:
            values[flag] = args.pop(0)
        else:
            return None
    area, tier = values.get("--area"), values.get("--tier")
    rename = None
    if "--rename-package" in values:
        match = _RENAME.match(values["--rename-package"])
        if match is None or match.group(1) == match.group(2):
            return None
        rename = (match.group(1), match.group(2))
    elif area not in AREAS:
        return None
    if (area is not None and area not in AREAS) or (tier is not None and tier not in TIERS):
        return None
    return Options(area, tier, apply, values.get("--report"), values.get("--assume-graph"), rename)


def progress_path(options: Options) -> str:
    """Where a partial apply writes its done/remaining lists: next to the report (or a fixed name under docs/audit)."""
    if options.report:
        return options.report.removesuffix(".md") + ".apply-progress.txt"
    return f"docs/audit/move-area-{options.area}{'-' + options.tier if options.tier else ''}.apply-progress.txt"


def collect_all_files() -> dict[str, str]:
    """Kotlin files of the blob modules and the module-dirs directories, plus non-Kotlin files under their `kotlin/` dirs."""
    files = mm.collect_files()
    roots = list(mm.MODULES) + [d.directory for d in load_module_dirs((ROOT / DEFAULT_MODULE_DIRS).read_text(encoding="utf-8")).values()]
    for root in roots:
        source = ROOT / root / "src"
        if not source.is_dir():
            continue
        for path in source.rglob("*"):
            rel = path.relative_to(ROOT).as_posix()
            if path.is_file() and rel not in files and locate(rel) is not None:
                files[rel] = path.read_text(encoding="utf-8", errors="replace")
    return files


def collect_universe(files: dict[str, str], directories: dict[str, str]) -> dict[str, str]:
    """`files` plus the Kotlin files of every other module directory (the symbol index of the checks)."""
    universe = dict(files)
    for directory in sorted(directories):
        source = ROOT / directory / "src"
        if not source.is_dir():
            continue
        for path in source.rglob("*.kt"):
            rel = path.relative_to(ROOT).as_posix()
            if rel not in universe and module_of(rel, directories) == directories[directory] and locate(rel) is not None:
                universe[rel] = path.read_text(encoding="utf-8", errors="replace")
    return universe


def checked_plan(files: dict[str, str], targets: dict[str, str], module_dirs: dict[str, ModuleDir], area: str, tier: str | None,
                 checker: Checker, assumed: dict[str, str] | None = None) -> Plan:
    """The plan with the real test closure, plus the checks' findings. `assumed`: earlier tiers' moves taken as done."""
    checker.stay_reasons.clear()
    result = plan(files, targets, module_dirs, area, tier, checker.closure(assumed))
    stays = [f._replace(detail=f"{f.detail}: {checker.stay_reasons[f.path]}") if f.kind == "test-stays" and f.path in checker.stay_reasons else f
             for f in result.findings]
    return Plan(result.moves, sorted(stays + checker.findings(result.moves, assumed)))


def tier_plans(files: dict[str, str], targets: dict[str, str], module_dirs: dict[str, ModuleDir], area: str, checker: Checker,
               until: str | None = None) -> TierPlans:
    """Cumulative per-tier plans in extraction order (api, data, server), each assuming the ones before it moved."""
    assumed: dict[str, str] = {}
    plans: TierPlans = []
    for tier in TIERS:
        result = checked_plan(files, targets, module_dirs, area, tier, checker, assumed)
        plans.append((tier, result))
        if tier == until:
            break
        assumed.update({m.src: gradle(m.target) for m in result.moves})
    return plans


def _git_ls(patterns: list[str]) -> list[str]:
    listing = subprocess.run(["git", "ls-files", "--", *patterns], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return sorted(listing.splitlines())


def rename_main(options: Options) -> int:
    """`--rename-package old=new`: every tracked `*.kt`/`*.kts` (all modules, ui, tests, build scripts); dry-run lists the
    files that would change, `--apply` writes them (files stay where they are; directories are not renamed)."""
    assert options.rename is not None
    old, new = options.rename
    files = {p: (ROOT / p).read_text(encoding="utf-8", errors="replace") for p in _git_ls(["*.kt", "*.kts"]) if (ROOT / p).is_file()}
    changed = rename_package(files, old, new)
    sys.stdout.write("".join(f"{path}\n" for path in sorted(changed)))
    sys.stdout.write(f"{len(changed)} file(s) {'rewritten' if options.apply else 'would change (dry-run; --apply writes them)'}: {old} -> {new}\n")
    after = {**files, **changed}
    for path in string_literal_mentions(after, old):
        sys.stderr.write(f"warning: {path}: a string literal still names {old}. (not rewritten; decide by hand)\n")
    if options.apply:
        for path, text in sorted(changed.items()):
            (ROOT / path).write_text(text, encoding="utf-8")
    return 0


def duplicate_classes(listings: dict[str, list[str]]) -> dict[str, list[str]]:
    """`--check-jars`: class entry -> the jars (sorted) holding it, for every class in more than one jar. `listings` maps a
    jar to its `unzip -Z1` entries. Only class files count: `META-INF/` (manifests, `*.kotlin_module`, multi-release
    `versions/`), `module-info.class`, resources and directory entries are not classes that shadowJar could shadow."""
    holders: dict[str, set[str]] = defaultdict(set)
    for jar, entries in listings.items():
        for entry in entries:
            if entry.endswith(".class") and not entry.startswith("META-INF/") and entry.rsplit("/", 1)[-1] != "module-info.class":
                holders[entry].add(jar)
    return {entry: sorted(jars) for entry, jars in sorted(holders.items()) if len(jars) > 1}


def _jar_listing(jar: str) -> list[str]:
    return subprocess.run(["unzip", "-Z1", jar], capture_output=True, text=True, check=True).stdout.splitlines()


def check_jars_main(jars: tuple[str, ...]) -> int:
    """Prints `<class> TAB <jar>, <jar>` per duplicate; exit 1 when there is any."""
    duplicates = duplicate_classes({jar: _jar_listing(jar) for jar in jars})
    sys.stdout.write("".join(f"{entry}\t{', '.join(holders)}\n" for entry, holders in duplicates.items()))
    sys.stdout.write(f"{len(duplicates)} duplicate class(es) across {len(jars)} jar(s)\n" if duplicates
                     else f"no duplicate classes across {len(jars)} jar(s)\n")
    return 1 if duplicates else 0


def _git_status() -> str:
    """Tracked changes only: untracked files never block a move."""
    return subprocess.run(["git", "status", "--porcelain=v1", "--untracked-files=no"], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def _tracked(paths: list[str]) -> set[str]:
    if not paths:
        return set()
    listing = subprocess.run(["git", "ls-files", "--", *paths], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return set(listing.splitlines())


def _path_kind(path: str) -> str | None:
    full = ROOT / path
    return "file" if full.is_file() else "dir" if full.is_dir() else None


def _run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def main(argv: list[str]) -> int:
    options = parse_args(argv)
    if options is None:
        sys.stderr.write(__doc__.split("Usage: ", 1)[1].split("\n", 1)[0] + "\n")
        return 2
    if options.check_jars:
        return check_jars_main(options.check_jars)
    if options.rename:
        return rename_main(options)
    assert options.area is not None
    status = _git_status()
    files = pre_apply_view(collect_all_files(), staged_renames(status))
    areas = mm.ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
    overrides = mm.ur.load_overrides((ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8"))
    try:
        targets, problems = sp.effective_targets({p: t for p, t in files.items() if p.endswith(".kt")})
        if problems:
            raise ValueError("\n".join(problems))
        targets.update(subject_targets(files, targets, lambda module, package: mm.target_for(module, package, areas, overrides)))
        module_dirs = load_module_dirs((ROOT / DEFAULT_MODULE_DIRS).read_text(encoding="utf-8"))
        assume_text = (ROOT / options.assume_graph).read_text(encoding="utf-8") if options.assume_graph else ""
        graph = load_module_graph(ROOT, assume_text)
        checker = Checker(collect_universe(files, graph.directories), graph)
        if options.tier:
            result = tier_plans(files, targets, module_dirs, options.area, checker, until=options.tier)[-1][1]
            per_tier = None
        else:
            result = checked_plan(files, targets, module_dirs, options.area, None, checker)
            per_tier = tier_plans(files, targets, module_dirs, options.area, checker)
    except ValueError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    resume = resume_state(status, result.moves)
    remaining = [m for m in result.moves if (m.src, m.dst) not in resume.done]
    findings = sorted(result.findings + untracked_findings(remaining, _tracked([m.src for m in remaining]))
                      + preflight_findings(remaining, _path_kind))
    report = render_report(Plan(result.moves, findings), options.area, options.tier, len(resume.done), per_tier, options.assume_graph)
    if options.apply:
        refusal = apply_refusal(bool(resume.problems), findings)
        if refusal:
            sys.stderr.write("\n".join([refusal, *resume.problems[:20]]) + "\n")
            return 1
        try:
            apply_moves(remaining, _run)
        except PartialApply as partial:
            path = progress_path(options)
            (ROOT / path).write_text(render_progress(partial), encoding="utf-8")
            sys.stderr.write(recovery_message(partial, path, "python3 -I scripts/structure/move_area.py " + " ".join(argv)))
            return 1
    sys.stdout.write(report)
    if options.report:
        (ROOT / options.report).write_text(report, encoding="utf-8")
    return 1 if any(f.blocking for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
