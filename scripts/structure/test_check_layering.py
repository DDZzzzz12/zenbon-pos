import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = ROOT / "scripts/structure/check_layering.py"
_spec = importlib.util.spec_from_file_location("check_layering", _SCRIPT)
assert _spec is not None and _spec.loader is not None
cl = importlib.util.module_from_spec(_spec)
sys.modules["check_layering"] = cl
_spec.loader.exec_module(cl)


def mod(area: str, tier: str) -> "cl.Module":
    return cl.Module(area=area, tier=tier)


class LayeringTest(unittest.TestCase):
    def test_accessor_of_kebab_names(self) -> None:
        self.assertEqual(cl.accessor_of(":ui-shared"), "uiShared")
        self.assertEqual(cl.accessor_of(":pos-server"), "posServer")

    def test_parse_settings_projectdir_override(self) -> None:
        text = 'include(":a")\ninclude(":b-c")\nproject(":b-c").projectDir = file("x/y")\n'
        self.assertEqual(cl.parse_settings(text), {":a": "a", ":b-c": "x/y"})

    def test_module_deps_reads_accessors_and_project_calls(self) -> None:
        known = [":ui-shared", ":platform-money", ":pos-tse", ":ui-till"]
        text = """
dependencies {
    implementation(projects.uiShared)
    api(project(":platform-money"))
    testImplementation(projects.platformMoney)
}
kotlin { sourceSets { commonMain.dependencies { implementation(projects.uiShared) } } }
val w = project(":pos-tse").file("lib/x.jar")
from(project(":ui-till").layout.buildDirectory)
"""
        self.assertEqual(cl.module_deps(text, known), {":ui-shared", ":platform-money"})

    def test_module_deps_source_set_prefixed_and_multiline(self) -> None:
        known = [":ui-shared", ":platform-money", ":ui-till"]
        text = """
commonMainApi(projects.platformMoney)
jvmMainImplementation(project(":ui-shared"))
androidMainCompileOnly(
    projects.platformMoney
)
implementation(
    projects.uiShared,
)
from(project(":ui-till").layout.buildDirectory)
"""
        self.assertEqual(cl.module_deps(text, known), {":platform-money", ":ui-shared"})

    def test_r2_shell_may_depend_on_app_but_app_may_not(self) -> None:
        mods = {":s": mod("ui", "shell"), ":a": mod("ui", "app"), ":a2": mod("ui", "app")}
        self.assertEqual(cl.violations(mods, {":s": {":a"}}), [])
        self.assertEqual(len(cl.violations(mods, {":a2": {":a"}})), 1)

    def test_r1_flags_lower_area_depending_upward(self) -> None:
        mods = {":a": mod("platform", "kernel"), ":b": mod("accounting", "data")}
        out = cl.violations(mods, {":a": {":b"}, ":b": set()})
        self.assertEqual(len(out), 1)
        self.assertTrue(out[0].startswith(":a -> :b: "))

    def test_r2_flags_ui_depending_on_data(self) -> None:
        mods = {":u": mod("ui", "app"), ":d": mod("platform", "data")}
        self.assertEqual(len(cl.violations(mods, {":u": {":d"}})), 1)

    def test_r2_ui_may_depend_on_api_tier(self) -> None:
        # Spec §5.3: "ui → platform-api and any area api".
        mods = {":u": mod("ui", "app"), ":p": mod("platform", "api"), ":e": mod("ecr", "api")}
        self.assertEqual(cl.violations(mods, {":u": {":p", ":e"}}), [])

    def test_r2_ui_may_not_depend_on_data_tier(self) -> None:
        mods = {":u": mod("ui", "app"), ":p": mod("platform", "data")}
        out = cl.violations(mods, {":u": {":p"}})
        self.assertEqual(len(out), 1)
        self.assertIn("R2", out[0])

    def test_r3_flags_feature_to_feature(self) -> None:
        mods = {":a": mod("ecr", "feature"), ":b": mod("ecr", "feature")}
        self.assertEqual(len(cl.violations(mods, {":a": {":b"}})), 1)

    def test_r4_flags_unlisted_module(self) -> None:
        mods = {":a": mod("ecr", "feature")}
        out = cl.registry_violations(mods, [":a", ":new"])
        self.assertEqual(len(out), 1)
        self.assertIn(":new", out[0])
        out = cl.registry_violations(mods, [])
        self.assertEqual(len(out), 1)

    def test_host_is_exempt_as_source(self) -> None:
        mods = {":h": mod("host", "host"), ":d": mod("accounting", "feature")}
        self.assertEqual(cl.violations(mods, {":h": {":d"}}), [])

    def test_module_deps_named_path_and_configuration_forms(self) -> None:
        known = [":platform-money", ":shared", ":ui-shared"]
        text = """
implementation(project(path = ":platform-money"))
api(project(":shared", configuration = "default"))
testImplementation(project(path = ":ui-shared", configuration = "x"))
"""
        self.assertEqual(cl.module_deps(text, known), {":platform-money", ":shared", ":ui-shared"})

    def test_module_deps_add_and_string_configuration_forms(self) -> None:
        known = [":platform-money", ":shared", ":ui-shared"]
        text = """
add("implementation", project(":platform-money"))
add("commonMainApi", project(path = ":shared"))
"implementation"(project(":ui-shared"))
"""
        self.assertEqual(cl.module_deps(text, known), {":platform-money", ":shared", ":ui-shared"})

    def test_module_deps_unresolvable_project_in_declaration_fails_loudly(self) -> None:
        known = [":platform-money"]
        with self.assertRaises(cl.LayeringError) as ctx:
            cl.module_deps('\n\nimplementation(project(":nope"))\n', known)
        self.assertIn("line 3", str(ctx.exception))
        with self.assertRaises(cl.LayeringError):
            cl.module_deps("implementation(project(somePathVariable))", known)
        with self.assertRaises(cl.LayeringError):
            cl.module_deps('add("api", project(":nope"))', known)

    def test_module_deps_project_outside_declarations_still_ignored(self) -> None:
        known = [":platform-money"]
        text = 'from(project(":nope").layout.buildDirectory)\nval f = project(":nope2").file("a")\n'
        self.assertEqual(cl.module_deps(text, known), set())

    def test_r5_non_ui_must_not_depend_on_ui(self) -> None:
        mods = {":f": mod("ecr", "feature"), ":u": mod("ui", "client-lib"), ":p": mod("platform", "data")}
        out = cl.violations(mods, {":f": {":u"}, ":p": {":u"}})
        self.assertEqual(
            out, [":f -> :u: R5 non-ui depends on ui", ":p -> :u: R5 non-ui depends on ui"]
        )

    def test_r5_ui_and_host_sources_are_not_flagged(self) -> None:
        mods = {":h": mod("host", "host"), ":u": mod("ui", "app"), ":u2": mod("ui", "client-lib")}
        self.assertEqual(cl.violations(mods, {":h": {":u"}, ":u": {":u2"}}), [])

    def test_main_exits_nonzero_naming_file_on_unresolvable_project(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "config/structure").mkdir(parents=True)
            (root / "config/structure/modules.tsv").write_text(":a\tplatform\tdata\n")
            (root / "config/structure/layering-baseline.txt").write_text("# none\n")
            (root / "settings.gradle.kts").write_text('include(":a")\n')
            (root / "a").mkdir()
            (root / "a/build.gradle.kts").write_text('\nimplementation(project(":ghost"))\n')
            old = cl.ROOT
            cl.ROOT = root
            try:
                self.assertNotEqual(cl.main([]), 0)
            finally:
                cl.ROOT = old

    def test_current_repo_has_no_unbaselined_violations(self) -> None:
        proc = subprocess.run(
            [sys.executable, "-I", str(ROOT / "scripts/structure/check_layering.py")],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


class R6TierTest(unittest.TestCase):
    """R6 fixtures use area modules that do not exist yet (<area>-api/-data/-server)."""

    def _mods(self) -> dict:
        m = {}
        for area in ("platform", "ecr", "accounting"):
            m[f":{area}-api"] = mod(area, "api")
            m[f":{area}-data"] = mod(area, "data")
            m[f":{area}-server"] = mod(area, "server")
        m[":host"] = mod("host", "host")
        return m

    def test_r6_data_may_not_depend_on_server(self) -> None:
        out = cl.violations(self._mods(), {":ecr-data": {":ecr-server"}})
        self.assertEqual(out, [":ecr-data -> :ecr-server: R6 tier order data depends on server"])

    def test_r6_api_may_not_depend_on_data(self) -> None:
        out = cl.violations(self._mods(), {":ecr-api": {":ecr-data"}})
        self.assertEqual(out, [":ecr-api -> :ecr-data: R6 tier order api depends on data"])

    def test_r6_same_area_downward_allowed(self) -> None:
        deps = {":ecr-server": {":ecr-data", ":ecr-api"}, ":ecr-data": {":ecr-api"}}
        self.assertEqual(cl.violations(self._mods(), deps), [])

    def test_r6_cross_area_server_dependency_flagged(self) -> None:
        out = cl.violations(self._mods(), {":accounting-server": {":ecr-server"}})
        self.assertEqual(out, [":accounting-server -> :ecr-server: R6 cross-area server dependency"])

    def test_r6_cross_area_server_dependency_on_platform_allowed(self) -> None:
        self.assertEqual(cl.violations(self._mods(), {":ecr-server": {":platform-server"}}), [])

    def test_r6_cross_area_server_dependency_on_other_area_still_flagged(self) -> None:
        out = cl.violations(self._mods(), {":accounting-server": {":ecr-server"}})
        self.assertEqual(out, [":accounting-server -> :ecr-server: R6 cross-area server dependency"])

    def test_r6_platform_server_may_not_depend_on_higher_area(self) -> None:
        out = cl.violations(self._mods(), {":platform-server": {":ecr-data"}})
        self.assertEqual(out, [":platform-server -> :ecr-data: R1 area platform depends on higher area ecr"])

    def test_r6_cross_area_data_dependency_allowed_downward(self) -> None:
        deps = {":accounting-data": {":ecr-data"}, ":accounting-server": {":ecr-api", ":platform-data"}}
        self.assertEqual(cl.violations(self._mods(), deps), [])

    def test_r6_feature_tier_counts_as_server_and_contract_as_api(self) -> None:
        mods = {":f": mod("accounting", "feature"), ":s": mod("ecr", "feature"), ":c": mod("platform", "contract")}
        self.assertEqual(cl.violations(mods, {":f": {":c"}}), [])
        self.assertIn("R6 cross-area server dependency", " ".join(cl.violations(mods, {":f": {":s"}})))

    def test_r6_host_exempt(self) -> None:
        deps = {":host": {":ecr-server", ":accounting-server", ":ecr-data"}}
        self.assertEqual(cl.violations(self._mods(), deps), [])

    def test_r6_existing_modules_unchanged(self) -> None:
        _, found = cl.collect(ROOT)
        self.assertEqual([v for v in found if "R6" in v], [])
        baseline = (ROOT / "config/structure/layering-baseline.txt").read_text().splitlines()
        self.assertEqual([l for l in baseline if l.strip() and not l.startswith("#")], [])


class R7ApiTest(unittest.TestCase):
    """R7 fixtures use area modules that do not exist yet."""

    def _mods(self) -> dict:
        m = {":platform-money": mod("platform", "kernel")}
        for area in ("ecr", "accounting"):
            m[f":{area}-api"] = mod(area, "api")
            m[f":{area}-data"] = mod(area, "data")
        return m

    def test_r7_api_may_not_depend_on_lower_area_data(self) -> None:
        out = cl.violations(self._mods(), {":accounting-api": {":ecr-data"}})
        self.assertEqual(out, [":accounting-api -> :ecr-data: R7 api depends on non-api"])

    def test_r7_api_may_depend_on_lower_area_api(self) -> None:
        self.assertEqual(cl.violations(self._mods(), {":accounting-api": {":ecr-api"}}), [])

    def test_r7_api_may_depend_on_kernel(self) -> None:
        self.assertEqual(cl.violations(self._mods(), {":accounting-api": {":platform-money"}}), [])

    def test_r7_data_may_depend_on_lower_area_data_still_allowed(self) -> None:
        self.assertEqual(cl.violations(self._mods(), {":accounting-data": {":ecr-data"}}), [])

    def test_r7_contract_tier_is_treated_as_api(self) -> None:
        mods = {":c": mod("platform", "contract"), ":d": mod("platform", "data")}
        self.assertEqual(cl.violations(mods, {":c": {":d"}}),
                         [":c -> :d: R6 tier order contract depends on data"])

    def test_r7_edge_already_reported_by_r6_yields_exactly_one_line(self) -> None:
        # same-area api -> data is R6's tier-order case; R7 stays silent so the edge is reported once
        out = cl.violations(self._mods(), {":ecr-api": {":ecr-data"}})
        self.assertEqual(out, [":ecr-api -> :ecr-data: R6 tier order api depends on data"])

    def test_r7_host_exempt(self) -> None:
        mods = {**self._mods(), ":host": mod("host", "host")}
        self.assertEqual(cl.violations(mods, {":host": {":ecr-data"}}), [])

    def test_r7_existing_modules_unchanged(self) -> None:
        _, found = cl.collect(ROOT)
        self.assertEqual([v for v in found if "R7" in v], [])
        baseline = (ROOT / "config/structure/layering-baseline.txt").read_text().splitlines()
        self.assertEqual([l for l in baseline if l.strip() and not l.startswith("#")], [])


if __name__ == "__main__":
    unittest.main()
