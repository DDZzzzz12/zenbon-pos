#!/usr/bin/env python3
"""Dependency license check and third-party notices for the shipped release artifacts.

Run by Gradle, not by hand:
    ./gradlew checkLicenses               -> `check`
    ./gradlew generateThirdPartyNotices   -> `notices`

Inputs
  * CycloneDX direct SBOMs of the shipped projects (`--bom SCOPES=path`, scopes comma-separated),
    which carry each resolved Maven component and the licenses its POM declares;
  * the Kotlin/wasm yarn lockfile plus the installed node_modules (`--npm-lock`, `--node-modules`),
    for the npm packages bundled into the browser apps;
  * artifact manifests (`--artifacts`, "group:name:version<TAB>path" lines) to lift NOTICE and
    LICENSE files out of the shipped jars;
  * the policy in config/licenses/ (allowlist, exceptions, mappings, bundled components, texts).

A component passes when one license it declares normalises to an allowlisted id; otherwise it needs
a documented exception whose scopes cover every artifact it ships in. Unknown or missing metadata
fails unless license-mappings.json resolves it. Exit status 1 on any violation or stale entry.
Standard library only, so it runs on any CI runner without a pip install.
"""

from __future__ import annotations

import argparse
import fnmatch
import glob
import hashlib
import json
import os
import re
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

SCOPES = ("server", "apk", "deb", "private-only")


# --------------------------------------------------------------------------------------------- policy


@dataclass
class License:
    id: str
    category: str
    name: str
    url: str
    aliases: list[re.Pattern]
    urls: list[re.Pattern]
    texts: list[str]


@dataclass
class Policy:
    licenses: list[License]
    first_party_groups: set[str]
    exceptions: list[dict]
    mappings: list[dict]
    bundled: dict
    config_dir: Path

    def by_id(self, license_id: str) -> License | None:
        return next((lic for lic in self.licenses if lic.id == license_id), None)

    def rank(self, license_id: str) -> int:
        return next(i for i, lic in enumerate(self.licenses) if lic.id == license_id)


def _compile(patterns: list[str]) -> list[re.Pattern]:
    return [re.compile(p, re.IGNORECASE) for p in patterns]


def load_policy(config_dir: Path) -> Policy:
    allowed = json.loads((config_dir / "allowed-licenses.json").read_text())
    licenses = [
        License(
            id=entry["id"],
            category=entry["category"],
            name=entry["name"],
            url=entry.get("url", ""),
            aliases=_compile([re.escape(entry["id"])] + entry.get("aliases", [])),
            urls=_compile(entry.get("urls", [])),
            texts=entry.get("texts", []),
        )
        for entry in allowed["licenses"]
    ]
    exceptions = json.loads((config_dir / "exceptions.json").read_text())["exceptions"]
    for exc in exceptions:
        missing = {"dependency", "license", "reason", "scope", "owner", "date"} - exc.keys()
        if missing:
            raise SystemExit(f"exceptions.json: entry {exc.get('dependency')} lacks {sorted(missing)}")
        unknown_scopes = set(exc["scope"]) - set(SCOPES)
        if unknown_scopes:
            raise SystemExit(f"exceptions.json: {exc['dependency']} has unknown scope {sorted(unknown_scopes)}")
    mappings = json.loads((config_dir / "license-mappings.json").read_text())["mappings"]
    for mapping in mappings:
        missing = {"dependency", "licenses", "reason", "evidence"} - mapping.keys()
        if missing:
            raise SystemExit(f"license-mappings.json: entry {mapping.get('dependency')} lacks {sorted(missing)}")
        if "version" in mapping and not isinstance(mapping["version"], str):
            raise SystemExit(f"license-mappings.json: entry {mapping['dependency']} has a non-string 'version'")
    bundled = json.loads((config_dir / "bundled-components.json").read_text())
    return Policy(
        licenses=licenses,
        first_party_groups=set(allowed.get("firstPartyGroups", [])),
        exceptions=exceptions,
        mappings=mappings,
        bundled=bundled,
        config_dir=config_dir,
    )


def normalise(policy: Policy, license_id: str | None, name: str | None, url: str | None) -> str | None:
    """Maps one declared license to an allowlisted id, or None when it is not (recognisably) allowed."""
    for candidate in (license_id, name):
        if candidate:
            candidate = candidate.strip()
            for lic in policy.licenses:
                if any(p.fullmatch(candidate) for p in lic.aliases):
                    return lic.id
    if url:
        url = url.strip()
        for lic in policy.licenses:
            if any(p.fullmatch(url) for p in lic.urls):
                return lic.id
    return None


# ------------------------------------------------------------------------------------------ components


@dataclass
class Component:
    key: str  # group:name for maven, npm:name for npm
    version: str
    ecosystem: str  # maven | npm
    declared: list[dict]  # [{"id"|"name"|"url"|"expression": ...}]
    scopes: set[str] = field(default_factory=set)
    purl: str = ""
    homepage: str = ""
    # results
    status: str = ""  # allowed | exception | violation
    license: str = ""  # chosen allowlisted id (allowed) or the exception's license text
    detail: str = ""
    via: str = ""  # "mapping" when license-mappings.json decided it

    @property
    def coordinate(self) -> str:
        return f"{self.key}:{self.version}" if self.ecosystem == "maven" else f"{self.key}@{self.version}"


def _split_expression(expression: str) -> list[list[str]]:
    """SPDX expression -> OR-list of AND-groups. Only the shapes POMs and package.json actually use."""
    expression = expression.strip().strip("()")
    return [
        [term.strip().strip("()") for term in re.split(r"\s+AND\s+", alt)]
        for alt in re.split(r"\s+OR\s+", expression)
    ]


def components_from_bom(bom: dict, scopes: set[str], policy: Policy) -> dict[str, Component]:
    found: dict[str, Component] = {}
    for comp in bom.get("components", []):
        group = comp.get("group") or ""
        if group in policy.first_party_groups:
            continue
        key = f"{group}:{comp['name']}"
        declared = []
        for entry in comp.get("licenses") or []:
            if "expression" in entry:
                declared.append({"expression": entry["expression"]})
            elif "license" in entry:
                lic = entry["license"]
                declared.append({k: lic[k] for k in ("id", "name", "url") if lic.get(k)})
        homepage = next(
            (r["url"] for r in comp.get("externalReferences", []) if r.get("type") in ("website", "vcs")),
            "",
        )
        ident = f"{key}:{comp.get('version')}"
        found[ident] = Component(
            key=key,
            version=comp.get("version") or "",
            ecosystem="maven",
            declared=declared,
            scopes=set(scopes),
            purl=comp.get("purl", ""),
            homepage=homepage,
        )
    return found


def parse_yarn_lock(text: str) -> list[tuple[str, str]]:
    """(name, version) for every package a yarn v1 lockfile resolves."""
    packages = []
    name = None
    for line in text.splitlines():
        if line and not line.startswith((" ", "#")) and line.rstrip().endswith(":"):
            first_spec = line.rstrip(":").split(",")[0].strip().strip('"')
            name = first_spec[: first_spec.rindex("@")] if first_spec.rindex("@") > 0 else first_spec
        elif name and line.strip().startswith("version "):
            packages.append((name, line.strip().split(" ", 1)[1].strip('"')))
            name = None
    return packages


def index_node_modules(node_modules: Path) -> dict[tuple[str, str], Path]:
    """(name, version) -> package directory, for every installed package under node_modules.

    Follows symlinks: yarn links Kotlin-generated packages into node_modules, and THEIR own
    node_modules is where a second version of a shared dependency gets installed.
    """
    index: dict[tuple[str, str], Path] = {}
    if not node_modules.is_dir():
        return index
    seen: set[str] = set()
    for dirpath, dirnames, filenames in os.walk(node_modules, followlinks=True):
        real = os.path.realpath(dirpath)
        if real in seen:
            dirnames[:] = []
            continue
        seen.add(real)
        here = Path(dirpath)
        parent = here.parent
        is_package_dir = parent.name == "node_modules" or (parent.name.startswith("@") and parent.parent.name == "node_modules")
        if not is_package_dir or "package.json" not in filenames:
            continue
        try:
            data = json.loads((here / "package.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(data, dict) and data.get("name") and data.get("version"):
            index.setdefault((data["name"], data["version"]), here)
    return index


def npm_declared(package_dir: Path | None) -> tuple[list[dict], str]:
    if package_dir is None:
        return [], ""
    data = json.loads((package_dir / "package.json").read_text(encoding="utf-8"))
    declared: list[dict] = []
    lic = data.get("license")
    if isinstance(lic, str):
        declared.append({"expression": lic})
    elif isinstance(lic, dict) and lic.get("type"):
        declared.append({"name": lic["type"], "url": lic.get("url", "")})
    for legacy in data.get("licenses") or []:
        if isinstance(legacy, dict) and legacy.get("type"):
            declared.append({"name": legacy["type"], "url": legacy.get("url", "")})
    homepage = data.get("homepage") or ""
    return declared, homepage


def components_from_npm(lock: Path, node_modules: Path, scopes: set[str]) -> dict[str, Component]:
    index = index_node_modules(node_modules)
    found: dict[str, Component] = {}
    for name, version in parse_yarn_lock(lock.read_text(encoding="utf-8")):
        package_dir = index.get((name, version))
        declared, homepage = npm_declared(package_dir)
        found[f"npm:{name}:{version}"] = Component(
            key=f"npm:{name}",
            version=version,
            ecosystem="npm",
            declared=declared,
            scopes=set(scopes),
            purl=f"pkg:npm/{name.replace('@', '%40')}@{version}",
            homepage=homepage,
            detail="" if package_dir else "not installed under node_modules",
        )
    return found


def merge(into: dict[str, Component], new: dict[str, Component]) -> None:
    for ident, comp in new.items():
        if ident in into:
            into[ident].scopes |= comp.scopes
        else:
            into[ident] = comp


# ------------------------------------------------------------------------------------------ evaluation


def _matches(pattern: str, key: str) -> bool:
    return fnmatch.fnmatchcase(key, pattern)


def _mapping_applies(mapping: dict, comp: Component) -> bool:
    """A mapping with a 'version' covers only that exact version: a bump is judged by its own metadata again."""
    return _matches(mapping["dependency"], comp.key) and mapping.get("version", comp.version) == comp.version


def _mapping_label(mapping: dict) -> str:
    return f"{mapping['dependency']}@{mapping['version']}" if "version" in mapping else mapping["dependency"]


def _choices(policy: Policy, declared: list[dict]) -> tuple[list[str], list[str]]:
    """Allowed ids a component may be used under, and the declared licenses that did not normalise."""
    allowed: list[str] = []
    unrecognised: list[str] = []
    for entry in declared:
        if "expression" in entry:
            for group in _split_expression(entry["expression"]):
                ids = [normalise(policy, term, term, None) for term in group]
                if all(ids):
                    # an AND-group is usable only if every part is allowed; record its strictest part
                    allowed.append(max(ids, key=policy.rank))
                else:
                    unrecognised.append(" AND ".join(group))
        else:
            normalised = normalise(policy, entry.get("id"), entry.get("name"), entry.get("url"))
            if normalised:
                allowed.append(normalised)
            else:
                unrecognised.append(entry.get("id") or entry.get("name") or entry.get("url") or "?")
    return allowed, unrecognised


def evaluate(policy: Policy, components: dict[str, Component]) -> dict[str, set[str]]:
    """Sets status/license/detail on every component. Returns the policy entries each matched."""
    used: dict[str, set[str]] = {"exceptions": set(), "mappings": set()}
    for comp in components.values():
        declared = comp.declared
        mapping = next((m for m in policy.mappings if _mapping_applies(m, comp)), None)
        if mapping:
            used["mappings"].add(_mapping_label(mapping))
            declared = [{"id": lic} for lic in mapping["licenses"]]
            comp.via = "mapping"
        allowed, unrecognised = _choices(policy, declared)
        if allowed:
            comp.status = "allowed"
            comp.license = min(allowed, key=policy.rank)
            continue
        exception = next((e for e in policy.exceptions if _matches(e["dependency"], comp.key)), None)
        if exception:
            used["exceptions"].add(exception["dependency"])
            outside = comp.scopes - set(exception["scope"])
            if not outside:
                comp.status = "exception"
                comp.license = "; ".join(unrecognised) or exception["license"]
                comp.detail = exception["reason"]
                continue
            comp.detail = f"exception covers {exception['scope']} but it also ships in {sorted(outside)}"
        comp.status = "violation"
        if not comp.detail:
            comp.detail = (
                "no license metadata" if not declared else "not on the allowlist: " + "; ".join(unrecognised)
            )
    return used


def stale_entries(policy: Policy, used: dict[str, set[str]]) -> list[str]:
    stale = [f"exceptions.json: {e['dependency']}" for e in policy.exceptions if e["dependency"] not in used["exceptions"]]
    stale += [f"license-mappings.json: {_mapping_label(m)}" for m in policy.mappings if _mapping_label(m) not in used["mappings"]]
    return stale


# ------------------------------------------------------------------------------------- bundled assets


def font_license_record(path: Path) -> str:
    """The OpenType/TrueType name-table license description (name ID 13), or '' when absent."""
    data = path.read_bytes()
    try:
        num_tables = struct.unpack(">H", data[4:6])[0]
        for i in range(num_tables):
            tag, _, offset, _ = struct.unpack(">4sIII", data[12 + 16 * i : 28 + 16 * i])
            if tag != b"name":
                continue
            _, count, string_offset = struct.unpack(">HHH", data[offset : offset + 6])
            for j in range(count):
                rec = data[offset + 6 + 12 * j : offset + 18 + 12 * j]
                platform, _, _, name_id, length, str_off = struct.unpack(">6H", rec)
                if name_id == 13:
                    raw = data[offset + string_offset + str_off : offset + string_offset + str_off + length]
                    return raw.decode("utf-16-be" if platform in (0, 3) else "latin-1", errors="replace")
    except struct.error:
        return ""
    return ""


def check_bundled(policy: Policy, root: Path) -> list[str]:
    """Problems with the declared non-dependency components (fonts, runtime, native binaries)."""
    problems = []
    covered: set[Path] = set()
    for comp in policy.bundled["components"]:
        lic = policy.by_id(comp["license"])
        if lic is None:
            problems.append(f"bundled {comp['name']}: license {comp['license']} is not on the allowlist")
        for pattern in comp.get("files", []):
            matches = [Path(p) for p in glob.glob(str(root / pattern))]
            if not matches:
                problems.append(f"bundled {comp['name']}: no file matches {pattern}")
            for path in matches:
                covered.add(path.resolve())
                if comp.get("verify") == "font-name-table" and "open font license" not in font_license_record(path).lower():
                    problems.append(f"bundled {comp['name']}: {path.relative_to(root)} does not declare the SIL Open Font License")
        if comp.get("verify") == "license-file":
            license_file = root / comp["licenseFile"]
            if not license_file.is_file() or "open font license" not in license_file.read_text(errors="replace").lower():
                problems.append(f"bundled {comp['name']}: {comp['licenseFile']} does not carry the SIL Open Font License")
    for pattern in policy.bundled.get("fontGlobs", []):
        for found in glob.glob(str(root / pattern), recursive=True):
            path = Path(found).resolve()
            if "/build/" in found or path in covered:
                continue
            problems.append(f"font {path.relative_to(root.resolve())} is not declared in bundled-components.json")
    return problems


# ------------------------------------------------------------------------------------------- reporting


def summary(components: dict[str, Component]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for comp in components.values():
        label = comp.license if comp.status == "allowed" else comp.status.upper()
        counts[label] = counts.get(label, 0) + 1
    return dict(sorted(counts.items(), key=lambda item: (-item[1], item[0])))


def report(components: dict[str, Component], stale: list[str], bundled_problems: list[str]) -> tuple[str, bool]:
    lines = [f"Shipped third-party components: {len(components)}"]
    for label, count in summary(components).items():
        lines.append(f"  {count:5d}  {label}")
    exceptions = sorted((c for c in components.values() if c.status == "exception"), key=lambda c: c.coordinate)
    if exceptions:
        lines.append("")
        lines.append(f"Documented exceptions in use ({len(exceptions)}):")
        lines += [f"  {c.coordinate}  [{', '.join(sorted(c.scopes))}]  {c.license}" for c in exceptions]
    violations = sorted((c for c in components.values() if c.status == "violation"), key=lambda c: c.coordinate)
    failed = bool(violations or stale or bundled_problems)
    if violations:
        lines.append("")
        lines.append(f"LICENSE VIOLATIONS ({len(violations)}):")
        lines += [f"  {c.coordinate}  [{', '.join(sorted(c.scopes))}]  {c.detail}" for c in violations]
    if stale:
        lines.append("")
        lines.append("STALE POLICY ENTRIES (match no shipped dependency; delete them):")
        lines += [f"  {s}" for s in stale]
    if bundled_problems:
        lines.append("")
        lines.append("BUNDLED ASSET PROBLEMS:")
        lines += [f"  {p}" for p in bundled_problems]
    if failed:
        lines.append("")
        lines.append(
            "Fix: drop or replace the dependency, or (only with the owner's decision) add a documented entry to "
            "config/licenses/exceptions.json or license-mappings.json. See docs/claude/deployment.md."
        )
    return "\n".join(lines) + "\n", failed


# --------------------------------------------------------------------------------------------- notices

NOTICE_NAME = re.compile(r"(^|/)(META-INF/)?(NOTICE|LICENSE|LICENCE|COPYING)([._-][^/]*)?$", re.IGNORECASE)


def _normalised_text(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().lower()


def read_artifact_manifests(manifests: list[tuple[set[str], Path]]) -> tuple[dict[str, list[Path]], dict[str, set[str]]]:
    """Artifact files per coordinate, and the scopes each coordinate's artifacts ship in."""
    files: dict[str, list[Path]] = {}
    scopes: dict[str, set[str]] = {}
    for manifest_scopes, manifest in manifests:
        for line in manifest.read_text(encoding="utf-8").splitlines():
            if "\t" in line:
                coordinate, path = line.split("\t", 1)
                files.setdefault(coordinate, []).append(Path(path))
                scopes.setdefault(coordinate, set()).update(manifest_scopes)
    return files, scopes


def legal_files(artifacts: list[Path]) -> list[tuple[str, str]]:
    """(entry name, text) of every NOTICE/LICENSE file at a jar's root or META-INF/ (not nested deeper)."""
    found = []
    for artifact in artifacts:
        if not artifact.is_file():
            continue
        try:
            with zipfile.ZipFile(artifact) as archive:
                for entry in archive.namelist():
                    if entry.endswith("/") or entry.count("/") > 1 or not NOTICE_NAME.search(entry):
                        continue
                    if entry.count("/") == 1 and not entry.startswith("META-INF/"):
                        continue
                    raw = archive.read(entry)
                    if len(raw) > 200_000 or b"\0" in raw[:1024]:
                        continue
                    found.append((entry, raw.decode("utf-8", errors="replace")))
        except (zipfile.BadZipFile, OSError):
            continue
    return found


def npm_legal_files(package_dir: Path | None) -> list[tuple[str, str]]:
    if package_dir is None:
        return []
    found = []
    for path in sorted(package_dir.iterdir()):
        if path.is_file() and NOTICE_NAME.search(path.name):
            found.append((path.name, path.read_text(encoding="utf-8", errors="replace")))
    return found


def write_notices(
    policy: Policy,
    components: dict[str, Component],
    legal: dict[str, list[tuple[str, str]]],
    product: str,
    out: Path,
) -> None:
    standard_texts = {}
    for lic in policy.licenses:
        for text_file in lic.texts:
            standard_texts[_normalised_text((policy.config_dir / "texts" / text_file).read_text())] = text_file

    rule = "=" * 100
    out_lines = [
        rule,
        f"{product} - THIRD-PARTY SOFTWARE NOTICES",
        rule,
        "",
        "This product includes third-party software. Each component is listed below with the license it is",
        "used under, the release artifacts it ships in (server = pos-server.jar including the /admin and /pos",
        "browser apps; apk = Android client; deb = Linux desktop client), the NOTICE and license files the",
        "component itself distributes, and the full text of every license named here.",
        "",
        "Generated by `./gradlew generateThirdPartyNotices` from the resolved dependency graphs; the",
        "machine-readable inventory is the CycloneDX SBOM published next to this file (sbom.json).",
        "",
    ]

    def component_block(comp: Component) -> list[str]:
        lic = policy.by_id(comp.license)
        block = [comp.coordinate]
        if lic:
            block.append(f"    License:   {lic.name} ({lic.id})" + (f"  {lic.url}" if lic.url else ""))
        else:
            block.append(f"    License:   {comp.license}  [documented exception: {comp.detail}]")
        offered = sorted({d.get("id") or d.get("name") or d.get("expression") or "" for d in comp.declared} - {""})
        if len(offered) > 1 or (offered and lic and offered[0] != lic.id):
            block.append(f"    Declared:  {' | '.join(offered)}")
        block.append(f"    Ships in:  {', '.join(sorted(comp.scopes))}")
        if comp.homepage:
            block.append(f"    Project:   {comp.homepage}")
        return block

    sections = [
        ("1. JAVA / KOTLIN / ANDROID LIBRARIES", [c for c in components.values() if c.ecosystem == "maven"]),
        ("2. NPM PACKAGES BUNDLED INTO THE BROWSER APPS", [c for c in components.values() if c.ecosystem == "npm"]),
    ]
    for title, members in sections:
        out_lines += [rule, title, rule, ""]
        for comp in sorted(members, key=lambda c: c.coordinate):
            out_lines += component_block(comp) + [""]

    out_lines += [rule, "3. FONTS, RUNTIME AND NATIVE BINARIES", rule, ""]
    for comp in policy.bundled["components"]:
        lic = policy.by_id(comp["license"])
        out_lines += [
            f"{comp['name']} {comp.get('version', '')}".rstrip(),
            f"    License:   {lic.name} ({lic.id})" + (f"  {lic.url}" if lic and lic.url else ""),
            f"    Copyright: {comp['copyright']}",
            f"    Ships in:  {', '.join(comp['scope'])}",
            f"    Project:   {comp['url']}",
            f"    Note:      {comp['note']}",
            "",
        ]

    # NOTICE and non-standard LICENSE files, deduplicated by content, attributed to every component carrying them.
    by_text: dict[str, tuple[str, str, list[str]]] = {}
    for coordinate, files in sorted(legal.items()):
        for entry, text in files:
            norm = _normalised_text(text)
            if not norm or norm in standard_texts:
                continue
            digest = hashlib.sha256(norm.encode()).hexdigest()
            kind = "NOTICE" if "notice" in entry.lower() else "LICENSE"
            by_text.setdefault(digest, (kind, text, []))[2].append(f"{coordinate} ({entry})")
    out_lines += [rule, "4. NOTICE AND LICENSE FILES DISTRIBUTED BY THE COMPONENTS", rule, ""]
    out_lines.append("Reproduced verbatim where they differ from the standard license texts in section 5.")
    out_lines.append("")
    for kind, text, owners in sorted(by_text.values(), key=lambda v: (v[0] != "NOTICE", v[2][0])):
        out_lines.append("-" * 100)
        out_lines.append(f"{kind} file of: " + ", ".join(sorted(set(owners))))
        out_lines.append("-" * 100)
        out_lines.append(text.rstrip())
        out_lines.append("")

    used_ids = {c.license for c in components.values() if c.status == "allowed"}
    used_ids |= {comp["license"] for comp in policy.bundled["components"]}
    out_lines += [rule, "5. LICENSE TEXTS", rule, ""]
    for lic in policy.licenses:
        if lic.id not in used_ids:
            continue
        out_lines.append("-" * 100)
        out_lines.append(f"{lic.name} ({lic.id})")
        out_lines.append("-" * 100)
        if not lic.texts:
            out_lines.append("Dedicated to the public domain by its authors; no license text applies.")
            out_lines.append("")
        for text_file in lic.texts:
            out_lines.append((policy.config_dir / "texts" / text_file).read_text().rstrip())
            out_lines.append("")
    exception_comps = [c for c in components.values() if c.status == "exception"]
    if exception_comps:
        out_lines += [rule, "6. COMPONENTS UNDER PROPRIETARY TERMS (documented exceptions)", rule, ""]
        for comp in sorted(exception_comps, key=lambda c: c.coordinate):
            out_lines.append(f"{comp.coordinate}  [{', '.join(sorted(comp.scopes))}]  {comp.license}")
        out_lines.append("")
        out_lines.append("These components are distributed under their publisher's own terms, not an open-source license.")
        out_lines.append("")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(out_lines), encoding="utf-8")


def write_sbom(aggregate: Path, components: dict[str, Component], policy: Policy, out: Path) -> None:
    """The aggregate CycloneDX SBOM plus the npm packages and bundled assets Gradle cannot see."""
    bom = json.loads(aggregate.read_text(encoding="utf-8"))
    extra = bom.setdefault("components", [])
    for comp in sorted((c for c in components.values() if c.ecosystem == "npm"), key=lambda c: c.coordinate):
        lic = policy.by_id(comp.license)
        extra.append(
            {
                "type": "library",
                "bom-ref": comp.purl,
                "name": comp.key.removeprefix("npm:"),
                "version": comp.version,
                "purl": comp.purl,
                "licenses": [{"license": {"name": lic.name, "url": lic.url}}] if lic else [],
            }
        )
    for comp in policy.bundled["components"]:
        lic = policy.by_id(comp["license"])
        extra.append(
            {
                "type": "file" if comp.get("files") else "platform",
                "bom-ref": "bundled:" + re.sub(r"[^a-z0-9]+", "-", comp["name"].lower()).strip("-"),
                "name": comp["name"],
                "version": comp.get("version", ""),
                "copyright": comp["copyright"],
                "licenses": [{"license": {"name": lic.name, "url": lic.url}}] if lic else [],
            }
        )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(bom, indent=2) + "\n", encoding="utf-8")


# -------------------------------------------------------------------------------------------- the CLI


def _bom_arg(value: str) -> tuple[set[str], Path]:
    scopes, _, path = value.partition("=")
    parsed = set(scopes.split(","))
    if not path or parsed - set(SCOPES):
        raise argparse.ArgumentTypeError(f"expected SCOPE[,SCOPE]=path with scopes from {SCOPES}, got {value}")
    return parsed, Path(path)


def collect(args: argparse.Namespace, policy: Policy, artifact_scopes: dict[str, set[str]]) -> dict[str, Component]:
    """All shipped components with the scopes they ship in.

    A project SBOM that covers several shipped configurations (composeApp: wasm till -> server,
    desktop -> deb) cannot say which configuration a component came from, so those scopes are taken
    from the per-configuration artifact manifests; the SBOM's own scopes are the fallback for a
    component that has no artifact (a platform or POM-only module).
    """
    components: dict[str, Component] = {}
    for scopes, path in args.bom:
        found = components_from_bom(json.loads(path.read_text(encoding="utf-8")), scopes, policy)
        if len(scopes) > 1:
            for ident, comp in found.items():
                precise = artifact_scopes.get(ident, set()) & scopes
                if precise:
                    comp.scopes = precise
        merge(components, found)
    if args.npm_lock:
        merge(components, components_from_npm(args.npm_lock, args.node_modules, set(args.npm_scope.split(","))))
    return components


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("check", "notices"))
    parser.add_argument("--root", type=Path, required=True, help="repository root")
    parser.add_argument("--config", type=Path, help="policy directory (default: <root>/config/licenses)")
    parser.add_argument("--bom", type=_bom_arg, action="append", default=[], help="SCOPES=path/to/bom.json")
    parser.add_argument("--npm-lock", type=Path)
    parser.add_argument("--node-modules", type=Path)
    parser.add_argument("--npm-scope", default="server")
    parser.add_argument("--artifacts", type=_bom_arg, action="append", default=[], help="SCOPES=artifact manifest")
    parser.add_argument("--aggregate-bom", type=Path, help="aggregate CycloneDX SBOM (notices)")
    parser.add_argument("--product", default="Zenbon POS")
    parser.add_argument("--report", type=Path, help="also write the check report here")
    parser.add_argument("--out-dir", type=Path, help="notices output directory")
    args = parser.parse_args(argv)

    policy = load_policy(args.config or args.root / "config" / "licenses")
    artifacts, artifact_scopes = read_artifact_manifests(args.artifacts)
    components = collect(args, policy, artifact_scopes)
    used = evaluate(policy, components)
    text, failed = report(components, stale_entries(policy, used), check_bundled(policy, args.root))
    sys.stdout.write(text)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    if failed:
        return 1
    if args.command == "notices":
        if not args.out_dir or not args.aggregate_bom:
            parser.error("notices needs --out-dir and --aggregate-bom")
        legal = {coordinate: legal_files(paths) for coordinate, paths in artifacts.items()}
        if args.npm_lock:
            index = index_node_modules(args.node_modules)
            for comp in components.values():
                if comp.ecosystem == "npm":
                    legal[comp.coordinate] = npm_legal_files(index.get((comp.key.removeprefix("npm:"), comp.version)))
        shipped = {c.coordinate for c in components.values()}
        legal = {k: v for k, v in legal.items() if k in shipped}
        write_notices(policy, components, legal, args.product, args.out_dir / "THIRD-PARTY-LICENSES.txt")
        write_sbom(args.aggregate_bom, components, policy, args.out_dir / "sbom.json")
        print(f"Wrote {args.out_dir / 'THIRD-PARTY-LICENSES.txt'} and {args.out_dir / 'sbom.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
