import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("move_area", ROOT / "scripts/structure/move_area.py")
assert _spec is not None and _spec.loader is not None
ma = importlib.util.module_from_spec(_spec)
sys.modules["move_area"] = ma
_spec.loader.exec_module(ma)

DIRS = ma.load_module_dirs(
    ":platform-api\tplatform/api\tshared\tcommonMain,commonTest,jvmMain,jvmTest,androidMain,wasmJsMain\n"
    ":platform-data\tplatform/data\tcore-data\tmain,test\n"
    ":platform-server\tplatform/server\tpos-server\tmain,test\n")
K = "kotlin/org/example/project"
SHARED_UTIL = f"shared/src/commonMain/{K}/core/utils/Format.kt"
CORE_DATA_TABLE = f"core-data/src/main/{K}/core/database/tables/ShopTable.kt"
SERVER_UTIL = f"pos-server/src/main/{K}/core/utils/Clock.kt"
SERVER_UTIL_TEST = f"pos-server/src/test/{K}/core/utils/ClockTest.kt"


def pkg(name: str) -> str:
    return f"package org.example.project.{name}\n"


class ModuleDirsTest(unittest.TestCase):
    def test_module_dirs_file_parses(self):
        dirs = ma.load_module_dirs((ROOT / "config/structure/module-dirs.tsv").read_text(encoding="utf-8"))
        self.assertEqual(dirs[":platform-server"].directory, "platform/server")
        self.assertEqual(dirs[":platform-api"].sets[0], "commonMain")

    def test_ecr_module_dirs_rows_load(self):
        dirs = ma.load_module_dirs((ROOT / "config/structure/module-dirs.tsv").read_text(encoding="utf-8"))
        self.assertEqual(dirs[":ecr-api"], ma.ModuleDir(":ecr-api", "ecr/api", "shared",
                                                        ("commonMain", "commonTest", "jvmMain", "jvmTest", "androidMain", "wasmJsMain")))
        self.assertEqual(dirs[":ecr-data"], ma.ModuleDir(":ecr-data", "ecr/data", "core-data", ("main", "test")))
        self.assertEqual(dirs[":ecr-server"], ma.ModuleDir(":ecr-server", "ecr/server", "pos-server", ("main", "test")))
        # the ecr target graph is the real build files (the assume-graph delta file was deleted in Stage B2-2b)
        graph = ma.load_module_graph(ROOT)
        self.assertEqual(graph.directories["ecr/data"], ":ecr-data")
        self.assertIn(":ecr-api", graph.visible[":ecr-server"])
        self.assertIn(":platform-server", graph.visible[":ecr-server"])
        self.assertIn(":ecr-api", graph.visible[":ecr-receipt"])
        for leaf in (":ecr-printer", ":ecr-voucher", ":ecr-customer", ":ecr-reservation", ":ecr-receipt", ":fiscal-process-data"):
            self.assertNotIn(":shared", graph.visible[leaf], leaf)
            self.assertNotIn(":core-data", graph.visible[leaf], leaf)


class PlanTest(unittest.TestCase):
    def test_shared_common_file_maps_to_platform_api_same_set(self):
        moves = ma.plan_moves({SHARED_UTIL: pkg("core.utils")}, {SHARED_UTIL: "platform-api"}, DIRS, "platform", None)
        self.assertEqual(moves, [ma.Move(SHARED_UTIL, f"platform/api/src/commonMain/{K}/core/utils/Format.kt", "platform-api", "main")])

    def test_core_data_file_maps_to_platform_data_main(self):
        moves = ma.plan_moves({CORE_DATA_TABLE: pkg("core.database.tables")}, {CORE_DATA_TABLE: "platform-data"}, DIRS, "platform", "data")
        self.assertEqual([(m.dst, m.kind) for m in moves], [(f"platform/data/src/main/{K}/core/database/tables/ShopTable.kt", "main")])

    def test_tier_filter_excludes_other_tiers(self):
        files = {SHARED_UTIL: pkg("core.utils"), CORE_DATA_TABLE: pkg("core.database.tables")}
        targets = {SHARED_UTIL: "platform-api", CORE_DATA_TABLE: "platform-data"}
        self.assertEqual([m.src for m in ma.plan_moves(files, targets, DIRS, "platform", "api")], [SHARED_UTIL])

    def test_file_row_overrides_package_target(self):
        path = f"pos-server/src/main/{K}/utils/TrainingFilter.kt"
        sibling = f"pos-server/src/main/{K}/utils/Strings.kt"
        files = {path: pkg("utils") + "object TrainingFilter\n", sibling: pkg("utils") + "fun s() = 1\n"}
        targets, _ = ma.sp.effective_targets(files)  # the real overrides: a placement row sends TrainingFilter to ecr-data
        self.assertEqual(targets[path], "ecr-data")
        self.assertEqual(targets[sibling], "platform-server")
        self.assertEqual([m.src for m in ma.plan_moves(files, targets, DIRS, "platform", None)], [sibling])

    def test_host_placement_never_moves(self):
        path = f"pos-server/src/main/{K}/features/setup/data/service/InitialSetupServiceImpl.kt"
        files = {path: pkg("features.setup.data.service") + "class InitialSetupServiceImpl\n"}
        targets, _ = ma.sp.effective_targets(files)
        self.assertEqual(targets[path], "host")
        self.assertEqual(ma.plan_moves(files, targets, DIRS, "platform", None), [])

    def test_other_area_never_moves(self):
        path = f"pos-server/src/main/{K}/features/zBon/Z.kt"
        self.assertEqual(ma.plan_moves({path: pkg("features.zBon")}, {path: "fiscal-server"}, DIRS, "platform", None), [])

    def test_unmapped_source_set_is_an_error(self):
        path = f"shared/src/iosMain/{K}/core/utils/Format.ios.kt"
        with self.assertRaises(ValueError) as raised:
            ma.plan_moves({path: pkg("core.utils")}, {path: "platform-api"}, DIRS, "platform", None)
        self.assertIn("iosMain", str(raised.exception))

    def test_target_without_module_dir_is_an_error(self):
        path = f"pos-server/src/main/{K}/core/Gone.kt"
        dirs = {k: v for k, v in DIRS.items() if k != ":platform-server"}
        with self.assertRaises(ValueError):
            ma.plan_moves({path: pkg("core")}, {path: "platform-server"}, dirs, "platform", None)

    def test_resources_never_move(self):
        path = "pos-server/src/main/resources/db/migration/V1__base.sql"
        self.assertEqual(ma.plan_moves({path: "select 1;"}, {path: "platform-server"}, DIRS, "platform", None), [])

    def test_non_kotlin_file_moves_with_its_package_directory(self):
        note = f"pos-server/src/main/{K}/core/utils/README.md"
        files = {SERVER_UTIL: pkg("core.utils"), note: "notes"}
        moves = ma.plan_moves(files, {SERVER_UTIL: "platform-server"}, DIRS, "platform", None)
        self.assertEqual([(m.src, m.dst) for m in moves if m.src == note],
                         [(note, f"platform/server/src/main/{K}/core/utils/README.md")])

    def test_non_kotlin_file_in_split_directory_is_blocking(self):
        note = f"pos-server/src/main/{K}/core/utils/README.md"
        other = f"pos-server/src/main/{K}/core/utils/Host.kt"
        files = {SERVER_UTIL: pkg("core.utils"), other: pkg("core.utils"), note: "notes"}
        result = ma.plan(files, {SERVER_UTIL: "platform-server", other: "host"}, DIRS, "platform", None)
        self.assertNotIn(note, [m.src for m in result.moves])
        self.assertEqual([(f.kind, f.path, f.blocking) for f in result.findings], [("non-kotlin-split", note, True)])

    def test_rerun_after_apply_is_noop(self):
        moved = f"platform/server/src/main/{K}/core/utils/Clock.kt"
        moved_api = f"platform/api/src/commonMain/{K}/core/utils/Format.kt"
        files = {moved: pkg("core.utils"), moved_api: pkg("core.utils")}
        result = ma.plan(files, {moved: "platform-server", moved_api: "platform-api"}, DIRS, "platform", None)
        self.assertEqual(result.moves, [])
        self.assertEqual(result.findings, [])


class TestPlacementTest(unittest.TestCase):
    def files(self):
        return {SERVER_UTIL: pkg("core.utils") + "object Clock\n", SERVER_UTIL_TEST: pkg("core.utils") + "class ClockTest\n"}

    def test_subject_target_comes_from_the_tested_file(self):
        files = self.files()
        tests = ma.subject_targets(files, {SERVER_UTIL: "ecr-server"}, lambda module, package: "platform-server")
        self.assertEqual(tests, {SERVER_UTIL_TEST: "ecr-server"})

    def test_subject_target_falls_back_to_the_package_rule(self):
        files = {SERVER_UTIL_TEST: pkg("core.utils") + "class SomethingTest\n"}
        tests = ma.subject_targets(files, {}, lambda module, package: f"{module}:{package}")
        self.assertEqual(tests, {SERVER_UTIL_TEST: "pos-server:core.utils"})

    def test_test_file_not_closed_stays_and_is_reported(self):
        targets = {SERVER_UTIL: "platform-server", SERVER_UTIL_TEST: "platform-server"}
        result = ma.plan(self.files(), targets, DIRS, "platform", None)  # default predicate: nothing is closed
        self.assertEqual([m.src for m in result.moves], [SERVER_UTIL])
        self.assertEqual([(f.kind, f.path, f.blocking) for f in result.findings], [("test-stays", SERVER_UTIL_TEST, False)])

    def test_closed_test_follows_its_subject(self):
        targets = {SERVER_UTIL: "platform-server", SERVER_UTIL_TEST: "platform-server"}
        result = ma.plan(self.files(), targets, DIRS, "platform", None, is_closed=lambda path, placement: True)
        self.assertIn(ma.Move(SERVER_UTIL_TEST, f"platform/server/src/test/{K}/core/utils/ClockTest.kt", "platform-server", "test"), result.moves)
        self.assertEqual(result.findings, [])

    def test_closure_is_a_fixpoint_over_moving_tests(self):
        helper = f"pos-server/src/test/{K}/core/utils/ClockFixture.kt"
        files = dict(self.files(), **{helper: pkg("core.utils") + "object ClockFixture\n"})
        targets = {SERVER_UTIL: "platform-server", SERVER_UTIL_TEST: "platform-server", helper: "platform-server"}
        seen: list[dict[str, str]] = []

        def closed(path: str, placement: dict[str, str]) -> bool:
            seen.append(dict(placement))
            if path == helper:
                return False  # helper needs a host testutil
            return placement[helper] == ":platform-server"  # the test needs the helper next to it

        result = ma.plan(files, targets, DIRS, "platform", None, is_closed=closed)
        self.assertEqual([m.src for m in result.moves], [SERVER_UTIL])
        self.assertEqual(sorted(f.path for f in result.findings if f.kind == "test-stays"), sorted([SERVER_UTIL_TEST, helper]))
        self.assertEqual(seen[0][SERVER_UTIL], ":platform-server")


class ApplyTest(unittest.TestCase):
    def test_apply_refuses_dirty_tree_or_blocking_findings(self):
        blocking = [ma.Finding("non-kotlin-split", "a", "b", True)]
        info = [ma.Finding("test-stays", "a", "b", False)]
        self.assertIsNone(ma.apply_refusal(False, info))
        self.assertIn("dirty", ma.apply_refusal(True, []))
        self.assertIn("blocking", ma.apply_refusal(False, blocking))

    def test_apply_issues_git_mv_in_sorted_order(self):
        calls: list[list[str]] = []
        moves = [ma.Move("b/src/main/kotlin/B.kt", "x/src/main/kotlin/B.kt", "platform-server", "main"),
                 ma.Move("a/src/main/kotlin/A.kt", "x/src/main/kotlin/A.kt", "platform-server", "main"),
                 ma.Move("a/src/test/kotlin/T.kt", "x/src/test/kotlin/T.kt", "platform-server", "test")]
        ma.apply_moves(moves, calls.append)
        self.assertEqual(calls, [
            ["mkdir", "-p", "x/src/main/kotlin"], ["git", "mv", "a/src/main/kotlin/A.kt", "x/src/main/kotlin/A.kt"],
            ["mkdir", "-p", "x/src/test/kotlin"], ["git", "mv", "a/src/test/kotlin/T.kt", "x/src/test/kotlin/T.kt"],
            ["git", "mv", "b/src/main/kotlin/B.kt", "x/src/main/kotlin/B.kt"]])


class CliTest(unittest.TestCase):
    def test_usage_errors_exit_2(self):
        self.assertEqual(ma.parse_args(["--tier", "api"]), None)
        self.assertEqual(ma.parse_args(["--area", "platform", "--tier", "kernel"]), None)
        self.assertEqual(ma.parse_args(["--area", "platform", "--bogus"]), None)
        self.assertEqual(ma.parse_args(["--area", "platform", "--tier", "server", "--apply"]).tier, "server")

    def test_report_is_deterministic_and_counts_per_target_set(self):
        targets = {SERVER_UTIL: "platform-server", SERVER_UTIL_TEST: "platform-server", SHARED_UTIL: "platform-api"}
        files = dict({SHARED_UTIL: pkg("core.utils")}, **TestPlacementTest().files())
        result = ma.plan(files, targets, DIRS, "platform", None)
        report = ma.render_report(result, "platform", None)
        self.assertEqual(report, ma.render_report(ma.plan(dict(reversed(list(files.items()))), targets, DIRS, "platform", None), "platform", None))
        self.assertIn("| :platform-server | main | main | 1 |", report)
        self.assertIn("| :platform-api | commonMain | main | 1 |", report)
        self.assertIn("test-stays", report)


class ReviewFixesTest(unittest.TestCase):
    MOVE = ma.Move(SERVER_UTIL, f"platform/server/src/main/{K}/core/utils/Clock.kt", "platform-server", "main")

    def test_rerun_on_platform_server_path_with_real_effective_targets_is_noop(self):
        moved = f"platform/server/src/main/{K}/core/utils/Clock.kt"
        files = {moved: pkg("core.utils") + "object Clock\n"}
        targets, _ = ma.sp.effective_targets(files)
        result = ma.plan(files, targets, DIRS, "platform", None)
        self.assertEqual((result.moves, result.findings), ([], []))

    def test_untracked_source_is_blocking(self):
        findings = ma.untracked_findings([self.MOVE], tracked=set())
        self.assertEqual([(f.kind, f.path, f.blocking) for f in findings], [("untracked-source", SERVER_UTIL, True)])
        self.assertEqual(ma.untracked_findings([self.MOVE], tracked={SERVER_UTIL}), [])

    def test_preflight_dst_exists_parent_is_file_and_duplicate(self):
        dup = ma.Move(f"pos-server/src/main/{K}/core/utils/Other.kt", self.MOVE.dst, "platform-server", "main")
        kinds = {self.MOVE.dst: "file", "platform/server/src": "file"}
        findings = ma.preflight_findings([self.MOVE, dup], kinds.get)
        self.assertEqual(sorted({f.kind for f in findings}), ["dst-exists", "dst-parent-is-file", "duplicate-dst"])
        self.assertTrue(all(f.blocking for f in findings))
        self.assertEqual(ma.preflight_findings([self.MOVE], {"platform": "dir"}.get), [])

    def test_partial_apply_reports_done_and_remaining(self):
        moves = [ma.Move(f"a/src/main/kotlin/{n}.kt", f"x/src/main/kotlin/{n}.kt", "platform-server", "main") for n in "ABC"]

        def run(command: list[str]) -> None:
            if command[:2] == ["git", "mv"] and command[2].endswith("B.kt"):
                raise subprocess.CalledProcessError(128, command)

        with self.assertRaises(ma.PartialApply) as raised:
            ma.apply_moves(moves, run)
        partial = raised.exception
        self.assertEqual([m.src for m in partial.done], ["a/src/main/kotlin/A.kt"])
        self.assertEqual([m.src for m in partial.remaining], ["a/src/main/kotlin/B.kt", "a/src/main/kotlin/C.kt"])
        progress = ma.render_progress(partial)
        self.assertIn("git mv x/src/main/kotlin/A.kt a/src/main/kotlin/A.kt", progress)
        message = ma.recovery_message(partial, "p.txt", "cmd --apply")
        self.assertIn("git status", message)
        self.assertNotIn("reset --hard", message)

    def test_resume_allows_only_staged_renames_of_the_plan(self):
        rename = f"R  {self.MOVE.src} -> {self.MOVE.dst}\n"
        self.assertEqual(ma.resume_state(rename, [self.MOVE]), ma.Resume({(self.MOVE.src, self.MOVE.dst)}, []))
        self.assertEqual(len(ma.resume_state(rename, []).problems), 1)  # a rename the plan does not contain
        self.assertEqual(len(ma.resume_state(" M build.gradle.kts\n", [self.MOVE]).problems), 1)
        self.assertEqual(len(ma.resume_state(rename.replace("R ", "RM", 1), [self.MOVE]).problems), 1)  # renamed and edited

    def test_pre_apply_view_replans_the_done_moves(self):
        files = {self.MOVE.dst: pkg("core.utils")}
        view = ma.pre_apply_view(files, ma.staged_renames(f"R  {self.MOVE.src} -> {self.MOVE.dst}\n"))
        self.assertEqual(ma.plan_moves(view, {SERVER_UTIL: "platform-server"}, DIRS, "platform", None), [self.MOVE])

    def test_test_in_another_source_module_stays(self):
        test = f"pos-server/src/test/{K}/utils/OwnerTipWeightsTest.kt"
        consulted: list[str] = []
        result = ma.plan({test: pkg("utils")}, {test: "platform-data"}, DIRS, "platform", None,
                         is_closed=lambda path, placement: consulted.append(path) or True)
        self.assertEqual(result.moves, [])
        self.assertEqual(consulted, [])
        self.assertEqual([f.kind for f in result.findings], ["test-stays"])
        self.assertIn("subject in another source module", result.findings[0].detail)

    def test_test_in_unmapped_source_set_stays_instead_of_raising(self):
        test = f"shared/src/iosTest/{K}/core/utils/FormatTest.kt"
        result = ma.plan({test: pkg("core.utils")}, {test: "platform-api"}, DIRS, "platform", None, is_closed=lambda p, q: True)
        self.assertEqual(result.moves, [])
        self.assertIn("iosTest", result.findings[0].detail)


class MainOrderTest(unittest.TestCase):
    # the real override tables would flag a two-file fake tree, so effective_targets is stubbed; the flow under test is main's
    FILES = {SERVER_UTIL: pkg("core.utils") + "object Clock\n"}

    def run_main(self, status: str, argv: list[str] | None = None) -> tuple[int, list[str]]:
        events: list[str] = []
        real_refusal = ma.apply_refusal

        def refusal(dirty, findings):
            events.append("refusal")
            return real_refusal(dirty, findings)

        with mock.patch.object(ma, "_git_status", return_value=status), \
                mock.patch.object(ma, "collect_all_files", return_value=dict(self.FILES)), \
                mock.patch.object(ma, "collect_universe", side_effect=lambda files, dirs: dict(files)), \
                mock.patch.object(ma.sp, "effective_targets", return_value=({SERVER_UTIL: "platform-server"}, [])), \
                mock.patch.object(ma, "_tracked", side_effect=lambda paths: set(paths)), \
                mock.patch.object(ma, "_path_kind", return_value=None), \
                mock.patch.object(ma, "apply_refusal", side_effect=refusal), \
                mock.patch.object(ma, "apply_moves", side_effect=lambda moves, run: events.append("apply")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = ma.main(argv or ["--area", "platform", "--apply"])
        return code, events

    def test_main_checks_refusal_before_apply(self):
        self.assertEqual(self.run_main(""), (0, ["refusal", "apply"]))

    def test_main_dirty_tree_never_applies(self):
        self.assertEqual(self.run_main(" M build.gradle.kts\n"), (1, ["refusal"]))

    def test_main_dry_run_never_applies(self):
        self.assertEqual(self.run_main(" M build.gradle.kts\n", ["--area", "platform"]), (0, []))

    def test_main_partial_failure_writes_progress_next_to_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = f"{tmp}/plan.md"

            def failing(command: list[str]) -> None:
                if command[:2] == ["git", "mv"]:
                    raise subprocess.CalledProcessError(128, command)

            err = io.StringIO()
            with mock.patch.object(ma, "_git_status", return_value=""), \
                    mock.patch.object(ma, "collect_all_files", return_value=dict(self.FILES)), \
                    mock.patch.object(ma, "collect_universe", side_effect=lambda files, dirs: dict(files)), \
                mock.patch.object(ma, "collect_universe", side_effect=lambda files, dirs: dict(files)), \
                    mock.patch.object(ma.sp, "effective_targets", return_value=({SERVER_UTIL: "platform-server"}, [])), \
                    mock.patch.object(ma, "_tracked", side_effect=lambda paths: set(paths)), \
                    mock.patch.object(ma, "_path_kind", return_value=None), \
                    mock.patch.object(ma, "_run", side_effect=failing), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                code = ma.main(["--area", "platform", "--apply", "--report", report])
            self.assertEqual(code, 1)
            self.assertIn("after 0 of 1 moves", err.getvalue())
            self.assertIn(SERVER_UTIL, Path(f"{tmp}/plan.apply-progress.txt").read_text(encoding="utf-8"))


P = "org.example.project"
UTIL = f"pos-server/src/main/{K}/core/utils"
HOST = f"pos-server/src/main/{K}/infrastructure"
TESTUTILS = f"pos-server/src/test/{K}/testutils/TestDatabase.kt"
# (dependency, exported?) per module: `api(...)` re-exports, `implementation(...)` does not
EDGES = {
    ":pos-server": {(":platform-server", True), (":core-data", True), (":shared", False)},
    ":platform-server": {(":platform-data", True), (":platform-server-common", True)},
    ":platform-data": {(":platform-api", True)},
    ":platform-server-common": {(":shared", True)},
    ":shared": {(":platform-api", True)},
    ":core-data": {(":shared", False), (":platform-data", True)},
    ":ui-till": {(":shared", False)},
    ":platform-api": set(),
}
MODULE_DIRS = {"pos-server": ":pos-server", "shared": ":shared", "core-data": ":core-data", "ui/till": ":ui-till",
               "platform/server-common": ":platform-server-common", "platform/api": ":platform-api",
               "platform/data": ":platform-data", "platform/server": ":platform-server"}
GRAPH = ma.module_graph(EDGES, MODULE_DIRS)


def server_move(path: str) -> "ma.Move":
    located = ma.locate(path)
    return ma.Move(path, f"platform/server/src/{located[1]}/kotlin/{located[2]}", "platform-server", "test" if "/src/test/" in path else "main")


def run_checks(files: dict[str, str], moving: list[str]) -> list[tuple[str, str, bool]]:
    moves = [server_move(p) for p in moving]
    findings = ma.checks(files, moves, {m.src: m.target for m in moves}, GRAPH)
    return [(f.kind, f.path, f.blocking) for f in findings if f.kind != "external-imports"]


class ChecksTest(unittest.TestCase):
    def test_collision_same_file_name_same_package_two_modules(self):
        moving = f"{UTIL}/Clock.kt"
        other = f"platform/server-common/src/main/{K}/core/utils/Clock.kt"
        files = {moving: pkg("core.utils") + "fun now() = 1\n", other: pkg("core.utils") + "fun later() = 2\n"}
        found = run_checks(files, [moving])
        self.assertEqual(found, [("collision", moving, True)])

    def test_collision_same_jvmname(self):
        moving = f"{UTIL}/Clock.kt"
        other = f"platform/server-common/src/main/{K}/core/utils/Time.kt"
        files = {moving: pkg("core.utils") + "fun now() = 1\n",
                 other: '@file:JvmName("ClockKt")\n' + pkg("core.utils") + "fun later() = 2\n"}
        self.assertEqual([k for k, _, _ in run_checks(files, [moving])], ["collision"])

    def test_collision_same_top_level_class_fqn(self):
        moving = f"{UTIL}/Clock.kt"
        other = f"platform/server-common/src/main/{K}/core/utils/Clocks.kt"
        files = {moving: pkg("core.utils") + "class SystemClock\n", other: pkg("core.utils") + "class SystemClock\n"}
        self.assertEqual([k for k, _, _ in run_checks(files, [moving])], ["collision"])

    def test_no_collision_between_modules_never_on_one_classpath(self):
        server = f"{HOST}/Main.kt"
        till = f"ui/till/src/desktopMain/{K}/Main.kt"
        files = {server: f"package {P}\nfun main() {{}}\n", till: f"package {P}\nfun main() {{}}\n"}
        self.assertEqual(run_checks(files, []), [])

    def test_no_collision_between_tests_or_android_and_server(self):
        a = f"pos-server/src/test/{K}/core/utils/ClockTest.kt"
        b = f"shared/src/commonTest/{K}/core/utils/ClockTest.kt"
        c = f"shared/src/androidMain/{K}/core/utils/Clock.kt"
        moving = f"{UTIL}/Clock.kt"
        files = {a: pkg("core.utils") + "class ClockTest\n", b: pkg("core.utils") + "class ClockTest\n",
                 c: pkg("core.utils") + "fun x() = 1\n", moving: pkg("core.utils") + "fun y() = 1\n"}
        self.assertEqual(run_checks(files, [moving]), [])

    def test_expect_actual_must_move_together(self):
        expect = f"shared/src/commonMain/{K}/core/utils/Io.kt"
        actual = f"shared/src/jvmMain/{K}/core/utils/Io.jvm.kt"
        files = {expect: pkg("core.utils") + "expect val ioDispatcher: Int\n",
                 actual: pkg("core.utils") + "actual val ioDispatcher: Int = 1\n"}
        api = ma.Move(expect, f"platform/api/src/commonMain/{K}/core/utils/Io.kt", "platform-api", "main")
        findings = ma.checks(files, [api], {expect: "platform-api"}, GRAPH)
        self.assertIn(("expect-actual-split", expect, True), [(f.kind, f.path, f.blocking) for f in findings])
        both = [api, ma.Move(actual, f"platform/api/src/jvmMain/{K}/core/utils/Io.jvm.kt", "platform-api", "main")]
        self.assertNotIn("expect-actual-split", [f.kind for f in ma.checks(files, both, {}, GRAPH)])

    def test_internal_crossing_blocks_apply(self):
        moving = f"{UTIL}/Clock.kt"
        user = f"{UTIL}/Host.kt"
        files = {moving: pkg("core.utils") + "internal fun tick() = 1\n", user: pkg("core.utils") + "fun h() = tick()\n"}
        found = run_checks(files, [moving])
        self.assertIn(("internal-crossing", user, True), found)
        self.assertIn("blocking", ma.apply_refusal(False, [ma.Finding(*f[:2], "", f[2]) for f in found]))

    def test_internal_import_across_new_boundary_is_a_crossing(self):
        moving = f"{UTIL}/Clock.kt"
        user = f"{HOST}/Wiring.kt"
        files = {moving: pkg("core.utils") + "internal fun tick() = 1\n",
                 user: pkg("infrastructure") + f"import {P}.core.utils.tick\nfun w() = tick()\n"}
        self.assertIn(("internal-crossing", user, True), run_checks(files, [moving]))

    def test_internal_member_used_from_other_module_blocks(self):
        moving = f"{UTIL}/Clock.kt"
        sibling = f"{UTIL}/Host.kt"
        importer = f"{HOST}/Wiring.kt"
        decl = pkg("core.utils") + "object Clock {\n    internal fun tick() = 1\n    fun open() = 2\n    val kinds = mapOf(Clock::class to 1)\n    companion object {}\n}\n"
        self.assertEqual(ma.sp.internal_member_names(decl), {"Clock": {"tick"}})
        files = {moving: decl,
                 sibling: pkg("core.utils") + "fun h() = Clock.tick()\n",
                 importer: pkg("infrastructure") + f"import {P}.core.utils.Clock\nval w = Clock::tick\n"}
        found = run_checks(files, [moving])
        self.assertIn(("internal-member-crossing", sibling, True), found)
        self.assertIn(("internal-member-crossing", importer, True), found)
        details = [f.detail for f in ma.checks(files, [server_move(moving)], {}, GRAPH) if f.kind == "internal-member-crossing"]
        self.assertTrue(all("`Clock.tick`" in d for d in details), details)
        # the reverse direction: the user moves, the declaring class stays
        self.assertIn(("internal-member-crossing", sibling, True), run_checks(files, [sibling]))
        # a member of a companion object belongs to its class; a qualified use names the class too
        companion = pkg("core.utils") + "class Clock {\n    companion object {\n        internal val zone = 1\n    }\n}\n"
        self.assertEqual(ma.sp.internal_member_names(companion), {"Clock": {"zone"}})
        qualified = {moving: companion, importer: pkg("infrastructure") + f"val z = {P}.core.utils.Clock.zone\n"}
        self.assertIn(("internal-member-crossing", importer, True), run_checks(qualified, [moving]))

    def test_internal_member_used_within_module_not_reported(self):
        moving = f"{UTIL}/Clock.kt"
        sibling = f"{UTIL}/Host.kt"
        files = {moving: pkg("core.utils") + "object Clock {\n    internal fun tick() = 1\n}\n",
                 sibling: pkg("core.utils") + "fun h() = Clock.tick()\n"}
        self.assertEqual(ma.sp.internal_member_names(files[moving]), {"Clock": {"tick"}})
        self.assertEqual(run_checks(files, [moving, sibling]), [])  # both move: still one module
        self.assertEqual(run_checks(files, []), [])                  # nothing moves

    def test_internal_member_name_without_class_reference_not_reported(self):
        moving = f"{UTIL}/Clock.kt"
        sibling = f"{UTIL}/Host.kt"
        other = f"{HOST}/Wiring.kt"
        files = {moving: pkg("core.utils") + "object Clock {\n    internal fun tick() = 1\n}\n",
                 sibling: pkg("core.utils") + "class Timer { fun tick() = 2 }\nfun h(t: Timer) = t.tick()\n",
                 # names a `Clock`, but not this one: another package, no import, no qualified use
                 other: pkg("infrastructure") + "class Clock { fun tick() = 3 }\nfun w() = Clock().tick()\n"}
        self.assertEqual(ma.sp.internal_member_names(files[other]), {})
        self.assertNotIn("internal-member-crossing", [k for k, _, _ in run_checks(files, [moving])])

    def test_unresolved_import_outside_closure(self):
        moving = f"{UTIL}/Clock.kt"
        host = f"{HOST}/Koin.kt"
        files = {moving: pkg("core.utils") + f"import {P}.infrastructure.Koin\nfun c() = Koin\n",
                 host: pkg("infrastructure") + "object Koin\n"}
        self.assertEqual(run_checks(files, [moving]), [("unresolved-import", moving, True)])

    def test_import_inside_platform_closure_resolves(self):
        moving = f"{UTIL}/Clock.kt"
        api = f"platform/api/src/commonMain/{K}/core/time/Instant.kt"
        files = {moving: pkg("core.utils") + f"import {P}.core.time.Instant\nfun c(i: Instant) = i\n",
                 api: pkg("core.time") + "class Instant\n"}
        self.assertEqual(run_checks(files, [moving]), [])

    def test_implementation_dependency_is_not_reexported(self):
        # :pos-server sees :shared, :core-data does not export :shared: a file moving into core-data's view breaks
        graph = ma.module_graph({":a": {(":b", False)}, ":b": {(":c", False)}, ":c": set(), ":d": {(":b", True)}}, {})
        self.assertEqual(graph.visible[":a"], frozenset({":b"}))
        self.assertEqual(graph.visible[":d"], frozenset({":b"}))
        graph = ma.module_graph({":a": {(":b", False)}, ":b": {(":c", True)}, ":c": set()}, {})
        self.assertEqual(graph.visible[":a"], frozenset({":b", ":c"}))

    def test_wildcard_import_resolves_against_closure(self):
        moving = f"{UTIL}/Clock.kt"
        host = f"{HOST}/Koin.kt"
        api = f"platform/api/src/commonMain/{K}/core/time/Instant.kt"
        ok = {moving: pkg("core.utils") + f"import {P}.core.time.*\nfun c(i: Instant) = i\n", api: pkg("core.time") + "class Instant\n"}
        self.assertEqual(run_checks(ok, [moving]), [])
        bad = {moving: pkg("core.utils") + f"import {P}.infrastructure.*\nfun c() = Koin\n", host: pkg("infrastructure") + "object Koin\n"}
        self.assertEqual(run_checks(bad, [moving]), [("unresolved-import", moving, True)])

    def test_nested_member_alias_and_kdoc_imports(self):
        moving = f"{UTIL}/Clock.kt"
        api = f"platform/api/src/commonMain/{K}/core/time/TimeDto.kt"
        host = f"{HOST}/Koin.kt"
        files = {
            moving: pkg("core.utils") + f"import {P}.core.time.TimeDto.ResponseDto\nimport {P}.core.time.Ticker.Companion.tick\n"
                                        f"import {P}.core.time.zone as z\nimport {P}.core.time.Ticker as T\n"
                                        f"/**\n * import {P}.infrastructure.Koin\n * see [{P}.infrastructure.Koin]\n */\nfun c() = 1\n",
            api: pkg("core.time") + "object TimeDto { class ResponseDto }\nclass Ticker { companion object { fun tick() = 1 } }\nval zone = 1\n",
            host: pkg("infrastructure") + "object Koin\n"}
        self.assertEqual(run_checks(files, [moving]), [])

    def test_nested_member_alias_typealias_and_wildcard_outside_closure_are_unresolved(self):
        moving = f"{UTIL}/Clock.kt"
        host = f"{HOST}/KoinDto.kt"
        decls = pkg("infrastructure") + "object KoinDto { class ResponseDto }\nclass Wiring { companion object { fun boot() = 1 } }\ntypealias Wired = Wiring\n"
        for imp, use in ((f"{P}.infrastructure.KoinDto.ResponseDto", "ResponseDto()"),
                         (f"{P}.infrastructure.Wiring.Companion.boot", "boot()"),
                         (f"{P}.infrastructure.Wired", "Wired()"),
                         (f"{P}.infrastructure.Wiring as W", "W()"),
                         (f"{P}.infrastructure.KoinDto.*", "ResponseDto()"),
                         (f"{P}.infrastructure.*", "Wired()")):
            files = {moving: pkg("core.utils") + f"import {imp}\nfun c() = {use}\n", host: decls}
            self.assertEqual(run_checks(files, [moving]), [("unresolved-import", moving, True)], imp)

    def test_qualified_use_outside_closure_is_unresolved(self):
        moving = f"{UTIL}/Clock.kt"
        host = f"{HOST}/Koin.kt"
        files = {moving: pkg("core.utils") + f"fun c() = {P}.infrastructure.Koin\n", host: pkg("infrastructure") + "object Koin\n"}
        self.assertEqual(run_checks(files, [moving]), [("unresolved-import", moving, True)])

    def test_same_package_ref_outside_closure(self):
        moving = f"{UTIL}/Clock.kt"
        host = f"{UTIL}/HostClock.kt"
        files = {moving: pkg("core.utils") + "fun c() = HostClock.now()\n", host: pkg("core.utils") + "object HostClock { fun now() = 1 }\n"}
        found = run_checks(files, [moving])
        self.assertEqual(found, [("unresolved-same-package", moving, True)])

    def test_reference_unsatisfied_before_the_move_is_not_reported(self):
        moving = f"{UTIL}/Clock.kt"
        files = {moving: pkg("core.utils") + "object Clock\n",
                 f"ui/till/src/desktopMain/{K}/core/utils/Ui.kt": pkg("core.utils") + "fun u() = Clock\n"}
        self.assertEqual(run_checks(files, [moving]), [])  # ui-till never saw pos-server: a name clash, not introduced by the move

    def test_staying_user_of_a_moved_file_is_checked(self):
        moving = f"{UTIL}/Clock.kt"
        user = f"core-data/src/main/{K}/core/utils/Rows.kt"  # core-data does not see platform-server
        files = {moving: pkg("core.utils") + "object Clock\n", user: pkg("core.utils") + f"import {P}.core.utils.Clock\nfun r() = Clock\n"}
        graph = ma.module_graph(dict(EDGES, **{":core-data": {(":pos-server", False)}, ":pos-server": {(":platform-server", False)}}), MODULE_DIRS)
        found = ma.checks(files, [server_move(moving)], {}, graph)
        self.assertEqual([(f.kind, f.path) for f in found if f.kind != "external-imports"], [("unresolved-import", user)])

    def test_buildconfig_is_generated_symbol_not_unresolved(self):
        moving = f"{UTIL}/Version.kt"
        files = {moving: pkg("core.utils") + f"import {P}.BuildConfig\nfun v() = BuildConfig.VERSION\n"}
        self.assertEqual(run_checks(files, [moving]), [("generated-symbol", moving, False)])

    def test_resource_lookup_reported(self):
        moving = f"{UTIL}/Csv.kt"
        files = {moving: pkg("core.utils") + 'fun r() = object {}.javaClass.getResourceAsStream("/base.sql")\n'}
        self.assertEqual(run_checks(files, [moving]), [("resource-lookup", moving, False)])

    def test_string_logger_reported(self):
        moving = f"{UTIL}/Log.kt"
        files = {moving: pkg("core.utils") + 'val log = LoggerFactory.getLogger("AUDIT")\n'}
        self.assertEqual(run_checks(files, [moving]), [("string-logger", moving, False)])

    def test_source_path_literal_in_any_test(self):
        test = f"pos-server/src/test/{K}/guards/ScanTest.kt"
        files = {test: pkg("guards") + 'val root = File("pos-server/src/main/kotlin")\n'}
        self.assertEqual(run_checks(files, []), [("source-path-literal", test, False)])

    def test_external_imports_per_target(self):
        moving = f"{UTIL}/Clock.kt"
        files = {moving: pkg("core.utils") + "import io.ktor.server.routing.Route\nimport org.jetbrains.exposed.sql.Table\n"
                                             f"import {P}.core.utils.Other\nimport kotlin.time.Duration\n"
                                             "import kotlin.math.abs\nimport kotlinx.coroutines.*\n"}
        moves = [server_move(moving)]
        found = [f for f in ma.checks(files, moves, {}, GRAPH) if f.kind == "external-imports"]
        self.assertEqual([(f.path, f.detail, f.blocking) for f in found],
                         [(":platform-server main", "io.ktor.server, kotlin.math, kotlin.time, kotlinx.coroutines, org.jetbrains.exposed", False)])


class ClosureTest(unittest.TestCase):
    SUBJECT = f"{UTIL}/Clock.kt"
    TEST = f"pos-server/src/test/{K}/core/utils/ClockTest.kt"

    def plan(self, files: dict[str, str]) -> "ma.Plan":
        checker = ma.Checker(files, GRAPH)
        targets = {p: "platform-server" for p in files if p.startswith(UTIL) or p.startswith(f"pos-server/src/test/{K}/core/utils")}
        return ma.plan(files, targets, DIRS, "platform", None, is_closed=checker.is_closed)

    def test_closed_test_moves_with_its_subject(self):
        files = {self.SUBJECT: pkg("core.utils") + "object Clock\n", self.TEST: pkg("core.utils") + "class ClockTest { val c = Clock }\n"}
        self.assertIn(self.TEST, [m.src for m in self.plan(files).moves])

    def test_test_importing_host_testutils_stays(self):
        files = {self.SUBJECT: pkg("core.utils") + "object Clock\n", TESTUTILS: pkg("testutils") + "object TestDatabase\n",
                 self.TEST: pkg("core.utils") + f"import {P}.testutils.TestDatabase\nclass ClockTest {{ val d = TestDatabase }}\n"}
        result = self.plan(files)
        self.assertNotIn(self.TEST, [m.src for m in result.moves])
        self.assertIn(("test-stays", self.TEST), [(f.kind, f.path) for f in result.findings])

    def test_tagged_or_resource_loading_test_stays(self):
        for body in ('@Tag("postgres")\nclass ClockTest\n', '@Tag("realdata")\nclass ClockTest\n', '@Tag("fatjar")\nclass ClockTest\n',
                     'class ClockTest { val s = javaClass.getResource("/x.sql") }\n',
                     'class ClockTest { val root = File("src/main/kotlin") }\n'):
            files = {self.SUBJECT: pkg("core.utils") + "object Clock\n", self.TEST: pkg("core.utils") + body}
            self.assertNotIn(self.TEST, [m.src for m in self.plan(files).moves], body)

    def test_test_of_a_main_file_reading_host_resources_stays(self):
        # Task 9: JwtConfig/ApiDocsPolicy/MaintenancePolicy read application.yaml (a :pos-server resource) through
        # YamlConfigLoader; their tests failed on :platform-server's test classpath ("Could not load application.yaml").
        for subject in ("object Clock { val c = YamlConfigLoader.load() }\n",
                        'object Clock { val s = Clock::class.java.classLoader.getResourceAsStream("x.sql") }\n'):
            files = {self.SUBJECT: pkg("core.utils") + subject, self.TEST: pkg("core.utils") + "class ClockTest { val c = Clock }\n"}
            checker = ma.Checker(files, GRAPH)
            targets = {self.SUBJECT: "platform-server", self.TEST: "platform-server"}
            result = ma.checked_plan(files, targets, DIRS, "platform", None, checker)
            self.assertNotIn(self.TEST, [m.src for m in result.moves], subject)
            stays = [f.detail for f in result.findings if f.kind == "test-stays"]
            self.assertEqual(len(stays), 1, subject)
            self.assertIn(f"uses `Clock` from {self.SUBJECT}, which reads host resources", stays[0])

    def test_test_of_a_main_file_not_reading_resources_still_moves(self):
        files = {self.SUBJECT: pkg("core.utils") + 'object Clock { val s = "application.yaml" }\n',
                 self.TEST: pkg("core.utils") + "class ClockTest { val c = Clock }\n"}
        self.assertIn(self.TEST, [m.src for m in self.plan(files).moves])

    def test_test_reaching_resource_reader_transitively_stays(self):
        # B2-1b Task 9 shape: the test uses a class that uses JwtConfig, which reads application.yaml through
        # YamlConfigLoader (in :platform-server-common); the direct rule sees only the test's own references.
        jwt = f"{UTIL}/JwtConfig.kt"
        loader = f"platform/server-common/src/main/{K}/core/config/YamlConfigLoader.kt"
        settings = f"{UTIL}/Settings.kt"
        files_loader = f"platform/server-common/src/main/{K}/core/config/ConfigFiles.kt"
        cases = (
            ({self.SUBJECT: pkg("core.utils") + "object Clock { val ttl = JwtConfig.ttl }\n",
              jwt: pkg("core.utils") + f"import {P}.core.config.YamlConfigLoader\nobject JwtConfig {{ val ttl = YamlConfigLoader.load() }}\n",
              loader: pkg("core.config") + 'object YamlConfigLoader { fun load() = javaClass.classLoader.getResourceAsStream("application.yaml") }\n'},
             [self.SUBJECT, jwt]),
            ({self.SUBJECT: pkg("core.utils") + "object Clock { val s = Settings.value }\n",
              settings: pkg("core.utils") + f"import {P}.core.config.ConfigFiles\nobject Settings {{ val value = ConfigFiles.read() }}\n",
              files_loader: pkg("core.config") + 'object ConfigFiles { fun read() = javaClass.getResourceAsStream("/x.yaml") }\n'},
             [self.SUBJECT, settings, files_loader]),
        )
        for main_files, chain in cases:
            files = {**main_files, self.TEST: pkg("core.utils") + "class ClockTest { val c = Clock }\n"}
            checker = ma.Checker(files, GRAPH)
            targets = {p: "platform-server" for p in files if p.startswith(UTIL)} | {self.TEST: "platform-server"}
            result = ma.checked_plan(files, targets, DIRS, "platform", None, checker)
            self.assertNotIn(self.TEST, [m.src for m in result.moves], chain)
            stays = [f.detail for f in result.findings if f.kind == "test-stays" and f.path == self.TEST]
            self.assertEqual(len(stays), 1, chain)
            self.assertIn(f"reaches {' → '.join(chain)}, which reads resources", stays[0])

    def test_test_not_reaching_resource_reader_still_moves(self):
        helper = f"{UTIL}/Settings.kt"
        loader = f"platform/server-common/src/main/{K}/core/config/ConfigFiles.kt"
        files = {self.SUBJECT: pkg("core.utils") + "object Clock { val s = Settings.value }\n",
                 helper: pkg("core.utils") + 'object Settings { val value = "application.yaml" }\n',
                 # a reader nobody on the test's path uses
                 loader: pkg("core.config") + 'object ConfigFiles { fun read() = javaClass.getResourceAsStream("/x.yaml") }\n',
                 self.TEST: pkg("core.utils") + "class ClockTest { val c = Clock }\n"}
        self.assertIsNone(ma.Checker(files, GRAPH).resource_chain(self.SUBJECT))
        self.assertIn(self.TEST, [m.src for m in self.plan(files).moves])

    def test_stay_reason_names_the_lost_requirement(self):
        files = {self.SUBJECT: pkg("core.utils") + "object Clock\n", TESTUTILS: pkg("testutils") + "object TestDatabase\n",
                 self.TEST: pkg("core.utils") + f"import {P}.testutils.TestDatabase\nclass ClockTest {{ val d = TestDatabase }}\n"}
        checker = ma.Checker(files, GRAPH)
        targets = {self.SUBJECT: "platform-server", self.TEST: "platform-server"}
        result = ma.checked_plan(files, targets, DIRS, "platform", None, checker)
        stays = [f.detail for f in result.findings if f.kind == "test-stays"]
        self.assertEqual(len(stays), 1)
        self.assertIn(f"needs `{P}.testutils.TestDatabase` from {TESTUTILS} (:pos-server)", stays[0])

    def test_helper_used_by_a_staying_test_stays(self):
        helper = f"pos-server/src/test/{K}/core/utils/ClockFixture.kt"
        staying = f"pos-server/src/test/{K}/features/order/OrderTest.kt"
        files = {self.SUBJECT: pkg("core.utils") + "object Clock\n", helper: pkg("core.utils") + "object ClockFixture\n",
                 staying: pkg("features.order") + f"import {P}.core.utils.ClockFixture\nclass OrderTest {{ val f = ClockFixture }}\n"}
        self.assertNotIn(helper, [m.src for m in self.plan(files).moves])

    def test_closure_receives_gradle_names(self):
        files = {self.SUBJECT: pkg("core.utils") + "object Clock\n", self.TEST: pkg("core.utils") + "class ClockTest { val c = Clock }\n"}
        checker = ma.Checker(files, GRAPH)
        self.assertTrue(checker.is_closed(self.TEST, {self.SUBJECT: ":platform-server", self.TEST: ":platform-server"}))
        self.assertTrue(checker.is_closed(self.TEST, {self.SUBJECT: "platform-server", self.TEST: "platform-server"}))
        self.assertFalse(checker.is_closed(self.TEST, {self.SUBJECT: ":pos-server", self.TEST: ":platform-server"}))


class GraphLoadingTest(unittest.TestCase):
    def test_assume_graph_parses_edges_with_optional_configuration(self):
        graph = ma.load_assume_graph("# comment\n:platform-data\t:platform-api\tapi\n:platform-server\t:platform-audit\n"
                                     ":platform-server-common\t-:shared\n")
        self.assertEqual(graph.add, {":platform-data": {(":platform-api", True)}, ":platform-server": {(":platform-audit", False)}})
        self.assertEqual(graph.remove, {":platform-server-common": {":shared"}})
        for bad in (":a\n", ":a\t-:b\tapi\n", ":a\t:b\tcompile\n"):
            with self.assertRaises(ValueError):
                ma.load_assume_graph(bad)

    def test_build_edges_distinguish_api_from_implementation(self):
        text = "dependencies {\n    api(projects.platformData)\n    implementation(projects.platformAudit)\n    // api(projects.shared)\n}\n"
        self.assertEqual(ma.build_edges(text, [":platform-data", ":platform-audit", ":shared"]),
                         {(":platform-data", True), (":platform-audit", False)})

    def test_real_graph_merged_with_assumed_edges(self):
        graph = ma.load_module_graph(ROOT, ":pos-server\t:platform-server\tapi\n:platform-server\t:platform-server-common\tapi\n")
        self.assertIn(":platform-server", graph.visible[":pos-server"])
        # Task 7 flipped server-common to api(platform-api) and platform-audit to platform-api, Task 8 moved
        # platform-audit off :core-data: the platform modules see neither residual blob through them
        self.assertNotIn(":shared", graph.visible[":platform-server"])
        self.assertNotIn(":core-data", graph.visible[":platform-server"])
        self.assertNotIn(":core-data", graph.visible[":platform-audit"])
        self.assertIn(":platform-api", graph.visible[":platform-server"])
        self.assertIn(":platform-data", graph.visible[":platform-server"])
        self.assertEqual(graph.directories["platform/server"], ":platform-server")

    def test_assume_graph_removes_real_edges(self):
        # the delta can drop an edge the build files declare (the ecr plan uses this for blob relabels)
        real = ma.load_module_graph(ROOT)
        # :backoffice-timetracking is an `implementation` of :pos-server that no `api` re-export reaches (:platform-server
        # is not usable here since :ecr-server re-exports it with `api`, and :accounting-document since :accounting-server does)
        self.assertIn(":backoffice-timetracking", real.visible[":pos-server"])
        graph = ma.load_module_graph(ROOT, ":pos-server\t-:backoffice-timetracking\n")
        self.assertNotIn(":backoffice-timetracking", graph.visible[":pos-server"])
        self.assertIn(":platform-api", graph.visible[":pos-server"])

    def test_removing_an_edge_the_build_does_not_have_is_an_error(self):
        with self.assertRaises(ValueError):
            ma.load_module_graph(ROOT, ":platform-money\t-:shared\n")

    def test_tier_assumption_is_cumulative(self):
        self.assertEqual(ma.earlier_tiers("server"), ("api", "data"))
        self.assertEqual(ma.earlier_tiers("api"), ())


class RenamePackageTest(unittest.TestCase):
    OLD = f"{P}.core.utils"
    NEW = f"{P}.platform.utils"

    def test_rename_package_rewrites_imports_in_ui_and_tests(self):
        files = {
            f"shared/src/commonMain/{K}/core/utils/Format.kt": f"package {self.OLD}\n\nfun format() = 1\nclass Money\n",
            f"ui/till/src/commonMain/{K}/Screen.kt": f"package {P}\n\nimport {self.OLD}.format\nimport {self.OLD}.*\nimport {self.OLD}.sub.Other\n",
            f"pos-server/src/test/{K}/FormatTest.kt": f"package {P}\n\nimport {self.OLD}.Money as M\n",
            "ui/till/build.gradle.kts": f"val x = {self.OLD}.Money::class\n",
        }
        out = ma.rename_package(files, self.OLD, self.NEW)
        self.assertEqual(out[f"shared/src/commonMain/{K}/core/utils/Format.kt"].splitlines()[0], f"package {self.NEW}")
        self.assertEqual(out[f"ui/till/src/commonMain/{K}/Screen.kt"],
                         f"package {P}\n\nimport {self.NEW}.format\nimport {self.NEW}.*\nimport {self.OLD}.sub.Other\n")
        self.assertIn(f"import {self.NEW}.Money as M", out[f"pos-server/src/test/{K}/FormatTest.kt"])
        self.assertIn(f"{self.NEW}.Money::class", out["ui/till/build.gradle.kts"])

    def test_rename_package_rewrites_fqn_uses_and_kdoc_links(self):
        user = f"pos-server/src/main/{K}/Wiring.kt"
        files = {
            f"shared/src/commonMain/{K}/core/utils/Format.kt": f"package {self.OLD}\nobject Money\n",
            user: f"package {P}\n/** See [{self.OLD}.Money]. */\nval m = {self.OLD}.Money\nval s = \"{self.OLD}.Money\"\nval n = x{self.OLD}.Money\n",
        }
        out = ma.rename_package(files, self.OLD, self.NEW)
        self.assertEqual(out[user], f"package {P}\n/** See [{self.NEW}.Money]. */\nval m = {self.NEW}.Money\nval s = \"{self.OLD}.Money\"\nval n = x{self.OLD}.Money\n")
        self.assertEqual(ma.rename_package({user: "val x = 1\n"}, self.OLD, self.NEW), {})

    def test_rename_package_rewrites_string_templates_and_reports_plain_literals(self):
        user = f"pos-server/src/main/{K}/Wiring.kt"
        files = {f"shared/src/commonMain/{K}/core/utils/Format.kt": f"package {self.OLD}\nobject Money\n",
                 user: f"package {P}\nval t = \"v=${{{self.OLD}.Money.x}} $name\"\nval s = \"{self.OLD}.Money\"\n"}
        out = ma.rename_package(files, self.OLD, self.NEW)
        self.assertEqual(out[user], f"package {P}\nval t = \"v=${{{self.NEW}.Money.x}} $name\"\nval s = \"{self.OLD}.Money\"\n")
        self.assertEqual(ma.string_literal_mentions(out, self.OLD), [user])

    def test_rename_cli_is_dry_run_without_apply(self):
        options = ma.parse_args(["--rename-package", f"{self.OLD}={self.NEW}"])
        self.assertEqual((options.rename, options.apply), ((self.OLD, self.NEW), False))
        self.assertIsNone(ma.parse_args(["--rename-package", "nonsense"]))


class CheckJarsTest(unittest.TestCase):
    """`--check-jars` (Task 9): the same class file in two jars of one runtime classpath; shadowJar keeps one silently."""

    def test_no_duplicates_is_empty(self):
        listings = {"a.jar": ["org/x/A.class", "META-INF/MANIFEST.MF", "org/x/"],
                    "b.jar": ["org/x/B.class", "META-INF/MANIFEST.MF", "org/x/"]}
        self.assertEqual(ma.duplicate_classes(listings), {})

    def test_same_class_in_two_jars_is_reported_with_both_jars(self):
        listings = {"b.jar": ["org/x/FooKt.class", "org/x/Bar.class"],
                    "a.jar": ["org/x/FooKt.class"],
                    "c.jar": ["org/x/Bar$Inner.class"]}
        self.assertEqual(ma.duplicate_classes(listings), {"org/x/FooKt.class": ["a.jar", "b.jar"]})

    def test_metadata_resources_and_directories_are_not_classes(self):
        listings = {"a.jar": ["META-INF/MANIFEST.MF", "META-INF/main.kotlin_module", "module-info.class",
                              "META-INF/versions/9/module-info.class", "org/", "app.yaml"],
                    "b.jar": ["META-INF/MANIFEST.MF", "META-INF/main.kotlin_module", "module-info.class",
                              "META-INF/versions/9/module-info.class", "org/", "app.yaml"]}
        self.assertEqual(ma.duplicate_classes(listings), {})

    def test_a_class_listed_twice_in_one_jar_is_not_a_cross_jar_duplicate(self):
        self.assertEqual(ma.duplicate_classes({"a.jar": ["org/x/A.class", "org/x/A.class"]}), {})

    def test_cli_takes_every_remaining_argument_as_a_jar(self):
        options = ma.parse_args(["--check-jars", "a.jar", "b.jar"])
        self.assertEqual((options.check_jars, options.area), (("a.jar", "b.jar"), None))
        self.assertIsNone(ma.parse_args(["--check-jars"]))

    def test_cli_exit_code_and_output(self):
        listings = {"a.jar": ["org/x/A.class"], "b.jar": ["org/x/A.class"]}
        out = io.StringIO()
        with mock.patch.object(ma, "_jar_listing", side_effect=lambda jar: listings[jar]), contextlib.redirect_stdout(out):
            self.assertEqual(ma.main(["--check-jars", "a.jar", "b.jar"]), 1)
        self.assertIn("org/x/A.class\ta.jar, b.jar", out.getvalue())
        out = io.StringIO()
        with mock.patch.object(ma, "_jar_listing", side_effect=lambda jar: {"a.jar": ["org/x/A.class"]}[jar]), contextlib.redirect_stdout(out):
            self.assertEqual(ma.main(["--check-jars", "a.jar"]), 0)
        self.assertIn("no duplicate classes", out.getvalue())


if __name__ == "__main__":
    unittest.main()
