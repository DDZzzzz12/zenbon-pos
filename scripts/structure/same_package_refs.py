#!/usr/bin/env python3
"""Same-package references that cross a placement boundary (ZenBon restructuring, spec sections 12/15).

Kotlin needs no import for a type declared in the same package, so upward_refs.py (import-based) cannot see a
reference from one file to a sibling placed elsewhere. This tool finds them: for every package whose files land in
different target modules it maps each public top-level declared name to its declaring file and scans the OTHER files
of that package for the name.

Kinds
- "blocker": the declaring file is placed in a HIGHER area than the using file (platform < ecr < fiscal < accounting
  < backoffice < host), or in the host while the user is in an area: the user cannot compile once split.
- "internal": the declaration is `internal` and the user's file goes to a different Gradle module (a different target),
  whatever the areas: `internal` does not cross a module boundary, so the mover must treat it as blocking.
- "module": same area, different module (needs a module dependency; informational).
- "lower-module": the declaring file is in a LOWER area and a different module (informational: the user's module needs
  that dependency). A user in the host is exempt from this one (the host depends on every module).
A declaration in a lower area within the same module, or a user in the host (blocker/module only), is fine and not reported.
An extension-only name is dropped only when a sibling file declares the same name as an extension on another receiver that the
using file names, while it names none of this declarer's receivers (an overload set such as one `getLocalizedName` per type);
an inferred receiver (`call.shopIdOrThrow()`) is still reported.

Heuristic and deliberately over-inclusive: word-boundary token match over text with comments and string literals
removed; a generic name declared at top level in one file and used as a local name elsewhere is reported anyway.
Imports, the package line and fully qualified `package.Name` uses are not same-package references. Private
declarations are ignored. A name the using file declares itself (an extension overload on another receiver, e.g. one `getName` per enum in
core/utils/enumStrings) is not a reference to the sibling's declaration.

Exit code 1 only for malformed overrides; findings never fail the run.
Usage: python3 -I scripts/structure/same_package_refs.py [--out docs/audit/2026-10-26-same-package-refs.md]
"""
from __future__ import annotations

import importlib.util
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parents[2]
ORDER = ["platform", "ecr", "fiscal", "accounting", "backoffice", "host"]
_PACKAGE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
_MODIFIERS = r"(?:(?:public|internal|protected|data|sealed|enum|abstract|open|inline|value|annotation|const|suspend|tailrec|operator|infix|expect|actual|lateinit|external|fun)\s+)*"
_DECL = re.compile(rf"^(?:@[\w.]+(?:\([^)]*\))?\s+)*{_MODIFIERS}(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?`?(\w+)", re.MULTILINE)
_PRIVATE = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*private\s")
_EXTENSION = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*" + _MODIFIERS + r"fun\s+(?:<[^>]*>\s*)?((?:[\w]+(?:<[^>]*>)?\??\.)+)`?(\w+)", re.MULTILINE)
_COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)
_STRINGS = re.compile(r'"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"')
_IMPORT_LINE = re.compile(r"^(?:import|package)\s[^\n]*", re.MULTILINE)


class Ref(NamedTuple):
    user: str
    name: str
    declarer: str
    kind: str          # blocker | internal | module | lower-module
    user_target: str
    declarer_target: str


def area_of(target: str) -> str:
    return "host" if target == "host" else target.rsplit("-", 1)[0]


def package_of(text: str) -> str | None:
    match = _PACKAGE.search(text)
    return match.group(1) if match else None


def code_only(text: str) -> str:
    """Text without comments and string literals (strings first would eat `//` in URLs, comments first would eat `"/*"`;
    the pair is good enough for a heuristic)."""
    return _STRINGS.sub('""', _COMMENTS.sub("", text))


def declared_names(text: str) -> set[str]:
    code = code_only(text)
    return {m.group(1) for m in _DECL.finditer(code) if not _PRIVATE.match(code[m.start():m.start() + 200])}


def internal_names(text: str) -> set[str]:
    """Top-level `internal` declarations (a subset of declared_names)."""
    code = code_only(text)
    return {m.group(1) for m in _DECL.finditer(code) if re.search(r"\binternal\b", code[m.start():m.start(1)]) and not _PRIVATE.match(code[m.start():m.start() + 200])}


_CLASS_HEAD = re.compile(r"\b(?:(companion)\s+object\b(?:\s+`?(\w+))?|(?<!::)(?:class|interface|object)\s+`?(\w+))")  # not `Foo::class to x`
_CONSTRUCTOR_INTERNAL = re.compile(r"\binternal\s+(?:(?:override|open|final)\s+)*(?:val|var)\s+`?(\w+)")
_MEMBER_DECL = re.compile(r"(?:^|;)\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*((?:\w+\s+)*?)(?:fun|val|var|class|interface|object|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?`?(\w+)", re.MULTILINE)


def _header_end(code: str, start: int) -> tuple[int, bool]:
    """(end of the class header that starts at `start`, whether a body `{` opens there). The header may span lines
    (constructor parameters, supertypes): a line break ends it only outside parentheses, when the text before it does
    not end with `,`/`:` and the next line does not start with `:`, `,`, `{` or `where`."""
    depth = 0
    for index in range(start, len(code)):
        char = code[index]
        if char in "([":
            depth += 1
        elif char in ")]":
            depth -= 1
        elif depth == 0 and char == "{":
            return index, True
        elif depth == 0 and char in ";}=":
            return index, False
        elif depth == 0 and char == "\n":
            before = code[start:index].rstrip()
            after = code[index:].lstrip()
            if not (before.endswith((",", ":")) or after.startswith((":", ",", "{", "where "))):
                return index, False
    return len(code), False


def _body_close(code: str, open_index: int) -> int:
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] == "{":
            depth += 1
        elif code[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    return len(code)


def _top_level_of(body: str) -> str:
    """The body with every character inside a nested `{...}` blanked (line breaks kept): only its own declarations remain."""
    out: list[str] = []
    depth = 0
    for char in body:
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        out.append(char if depth == 0 or char == "\n" else " ")
    return "".join(out)


def internal_member_names(text: str) -> dict[str, set[str]]:
    """Class name -> the names of its `internal` members (functions, properties, nested types), for every class,
    interface or object of the file that has any. A nested class is keyed `Outer.Inner`; the members of a companion
    object belong to its class (`Cls.member`). Only the class body's own declarations count: locals inside functions and
    members of anonymous objects do not."""
    code = code_only(text)
    classes: list[tuple[int, int, int, str | None, bool, str]] = []  # (declared at, body open, body close, name, companion, header)
    for match in _CLASS_HEAD.finditer(code):
        end, has_body = _header_end(code, match.end())
        closing = _body_close(code, end) if has_body else end
        classes.append((match.start(), end, closing, match.group(3) or match.group(2), bool(match.group(1)), code[match.end():end]))
    members: dict[str, set[str]] = defaultdict(set)
    for at, opening, closing, name, companion, header in classes:
        outer = sorted(c for c in classes if c[1] < at < c[2] and c[1] != c[2])
        chain = [c[3] for c in outer if not c[4] and c[3]] + ([] if companion or not name else [name])
        if not chain:
            continue
        key = ".".join(chain)
        members[key].update(_CONSTRUCTOR_INTERNAL.findall(header))  # `class A(internal val x: Int)`
        if opening == closing:
            continue
        own = _top_level_of(code[opening + 1:closing])
        for decl in _MEMBER_DECL.finditer(own):
            if re.search(r"\binternal\b", decl.group(1)) and not re.search(r"\bprivate\b", decl.group(1)):
                members[key].add(decl.group(2))
    return {key: names for key, names in members.items() if names}


def extension_receivers(text: str) -> dict[str, set[str]]:
    """Names declared ONLY as extension functions -> the type names of their receivers (`A.B<T>?.f` gives {A, B}).
    A name that is also declared as a plain member of the file is not an extension-only name."""
    code = code_only(text)
    receivers: dict[str, set[str]] = defaultdict(set)
    for m in _EXTENSION.finditer(code):
        if _PRIVATE.match(code[m.start():m.start() + 200]):
            continue
        receivers[m.group(2)].update(re.findall(r"\w+", re.sub(r"<[^>]*>", "", m.group(1))))
    plain = {m.group(1) for m in _DECL.finditer(code)} - {m.group(2) for m in _EXTENSION.finditer(code)}
    return {name: recv for name, recv in receivers.items() if name not in plain}


def _is_reference(name: str, declarer: str, receivers: dict[str, dict[str, set[str]]], body: str, tokens: set[str]) -> bool:
    """Conservative: an extension-only name is NOT a reference only when another file of the package also declares it as an
    extension, the user names that other receiver, names none of this declarer's receivers and calls it with a receiver."""
    own = receivers[declarer].get(name)
    if own is None or own & tokens or re.search(rf"(?<![\w.]){re.escape(name)}\b", body):
        return True
    return not any(other != declarer and name in rec and rec[name] & tokens for other, rec in receivers.items())


def _kind(user_target: str, declarer_target: str) -> str | None:
    user, declarer = area_of(user_target), area_of(declarer_target)
    if user == declarer:
        return "module" if user_target != declarer_target else None
    if user == "host":
        return None
    return "blocker" if ORDER.index(declarer) > ORDER.index(user) else "lower-module"


def find_same_package_refs(files: dict[str, str], targets: dict[str, str]) -> list[Ref]:
    """files: path -> text; targets: path -> target module (`<area>-<tier>` or `host`). Files without a target are skipped."""
    by_package: dict[str, list[str]] = defaultdict(list)
    for path, text in files.items():
        package = package_of(text)
        if package is not None and path in targets:
            by_package[package].append(path)
    refs: list[Ref] = []
    for package, paths in sorted(by_package.items()):
        if len({targets[p] for p in paths}) < 2:
            continue
        declared = {p: declared_names(files[p]) for p in paths}
        internal = {p: internal_names(files[p]) for p in paths}
        receivers = {p: extension_receivers(files[p]) for p in paths}
        qualified = re.compile(re.escape(package) + r"\.\w+")
        for user in sorted(paths):
            body = qualified.sub("", _IMPORT_LINE.sub("", code_only(files[user])))
            tokens = set(re.findall(r"\w+", body))
            for declarer in sorted(paths):
                if declarer == user:
                    continue
                kind = _kind(targets[user], targets[declarer])
                crosses_module = targets[user] != targets[declarer]
                if kind is None and not crosses_module:
                    continue
                for name in sorted((declared[declarer] & tokens) - declared[user]):  # a name the user declares itself (a receiver overload) is its own
                    if not _is_reference(name, declarer, receivers, body, tokens):
                        continue
                    name_kind = "internal" if crosses_module and name in internal[declarer] else kind
                    if name_kind is not None:
                        refs.append(Ref(user, name, declarer, name_kind, targets[user], targets[declarer]))
    return refs


def render_report(refs: list[Ref]) -> str:
    kinds = ("blocker", "internal", "module", "lower-module")
    pairs: dict[tuple[str, str], list[int]] = defaultdict(lambda: [0, 0, 0, 0])
    for ref in refs:
        pairs[(area_of(ref.user_target), area_of(ref.declarer_target))][kinds.index(ref.kind)] += 1
    lines = ["# Same-package references across placement boundaries", "",
             "Generated by `scripts/structure/same_package_refs.py`; do not edit by hand. A same-package reference needs no import, "
             "so the import-based scans cannot see it. **Heuristic and deliberately over-inclusive**: a word-boundary match of a sibling's "
             "public top-level name in the other files of the same package (comments, strings, imports and qualified names removed); "
             "a generic name reused as a local identifier is reported too. `blocker` = the declaring file is in a higher area (or the host) "
             "than the using file; `internal` = an `internal` declaration whose user lands in a different Gradle module (blocking for the mover); "
             "`module` = same area, different module (needs a module dependency); `lower-module` = declaring file in a lower area and a "
             "different module (informational, the user's module needs that dependency).", "",
             f"{sum(1 for r in refs if r.kind == 'blocker')} blockers, {sum(1 for r in refs if r.kind == 'internal')} internal, "
             f"{sum(1 for r in refs if r.kind == 'module')} module references, {sum(1 for r in refs if r.kind == 'lower-module')} lower-module references.", "",
             "| using area | declaring area | blockers | internal | module refs | lower-module refs |", "|---|---|---|---|---|---|"]
    lines += [f"| {u} | {d} | {c[0]} | {c[1]} | {c[2]} | {c[3]} |" for (u, d), c in sorted(pairs.items())]
    lines += ["", "## References", "", "| kind | using file | name | declaring file |", "|---|---|---|---|"]
    lines += [f"| {r.kind} | `{r.user}` ({r.user_target}) | `{r.name}` | `{r.declarer}` ({r.declarer_target}) |" for r in sorted(refs, key=lambda r: (r.kind, r.user, r.name))]
    return "\n".join(lines) + "\n"


def _load(name: str):
    existing = sys.modules.get(name)
    if existing is not None:
        return existing
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/structure/{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def effective_targets(files: dict[str, str]) -> tuple[dict[str, str], list[str]]:
    """path (relative to the repo) -> target module for every non-test file of the manifest roots, plus override problems."""
    mm = _load("move_manifest")
    ur = mm.ur
    areas = ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
    overrides = ur.load_overrides((ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8"))
    generated = ur.load_file_overrides_file()
    placements = ur.load_placement_overrides_file()
    merged = ur.merge_placements(placements, generated)  # ValueError on conflicting rows
    problems = ur.validate_file_overrides(generated, files) + ur.validate_placement_overrides(placements, files)
    targets: dict[str, str] = {}
    for path, text in files.items():
        located = mm.package_of(path, text)
        if located is None or mm.is_test(located[1]):
            continue
        module, _, package = located
        rel = ur.rel_path(path)
        targets[path] = mm.file_target(module, rel, merged[rel], placements) if rel in merged else mm.target_for(module, package, areas, overrides)
    return targets, problems


def main(argv: list[str]) -> int:
    mm = _load("move_manifest")
    files = mm.collect_files()
    try:
        targets, problems = effective_targets(files)
    except ValueError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    if problems:
        sys.stderr.write("\n".join(problems) + "\n")
        return 1
    report = render_report(find_same_package_refs(files, targets))
    if "--out" in argv:
        (ROOT / argv[argv.index("--out") + 1]).write_text(report, encoding="utf-8")
    else:
        sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
