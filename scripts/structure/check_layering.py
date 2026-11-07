#!/usr/bin/env python3
"""Module layering checker (ZenBon restructuring spec section 5).

Rules:
  R1 a module may not depend on a module of a higher area (AREAS order).
  R2 `ui` modules may depend only on tiers kernel, api, contract, client-lib; tier `shell` (androidApp)
     may additionally depend on tier `app`.
  R3 no feature -> feature dependency.
  R4 every included module is listed in config/structure/modules.tsv and vice versa.
  R5 a module outside the `ui` and `host` areas may not depend on a `ui` module.
  R6 tier rank kernel < api < data < server (`contract` is an alias of `api`, `feature` of `server`).
     Within an area a module may depend only on modules of equal or lower rank; across areas (along
     R1, lower area only) only on kernel/api/data modules, never on another area's `server` tier.
     Applies between modules of the non-ui, non-host areas whose tier has a rank; `client-lib`,
     `app` and `shell` are governed by R2/R5 and have no rank.
     Exception: platform is the foundation area every server may use, so the cross-area server
     reason does not fire when the target's area is `platform` (spec section 15, D1). Platform itself
     still may not depend upward (R1) and the tier order within an area is unchanged.

  R7 a module of tier `api`/`contract` may depend only on tiers kernel, api, contract (any lower area per
     R1). An edge R6 already reports (same-area api -> data/server, cross-area server) is reported by R6
     only, so R6 and R7 never both report the same edge; R7 reports the remaining ones (e.g. cross-area api -> data).

The `host` area (pos-server) is exempt as a *source* of R1-R3 violations: it wires everything.
Known violations are frozen in config/structure/layering-baseline.txt. The check fails on a
violation missing from the baseline and on a baseline line that no longer occurs (baselines only shrink).

Usage: python3 -I scripts/structure/check_layering.py [--print-baseline]
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

AREAS = ["platform", "ecr", "fiscal", "accounting", "backoffice"]
EXTRA_AREAS = ["ui", "host"]
TIERS = ["kernel", "api", "contract", "data", "server", "feature", "client-lib", "app", "shell", "host"]
# R6 rank per tier; aliases share a rank. Tiers absent here (client-lib, app, shell, host) are unranked.
TIER_RANK = {"kernel": 0, "api": 1, "contract": 1, "data": 2, "server": 3, "feature": 3}
API_TIERS = {"api", "contract"}  # R7
FOUNDATION_AREA = "platform"  # R6: servers of any area may depend on platform servers
UI_ALLOWED_TIERS = {"kernel", "api", "contract", "client-lib"}  # spec 5.3: ui -> platform-api, any area api
SHELL_EXTRA = {"app"}  # a `shell` ui module (androidApp) may also depend on `app` modules
ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Module:
    area: str
    tier: str


def parse_settings(text: str) -> dict[str, str]:
    """Gradle path -> directory; `project(":x").projectDir = file("a/b")` overrides the default."""
    code = _strip_comments(text)
    paths = {p: p.lstrip(":").replace(":", "/") for p in re.findall(r'include\(\s*"(:[^"]+)"', code)}
    for path, directory in re.findall(r'project\(\s*"(:[^"]+)"\s*\)\.projectDir\s*=\s*file\(\s*"([^"]+)"', code):
        paths[path] = directory
    return paths


def accessor_of(path: str) -> str:
    """':ui-shared' -> 'uiShared' (Gradle typesafe project accessor)."""
    first, *rest = path.lstrip(":").split("-")
    return first + "".join(part[:1].upper() + part[1:] for part in rest)


# Only real dependency declarations count: a configuration call (implementation, api, compileOnly,
# runtimeOnly, including prefixed forms such as testImplementation / commonMainApi), the string forms
# `"implementation"(...)` and `add("implementation", ...)`, whose arguments reference `projects.x` or
# `project(":x")` / `project(path = ":x", configuration = "...")`. Other uses of project(":x"), e.g.
# `project(":pos-tse").file(...)` or `from(project(":ui-till").layout...)` in task wiring, are
# build-directory lookups and are deliberately not counted. Inside a declaration, a `project(` that
# cannot be resolved to a known module is an error, never silently ignored.
_CONFIG_NAME = r"(?:[a-z][A-Za-z0-9]*(?:Implementation|Api|CompileOnly|RuntimeOnly)|implementation|api|compileOnly|runtimeOnly)"
_CONFIG_CALL = re.compile(
    r"(?<![A-Za-z0-9_\"])" + _CONFIG_NAME + r"\s*\("
    r"|\"" + _CONFIG_NAME + r"\"\s*\("
    r"|(?<![A-Za-z0-9_])add\(\s*\"" + _CONFIG_NAME + r"\"\s*,"
)
_ACCESSOR = re.compile(r"\bprojects\.([A-Za-z]\w*)")
_PROJECT_OPEN = re.compile(r"\bproject\s*\(")
_PROJECT_CALL = re.compile(r'project\(\s*(?:path\s*=\s*)?"(:[^"]+)"\s*(?:,[^()]*)?\)')


class LayeringError(Exception):
    """The build files cannot be analysed reliably; the check must fail rather than guess."""


def _call_args(code: str, start: int) -> str:
    """Text between the parenthesis opened just before `start` and its match (may span lines)."""
    depth = 1
    for i in range(start, len(code)):
        depth += {"(": 1, ")": -1}.get(code[i], 0)
        if depth == 0:
            return code[start:i]
    return code[start:]


def _line_of(code: str, offset: int) -> int:
    return code.count("\n", 0, offset) + 1


def module_deps(build_text: str, known_paths: list[str]) -> set[str]:
    by_accessor = {accessor_of(p): p for p in known_paths}
    code = _strip_comments(build_text)
    deps: set[str] = set()
    for call in _CONFIG_CALL.finditer(code):
        args = _call_args(code, call.end())
        deps.update(by_accessor[a] for a in _ACCESSOR.findall(args) if a in by_accessor)
        for opened in _PROJECT_OPEN.finditer(args):
            resolved = _PROJECT_CALL.match(args, opened.start())
            if resolved is None or resolved.group(1) not in known_paths:
                line = _line_of(code, call.end() + opened.start())
                snippet = args[opened.start():opened.start() + 60].splitlines()[0]
                raise LayeringError(f"line {line}: cannot resolve dependency {snippet!r} to a known module")
            deps.add(resolved.group(1))
    return deps


def violations(modules: dict[str, Module], deps: dict[str, set[str]]) -> list[str]:
    out: list[str] = []
    for src, targets in sorted(deps.items()):
        if src not in modules or modules[src].area == "host":
            continue
        for dst in sorted(targets):
            if dst in modules:
                out.extend(_edge_violations(src, dst, modules[src], modules[dst]))
    return out


def _edge_violations(src: str, dst: str, a: Module, b: Module) -> list[str]:
    found: list[str] = []
    if a.area in AREAS and b.area in AREAS and AREAS.index(b.area) > AREAS.index(a.area):
        found.append(f"{src} -> {dst}: R1 area {a.area} depends on higher area {b.area}")
    if a.area == "ui" and b.tier not in UI_ALLOWED_TIERS | (SHELL_EXTRA if a.tier == "shell" else set()):
        found.append(f"{src} -> {dst}: R2 ui depends on tier {b.tier}")
    if a.area not in ("ui", "host") and b.area == "ui":
        found.append(f"{src} -> {dst}: R5 non-ui depends on ui")
    if a.tier == "feature" and b.tier == "feature":
        found.append(f"{src} -> {dst}: R3 feature depends on feature")
    r6 = _r6_violations(src, dst, a, b)
    found.extend(r6)
    if not r6:  # an edge R6 already reports (same-area tier order, cross-area server) is not reported twice
        found.extend(_r7_violations(src, dst, a, b))
    return found


def _r7_violations(src: str, dst: str, a: Module, b: Module) -> list[str]:
    """R7: an api/contract module depends only on kernel/api/contract modules (any lower area per R1).

    ui targets are R5's to report, so they are skipped here."""
    if a.area not in AREAS or a.tier not in API_TIERS or b.area == "ui":
        return []
    if b.tier in API_TIERS | {"kernel"}:
        return []
    return [f"{src} -> {dst}: R7 api depends on non-api"]


def _r6_violations(src: str, dst: str, a: Module, b: Module) -> list[str]:
    if a.area not in AREAS or b.area not in AREAS or a.tier not in TIER_RANK or b.tier not in TIER_RANK:
        return []
    if a.area == b.area:
        if TIER_RANK[b.tier] > TIER_RANK[a.tier]:
            return [f"{src} -> {dst}: R6 tier order {a.tier} depends on {b.tier}"]
        return []
    if b.area == FOUNDATION_AREA:  # platform is the foundation area every server may use (spec section 15, D1)
        return []
    if AREAS.index(b.area) < AREAS.index(a.area) and TIER_RANK[b.tier] == TIER_RANK["server"]:
        return [f"{src} -> {dst}: R6 cross-area server dependency"]
    return []  # upward-area edges are R1's to report


def registry_violations(modules: dict[str, Module], included: list[str]) -> list[str]:
    out = [f"{p} -> -: R4 included module not listed in modules.tsv" for p in sorted(set(included) - set(modules))]
    out += [f"{p} -> -: R4 listed in modules.tsv but not included" for p in sorted(set(modules) - set(included))]
    return out


def parse_modules(text: str) -> dict[str, Module]:
    mods: dict[str, Module] = {}
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        cols = line.split("\t")
        if len(cols) != 3 or cols[1] not in AREAS + EXTRA_AREAS or cols[2] not in TIERS:
            raise ValueError(f"modules.tsv line {n}: expected '<path>\\t<area>\\t<tier>', got {raw!r}")
        mods[cols[0]] = Module(area=cols[1], tier=cols[2])
    return mods


def _strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group().count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def collect(root: Path) -> tuple[dict[str, Module], list[str]]:
    included = parse_settings((root / "settings.gradle.kts").read_text())
    mods = parse_modules((root / "config/structure/modules.tsv").read_text())
    problems = registry_violations(mods, list(included))
    deps: dict[str, set[str]] = {}
    for path, directory in included.items():
        build = root / directory / "build.gradle.kts"
        if not build.exists():
            deps[path] = set()
            continue
        try:
            deps[path] = module_deps(build.read_text(), list(included))
        except LayeringError as err:
            raise LayeringError(f"{build.relative_to(root)}: {err}") from err
    return mods, problems + violations(mods, deps)


def main(argv: list[str]) -> int:
    try:
        _, found = collect(ROOT)
    except LayeringError as err:
        print(f"checkLayering: ERROR {err}", file=sys.stderr)
        return 2
    if "--print-baseline" in argv:
        print("\n".join(sorted(found)))
        return 0
    baseline_file = ROOT / "config/structure/layering-baseline.txt"
    baseline = {l.strip() for l in baseline_file.read_text().splitlines() if l.strip() and not l.startswith("#")}
    new = sorted(set(found) - baseline)
    stale = sorted(baseline - set(found))
    for v in new:
        print(f"NEW VIOLATION: {v}")
    for v in stale:
        print(f"STALE BASELINE LINE (fixed, remove it): {v}")
    if new or stale:
        return 1
    print(f"checkLayering: ok ({len(baseline)} baselined violations)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
