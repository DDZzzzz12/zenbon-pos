"""Offline tests for third_party.py (the checkLicenses / generateThirdPartyNotices policy engine).

Run: python3 -m unittest discover scripts/licenses/tests
They load the REAL policy in config/licenses/ wherever the rule under test is the policy itself.
"""

from __future__ import annotations

import dataclasses
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import third_party as tp  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
POLICY = tp.load_policy(REPO / "config" / "licenses")


def comp(key: str, declared: list[dict], scopes: set[str] | None = None, version: str = "1.0") -> tp.Component:
    return tp.Component(key=key, version=version, ecosystem="maven", declared=declared, scopes=scopes or {"server"})


def npm_comp(key: str, declared: list[dict], version: str) -> tp.Component:
    return tp.Component(key=key, version=version, ecosystem="npm", declared=declared, scopes={"server"})


def run(*components: tp.Component, policy: tp.Policy = POLICY) -> dict[str, tp.Component]:
    found = {c.coordinate: c for c in components}
    tp.evaluate(policy, found)
    return found


class NormaliseTest(unittest.TestCase):
    def test_pom_spellings_map_to_spdx(self):
        cases = {
            "The Apache Software License, Version 2.0": "Apache-2.0",
            "Apache License, Version 2.0": "Apache-2.0",
            "The (New) BSD License": "BSD-3-Clause",
            "Mozilla Public License Version 2.0": "MPL-2.0",
            "Public Domain": "Public-Domain",
            "GPL2 w/ CPE": "GPL-2.0-with-classpath-exception",
            "Eclipse Public License - v 2.0": "EPL-2.0",
        }
        for name, expected in cases.items():
            self.assertEqual(tp.normalise(POLICY, None, name, None), expected, name)

    def test_url_is_the_fallback(self):
        self.assertEqual(tp.normalise(POLICY, None, "Some odd name", "https://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0")

    def test_bare_gpl_and_proprietary_terms_are_not_allowed(self):
        for name in ("GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "AGPL-3.0-only", "SSPL-1.0",
                     "Android Software Development Kit License", "ML Kit Terms of Service"):
            self.assertIsNone(tp.normalise(POLICY, name, name, None), name)


class EvaluateTest(unittest.TestCase):
    def test_allowed_license_passes(self):
        c = run(comp("a:b", [{"id": "MIT"}]))["a:b:1.0"]
        self.assertEqual((c.status, c.license), ("allowed", "MIT"))

    def test_choice_prefers_permissive_over_gpl_option(self):
        # bytedeco: "Apache-2.0 | GPL-2.0-or-later | GPL-2.0-with-classpath-exception"
        c = run(comp("org.bytedeco:ffmpeg", [{"id": "Apache-2.0"}, {"id": "GPL-2.0-or-later"}]))["org.bytedeco:ffmpeg:1.0"]
        self.assertEqual(c.license, "Apache-2.0")

    def test_choice_prefers_epl_over_lgpl(self):
        c = run(comp("ch.qos.logback:logback-core", [{"id": "LGPL-2.1-only"}, {"id": "EPL-2.0"}]))
        self.assertEqual(c["ch.qos.logback:logback-core:1.0"].license, "EPL-2.0")

    def test_gpl_only_component_is_a_violation(self):
        c = run(comp("evil:gpl", [{"id": "GPL-3.0-only"}]))["evil:gpl:1.0"]
        self.assertEqual(c.status, "violation")
        self.assertIn("GPL-3.0-only", c.detail)

    def test_missing_metadata_is_a_violation(self):
        c = run(comp("no:meta", []))["no:meta:1.0"]
        self.assertEqual((c.status, c.detail), ("violation", "no license metadata"))

    def test_spdx_expression_or_and(self):
        either = run(comp("x:or", [{"expression": "GPL-3.0-only OR MIT"}]))["x:or:1.0"]
        self.assertEqual(either.license, "MIT")
        both = run(comp("x:and", [{"expression": "GPL-3.0-only AND MIT"}]))["x:and:1.0"]
        self.assertEqual(both.status, "violation")

    def test_exception_applies_only_inside_its_scope(self):
        terms = [{"name": "ML Kit Terms of Service"}]
        inside = run(comp("com.google.mlkit:common", terms, {"apk"}))["com.google.mlkit:common:1.0"]
        self.assertEqual(inside.status, "exception")
        outside = run(comp("com.google.mlkit:common", terms, {"apk", "server"}))["com.google.mlkit:common:1.0"]
        self.assertEqual(outside.status, "violation")
        self.assertIn("server", outside.detail)

    def test_mapping_replaces_missing_metadata(self):
        c = run(comp("com.sun.mail:mailapi", []))["com.sun.mail:mailapi:1.0"]
        self.assertEqual((c.status, c.via), ("allowed", "mapping"))

    def test_version_pinned_mapping_applies_only_to_its_version(self):
        pinned = {"dependency": "npm:pinned", "version": "1.0.0", "licenses": ["MIT"], "reason": "r", "evidence": "e"}
        policy = dataclasses.replace(POLICY, mappings=[pinned])
        same = run(npm_comp("npm:pinned", [], "1.0.0"), policy=policy)["npm:pinned@1.0.0"]
        self.assertEqual((same.status, same.via), ("allowed", "mapping"))
        # a bump (possibly relicensed) is judged by its own metadata, never by the old evidence
        bumped = run(npm_comp("npm:pinned", [{"id": "GPL-3.0-only"}], "2.0.0"), policy=policy)["npm:pinned@2.0.0"]
        self.assertEqual((bumped.status, bumped.via), ("violation", ""))

    def test_version_pinned_mapping_is_stale_once_its_version_is_gone(self):
        pinned = {"dependency": "npm:pinned", "version": "1.0.0", "licenses": ["MIT"], "reason": "r", "evidence": "e"}
        policy = dataclasses.replace(POLICY, mappings=[pinned])
        found = {"npm:pinned@2.0.0": npm_comp("npm:pinned", [{"id": "MIT"}], "2.0.0")}
        stale = tp.stale_entries(policy, tp.evaluate(policy, found))
        mapping_stale = [s for s in stale if s.startswith("license-mappings.json")]
        self.assertEqual(mapping_stale, ["license-mappings.json: npm:pinned@1.0.0"])

    def test_napi_canvas_mapping_names_each_platform_at_its_version(self):
        # the real policy: the eleven uninstalled-on-some-OS platform packages of @napi-rs/canvas 1.0.13
        mapped = run(npm_comp("npm:@napi-rs/canvas-win32-x64-msvc", [], "1.0.13"))
        self.assertEqual(mapped["npm:@napi-rs/canvas-win32-x64-msvc@1.0.13"].status, "allowed")
        bumped = run(npm_comp("npm:@napi-rs/canvas-win32-x64-msvc", [], "1.0.14"))
        self.assertEqual(bumped["npm:@napi-rs/canvas-win32-x64-msvc@1.0.14"].status, "violation")
        new_platform = run(npm_comp("npm:@napi-rs/canvas-freebsd-x64", [], "1.0.13"))
        self.assertEqual(new_platform["npm:@napi-rs/canvas-freebsd-x64@1.0.13"].status, "violation")

    def test_unused_policy_entries_are_stale(self):
        found = {"a:b:1.0": comp("a:b", [{"id": "MIT"}])}
        used = tp.evaluate(POLICY, found)
        stale = tp.stale_entries(POLICY, used)
        self.assertIn("exceptions.json: com.google.mlkit:*", stale)
        self.assertIn("license-mappings.json: com.sun.mail:mailapi", stale)

    def test_first_party_groups_are_skipped(self):
        bom = {"components": [
            {"group": "POS", "name": "shared", "version": "unspecified"},
            {"group": "org.example.project", "name": "pos-tse", "version": "1.0.0"},
            {"group": "io.ktor", "name": "ktor-io", "version": "3", "licenses": [{"license": {"id": "Apache-2.0"}}]},
        ]}
        self.assertEqual(list(tp.components_from_bom(bom, {"server"}, POLICY)), ["io.ktor:ktor-io:3"])


class NpmTest(unittest.TestCase):
    LOCK = '''# yarn lockfile v1


"@js-joda/core@3.2.0":
  version "3.2.0"
  resolved "https://registry.yarnpkg.com/@js-joda/core/-/core-3.2.0.tgz"

ws@8.18.0, ws@^8.0.0:
  version "8.18.0"

ws@8.20.1:
  version "8.20.1"
'''

    def test_yarn_lock_parsing(self):
        self.assertEqual(
            tp.parse_yarn_lock(self.LOCK),
            [("@js-joda/core", "3.2.0"), ("ws", "8.18.0"), ("ws", "8.20.1")],
        )

    def test_nested_version_behind_a_symlink_is_found(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nm = root / "node_modules"
            for directory, name, version, lic in (
                (nm / "ws", "ws", "8.20.1", "MIT"),
                (nm / "@js-joda" / "core", "@js-joda/core", "3.2.0", "BSD-3-Clause"),
                (root / "packages" / "lib" / "node_modules" / "ws", "ws", "8.18.0", "MIT"),
            ):
                directory.mkdir(parents=True)
                (directory / "package.json").write_text(json.dumps({"name": name, "version": version, "license": lic}))
            (root / "packages" / "lib" / "package.json").write_text(json.dumps({"name": "lib", "version": "1"}))
            os.symlink(root / "packages" / "lib", nm / "lib")
            lock = root / "yarn.lock"
            lock.write_text(self.LOCK)
            found = tp.components_from_npm(lock, nm, {"server"})
            tp.evaluate(POLICY, found)
            self.assertEqual({c.coordinate: c.license for c in found.values()},
                             {"npm:@js-joda/core@3.2.0": "BSD-3-Clause", "npm:ws@8.18.0": "MIT", "npm:ws@8.20.1": "MIT"})


class ScopeTest(unittest.TestCase):
    def test_multi_scope_bom_is_narrowed_by_the_artifact_manifests(self):
        with tempfile.TemporaryDirectory() as tmp:
            bom = Path(tmp) / "bom.json"
            bom.write_text(json.dumps({"components": [
                {"group": "org.bytedeco", "name": "ffmpeg", "version": "7", "licenses": [{"license": {"id": "Apache-2.0"}}]},
                {"group": "org.jetbrains", "name": "platform", "version": "1", "licenses": [{"license": {"id": "Apache-2.0"}}]},
            ]}))
            desktop = Path(tmp) / "desktop.tsv"
            desktop.write_text("org.bytedeco:ffmpeg:7\t/x/ffmpeg.jar\n")
            args = tp.argparse.Namespace(bom=[({"server", "deb"}, bom)], npm_lock=None)
            _, scopes = tp.read_artifact_manifests([({"deb"}, desktop)])
            found = tp.collect(args, POLICY, scopes)
            self.assertEqual(found["org.bytedeco:ffmpeg:7"].scopes, {"deb"})
            self.assertEqual(found["org.jetbrains:platform:1"].scopes, {"server", "deb"})


class LegalFilesTest(unittest.TestCase):
    def test_root_and_meta_inf_notices_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "a.jar"
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("META-INF/NOTICE.txt", "Apache Foo\nCopyright 2020 Foo")
                z.writestr("LICENSE-junit.txt", "Eclipse Public License")
                z.writestr("org/foo/LICENSE", "deep, not a distribution notice")
                z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0")
            names = sorted(name for name, _ in tp.legal_files([jar]))
            self.assertEqual(names, ["LICENSE-junit.txt", "META-INF/NOTICE.txt"])


class BundledAssetsTest(unittest.TestCase):
    def test_real_repository_fonts_are_declared_and_ofl(self):
        self.assertEqual(tp.check_bundled(POLICY, REPO), [])

    def test_an_undeclared_font_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            font = root / "newmodule" / "src" / "main" / "resources" / "x.ttf"
            font.parent.mkdir(parents=True)
            font.write_bytes(b"\0\1\0\0")
            policy = tp.Policy(POLICY.licenses, POLICY.first_party_groups, [], [],
                               {"fontGlobs": POLICY.bundled["fontGlobs"], "components": []}, POLICY.config_dir)
            problems = tp.check_bundled(policy, root)
            self.assertEqual(len(problems), 1)
            self.assertIn("not declared", problems[0])


class PolicyFilesTest(unittest.TestCase):
    def test_every_allowed_license_has_its_texts(self):
        for lic in POLICY.licenses:
            for text in lic.texts:
                self.assertTrue((POLICY.config_dir / "texts" / text).is_file(), f"{lic.id}: {text}")

    def test_exceptions_carry_reason_owner_date_scope(self):
        for exc in POLICY.exceptions:
            self.assertTrue(exc["reason"] and exc["owner"] and exc["date"] and exc["scope"], exc["dependency"])
            self.assertTrue(set(exc["scope"]) <= set(tp.SCOPES), exc["dependency"])

    def test_policy_never_allows_a_bare_gpl(self):
        ids = {lic.id for lic in POLICY.licenses}
        self.assertFalse(ids & {"GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "AGPL-3.0-only", "SSPL-1.0"})


if __name__ == "__main__":
    unittest.main()
