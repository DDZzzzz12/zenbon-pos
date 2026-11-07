import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = ROOT / "scripts/structure/move_manifest.py"
_spec = importlib.util.spec_from_file_location("move_manifest", _SCRIPT)
assert _spec is not None and _spec.loader is not None
mm = importlib.util.module_from_spec(_spec)
sys.modules["move_manifest"] = mm
_spec.loader.exec_module(mm)

AREAS = {
    "order": "ecr", "bill": "ecr", "zBon": "fiscal", "tse": "fiscal", "auth": "platform",
    "document": "accounting",
}
OVERRIDES = {"zBon.database.tables": "ecr", "zBon.domain.models": "ecr"}
BASE = "org/example/project"


def kt(module: str, source_set: str, directory: str, name: str = "A.kt") -> str:
    return f"{module}/src/{source_set}/kotlin/{BASE}/{directory}/{name}"


def pkg_line(directory: str) -> str:
    return "package org.example.project." + directory.replace("/", ".") + "\n"


def manifest(*entries: tuple[str, str, str]):
    files = {kt(m, s, d): pkg_line(d) for m, s, d in entries}
    return mm.build_manifest(files, AREAS, OVERRIDES)


class MoveManifestTest(unittest.TestCase):
    def test_core_data_package_goes_to_data_module(self):
        rows = manifest(("core-data", "main", "features/bill/database/tables"))
        self.assertEqual(rows, [("core-data", "main", "features.bill.database.tables", "ecr-data", 1)])

    def test_shared_dto_goes_to_api_module_keeping_source_set(self):
        rows = manifest(("shared", "commonMain", "core/domain/dto/order"))
        self.assertEqual(rows, [("shared", "commonMain", "core.domain.dto.order", "ecr-api", 1)])

    def test_shared_non_feature_goes_to_platform_api(self):
        rows = manifest(("shared", "jvmMain", "core/utils"), ("shared", "commonMain", "core/network"))
        self.assertEqual({r[3] for r in rows}, {"platform-api"})
        self.assertEqual({r[1] for r in rows}, {"jvmMain", "commonMain"})

    def test_pos_server_feature_goes_to_server_module(self):
        rows = manifest(("pos-server", "main", "features/order/domain"))
        self.assertEqual(rows[0][3], "ecr-server")

    def test_pos_server_infrastructure_is_host(self):
        rows = manifest(("pos-server", "main", "infrastructure/di"), ("pos-server", "main", "infrastructure/security/rules"))
        self.assertEqual({r[3] for r in rows}, {"host"})

    def test_pos_server_core_and_utils_are_platform_server(self):
        rows = manifest(("pos-server", "main", "core/config"), ("pos-server", "main", "utils/formatter"))
        self.assertEqual({r[3] for r in rows}, {"platform-server"})

    def test_override_package_goes_to_data_of_ecr(self):
        rows = manifest(
            ("core-data", "main", "features/zBon/database/tables"),
            ("core-data", "main", "features/zBon/domain/models"),
            ("pos-server", "main", "features/zBon/data/service"),
        )
        self.assertEqual([r[3] for r in rows], ["ecr-data", "ecr-data", "fiscal-server"])

    def test_package_line_wins_over_path_and_path_is_the_fallback(self):
        files = {
            kt("core-data", "main", "features/bill/x", "A.kt"): "package org.example.project.features.order.service\n",
            kt("core-data", "main", "features/bill/domain", "B.kt"): "// no package line\n",
        }
        rows = mm.build_manifest(files, AREAS, OVERRIDES)
        self.assertEqual(sorted((r[2], r[3]) for r in rows),
                         [("features.bill.domain", "ecr-data"), ("features.order.service", "ecr-data")])

    def test_file_count_and_test_source_sets(self):
        files = {kt("pos-server", "test", "features/order/domain", f"T{i}.kt"): pkg_line("features/order/domain") for i in range(3)}
        rows = mm.build_manifest(files, AREAS, OVERRIDES)
        self.assertEqual(rows, [("pos-server", "test", "features.order.domain", "ecr-server", 3)])
        self.assertTrue(mm.is_test("test") and mm.is_test("commonTest") and mm.is_test("jvmTest"))
        self.assertFalse(mm.is_test("main") or mm.is_test("commonMain"))

    def test_unmapped_package_is_reported_and_fails_cli(self):
        rows = manifest(("core-data", "main", "features/settings/domain"))
        self.assertEqual(rows[0][3], "UNMAPPED")
        self.assertEqual(mm.unmapped(rows), rows)
        self.assertEqual(mm.exit_code(rows, []), 1)

    def test_same_package_two_targets_fails(self):
        rows = [
            ("pos-server", "main", "features.order.domain", "ecr-server", 1),
            ("pos-server", "main", "features.order.domain", "fiscal-server", 1),
        ]
        self.assertEqual(list(mm.conflicting_targets(rows)), ["pos-server/main/features.order.domain"])
        self.assertEqual(mm.exit_code(rows, []), 1)

    def test_clean_manifest_exits_zero(self):
        rows = manifest(("core-data", "main", "features/bill/domain"))
        self.assertEqual(mm.exit_code(rows, []), 0)
        self.assertEqual(mm.exit_code(rows, ["override problem"]), 1)

    def test_split_packages_listed(self):
        rows = manifest(
            ("core-data", "main", "core/config"), ("pos-server", "main", "core/config"),
            ("shared", "commonTest", "testutils"), ("pos-server", "test", "testutils"),
            ("core-data", "main", "features/bill"), ("pos-server", "main", "features/bill"),
            ("pos-server", "main", "features/order/domain"),
            ("shared", "commonMain", "core/utils"), ("shared", "jvmMain", "core/utils"),
        )
        split = mm.split_packages(rows)
        self.assertEqual(sorted(split), ["core.config", "features.bill", "testutils"])
        self.assertEqual({r[0] for r in split["core.config"]}, {"core-data", "pos-server"})

    def test_override_with_unknown_feature_reported(self):
        problems = mm.validate_overrides("ghost.database.tables\tecr\nzBon.domain.models\tecr\n", AREAS)
        self.assertEqual(len(problems), 1)
        self.assertIn("ghost", problems[0])

    def test_duplicate_override_key_reported(self):
        problems = mm.validate_overrides("zBon.domain.models\tecr\nzBon.domain.models\tfiscal\n", AREAS)
        self.assertEqual(len(problems), 1)
        self.assertIn("zBon.domain.models", problems[0])

    def test_valid_overrides_have_no_problems(self):
        self.assertEqual(mm.validate_overrides("# c\nzBon.domain.models\tecr\n", AREAS), [])

    def test_mixed_package_detected_by_filename_features(self):
        names = ["TseSettingsApiClient.kt", "KtorTseSettingsApiClient.kt", "OrderApiClient.kt", "ZBonApiClient.kt", "Helper.kt"]
        files = {kt("shared", "commonMain", "core/data/remote", n): pkg_line("core/data/remote") for n in names}
        mixed = mm.mixed_packages(files, AREAS, OVERRIDES)
        self.assertEqual(len(mixed), 1)
        entry = mixed[0]
        self.assertEqual((entry.module, entry.package, entry.target, entry.file_count), ("shared", "core.data.remote", "platform-api", 5))
        self.assertEqual(entry.areas, {"fiscal": 3, "ecr": 1, "platform/unknown": 1})

    def test_mixed_area_ties_are_ordered_by_name(self):
        for names in (["ZBonApiClient.kt", "OrderApiClient.kt"], ["OrderApiClient.kt", "ZBonApiClient.kt"]):
            files = {kt("shared", "commonMain", "core/data/remote", n): pkg_line("core/data/remote") for n in names}
            self.assertEqual(list(mm.mixed_packages(files, AREAS, OVERRIDES)[0].areas), ["ecr", "fiscal"])

    def test_single_feature_package_not_mixed(self):
        feature = {kt("core-data", "main", "features/bill/domain", n): pkg_line("features/bill/domain") for n in ("BillOrderLink.kt", "TseThing.kt")}
        one_area = {kt("shared", "commonMain", "core/network", n): pkg_line("core/network") for n in ("OrderClient.kt", "BillClient.kt", "Plain.kt")}
        self.assertEqual(mm.mixed_packages({**feature, **one_area}, AREAS, OVERRIDES), [])

    def test_explicit_aggregate_package_is_mixed_even_with_plain_names(self):
        files = {kt("shared", "commonMain", "core/utils/enumStrings", "Plain.kt"): pkg_line("core/utils/enumStrings")}
        self.assertEqual([m.package for m in mm.mixed_packages(files, AREAS, OVERRIDES)], ["core.utils.enumStrings"])

    def test_report_has_mixed_packages_section_and_provisional_test_note(self):
        files = {kt("shared", "commonMain", "core/data/remote", n): pkg_line("core/data/remote") for n in ("TseApiClient.kt", "OrderApiClient.kt")}
        files[kt("pos-server", "test", "routes", "R.kt")] = pkg_line("routes")
        rows = mm.build_manifest(files, AREAS, OVERRIDES)
        report = mm.render_report(rows, [], AREAS, mm.mixed_packages(files, AREAS, OVERRIDES))
        self.assertIn("## Mixed packages - file-level handling required", report)
        self.assertIn("a package-level move would place 2 files in platform-api; the mover must split by file", report)
        self.assertIn("PROVISIONAL", report)
        self.assertIn("filename-heuristic review worklist, not a verdict", report)

    @unittest.skipUnless((ROOT / "pos-server/src").is_dir(),
                         "module sources absent: cannot pin the split-package set")
    def test_real_tree_split_packages_are_exactly_the_known_set(self):
        areas = mm.ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
        overrides = mm.ur.load_overrides((ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8"))
        rows = mm.build_manifest(mm.collect_files(), areas, overrides)
        self.assertEqual([r for r in rows if r[0] == "core-data"], [], "core-data was dissolved in B2-5 C2: no file may map from it")
        self.assertEqual([r for r in rows if r[0] == "shared"], [], "shared was dissolved in B2-5 C3: no file may map from it")
        # Of the sixteen packages split across the blobs before any move, six were untouched by the platform extraction
        # (their halves are ecr/fiscal/accounting files in residual core-data, shared and pos-server). The ecr server move
        # (Stage B2-2) settled three of them (features.bill, order/table domain.service: see ecr_server_splits); three are left.
        # The fiscal server move (Stage B2-3) settled a fourth, features.kassenbuch.domain (see fiscal_server_splits).
        # The accounting server move (Stage B2-4) settled the last two (features.salesinvoice.domain.service, features.issuereport.data.remote):
        # see accounting_server_splits.
        residual_blobs: list[str] = []
        # Stage B2-1b Task 7 moved the platform files of the mixed shared packages (placed file by file) to
        # platform/api. The residual shared is gone (B2-5 C3 re-homed its last sixteen tests to the area api modules),
        # so each remaining split here is between area api modules.
        # Stage B2-2 (ecr-api move) then moved the ecr files of those and four more shared packages to ecr/api, so the
        # set of modules per package is pinned explicitly.
        shared_residual_vs_api = {
            "core.data.remote": {"platform-api", "ecr-api", "fiscal-api", "accounting-api", "backoffice-api"},
            "core.data.remote.impl": {"platform-api", "ecr-api", "fiscal-api", "accounting-api", "backoffice-api"},
            "core.domain.dto.rotation": {"platform-api", "ecr-api"},
            "core.domain.dto.tse": {"platform-api", "ecr-api", "fiscal-api"},
            "core.domain.enums.datev": {"ecr-api", "accounting-api"},
            "core.domain.enums.kassenbuch": {"ecr-api", "fiscal-api", "accounting-api"},
            "core.domain.enums.setup": {"platform-api", "fiscal-api"},
            "core.domain.enums.tse": {"platform-api", "ecr-api", "fiscal-api"},
            "core.domain.enums.zBon": {"ecr-api", "fiscal-api"},
            "core.domain.models.setup": {"platform-api", "ecr-api"},
            "core.network": {"platform-api", "accounting-api"},
            "core.utils": {"platform-api", "ecr-api", "fiscal-api", "backoffice-api"},
            "core.utils.datev": {"platform-api", "accounting-api"},
            "core.utils.enumStrings": {"platform-api", "ecr-api", "fiscal-api", "accounting-api"},
            # Stage B2-3 (fiscal-api move): fiscal files left these shared packages;
            # Stage B2-4 (accounting-api move) then moved the accounting halves of the three DATEV/document packages;
            # Stage B2-5 (backoffice-api move) did the same for the statistics package
            "core.domain.dto.datev": {"accounting-api", "fiscal-api"},
            "core.domain.dto.datevexportlog": {"accounting-api", "fiscal-api"},
            "core.domain.dto.document": {"accounting-api", "fiscal-api"},
            "core.domain.dto.statistics": {"backoffice-api", "fiscal-api"}}
        shared_residual_vs_platform_api = list(shared_residual_vs_api)
        # Stage B2-4 (accounting-api move): features.issuereport.data.remote (a residual blob) is now split accounting-api/pos-server.
        accounting_api_splits: dict[str, set[str]] = {}
        # Stage B2-4 (accounting-server move): the accounting routes, services and infrastructure left pos-server for
        # accounting/server, 44 tests with them; the host fixtures (`test-stays`) keep a pos-server half of nine test-only
        # packages. The two former residual blobs are now same-area splits (accounting-data/-api beside accounting-server).
        accounting_server_splits = {
            "features.salesinvoice.domain.service": {"accounting-data", "accounting-server"},
            "features.issuereport.data.remote": {"accounting-api", "accounting-server"},
            "features.scheduler.data.service.executors": {"platform-server", "accounting-server"},
            "features.allocation": {"accounting-server", "pos-server"},
            "features.bank": {"accounting-server", "pos-server"},
            "features.contract": {"accounting-server", "pos-server"},
            "features.datevexportlog": {"accounting-server", "pos-server"},
            "features.document": {"accounting-server", "pos-server"},
            "features.issuereport": {"accounting-server", "pos-server"},
            "features.matching": {"accounting-server", "pos-server"},
            "features.salesinvoice": {"accounting-server", "pos-server"},
            "features.supplier": {"accounting-server", "pos-server"}}
        accounting_test_only_splits = [
            "features.allocation", "features.bank", "features.contract", "features.datevexportlog", "features.document",
            "features.issuereport", "features.matching", "features.salesinvoice", "features.supplier"]
        # Stage B2-2 (ecr-data move): core-data's ecr files left for ecr/data and so did eight file-placed pos-server files,
        # so these packages now also hold ecr-data (`VatRepository` replaces the core-data half of setup.domain.repository;
        # the pos-server halves are the files that stay, or the tests that stay, `test-stays`).
        ecr_data_splits = {
            "features.setup.domain.repository": {"platform-data", "ecr-data"},
            "core.mapper.websocket": {"ecr-data", "pos-server"},
            "utils.builder.tse": {"accounting-server", "ecr-data", "fiscal-server", "pos-server"},
            "utils.builder": {"ecr-data", "platform-server", "pos-server"},
            "utils.formatter": {"ecr-data", "platform-server", "pos-server"}}
        # The nine former same-area platform blockers, resolved by Task 9. Six are split between two platform tiers
        # only; core.config and utils also keep accounting/fiscal/ecr files in pos-server (rows whose files are all
        # file-placed elsewhere, so not pending): those eight are "Split packages (settled)" in the blockers report.
        # utils.config never held a platform file in these modules (YamlConfig is in platform-server-common): what is
        # left is core-data's VatMappingUtil (ecr-data) and pos-server's VoucherConfigUtil (ecr-server), both
        # file-placed, so it is no platform split at all.
        settled = {
            "features.auth.data": {"platform-data", "platform-server"},
            "features.auth.domain.service": {"platform-data", "platform-server"},
            "features.backup.domain.service": {"platform-data", "platform-server"},
            "features.setup.domain.service": {"platform-data", "platform-server"},
            "features.terminal.domain.service": {"platform-data", "platform-server"},
            "features.update.data.remote": {"platform-api", "platform-server"},
            "core.config": {"accounting-server", "platform-data", "platform-server", "pos-server"},
            "utils": {"ecr-data", "fiscal-server", "platform-data", "platform-server", "pos-server"}}
        # Stage B2-2 (ecr-server move): the ecr server files left pos-server for ecr/server. The two C12 same-area splits
        # (order/table domain.service) and utils.config (VatMappingUtil in ecr-data, VoucherConfigUtil in ecr-server) are
        # now split between two ecr tiers only ("Split packages (settled)"); features.bill keeps its data files in ecr-data
        # and its tests partly in pos-server (`test-stays`), as do the test-only splits features.order and features.pfand.
        # The four setup packages hold ecr files beside the platform ones (and, for data.service/domain.routes, the
        # fiscal/accounting/backoffice files that stay in pos-server).
        ecr_server_splits = {
            "features.order.domain.service": {"ecr-data", "ecr-server"},
            "features.table.domain.service": {"ecr-data", "ecr-server"},
            "utils.config": {"ecr-data", "ecr-server"},
            "features.bill": {"ecr-data", "ecr-server", "pos-server"},
            "features.order": {"ecr-server", "pos-server"},
            "features.pfand": {"ecr-server", "pos-server"},
            "features.bill.mapper": {"ecr-data", "ecr-server"},
            "features.bill.data.service": {"ecr-data", "ecr-server"},
            "features.setup.data.repository": {"ecr-server", "platform-server"},
            "features.setup.mapper": {"ecr-server", "platform-server"},
            "features.setup.data.service": {"ecr-server", "fiscal-server", "platform-server", "pos-server"},
            "features.setup.domain.routes": {"accounting-server", "backoffice-server", "ecr-server", "fiscal-server", "platform-server"}}
        # Stage B2-3 (fiscal-data move): the five fiscal Kassenbuch services left core-data; the DATEV export services beside
        # them are accounting files (file placements A§0.2); the accounting data move (B2-4) took them to accounting-data.
        fiscal_data_splits = {"features.kassenbuch.domain.service": {"accounting-data", "fiscal-data"}}
        # Stage B2-3 (fiscal-server move): the fiscal routes, services and TSE/DSFinV-K/Z-Bon code left pos-server for
        # fiscal/server, 44 tests with them; 204 tests stay (`test-stays`: host fixtures, resources), so each test package
        # also keeps a pos-server half. features.kassenbuch.domain is a same-area fiscal-data/fiscal-server split (settled).
        # The accounting files (Kassenbuch data service, cash-book routes) and the host's printer formatter tests stay in
        # pos-server; the four setup packages and utils hold fiscal-server files beside the platform/ecr ones.
        fiscal_server_splits = {
            "features.kassenbuch.domain": {"fiscal-data", "fiscal-server"},
            "features.kassenbuch.data.service": {"accounting-server", "fiscal-server"},
            "features.kassenbuch.domain.routes": {"accounting-server", "fiscal-server"},
            "features.tse.findings": {"fiscal-server", "pos-server"},
            "features.tse.verification": {"fiscal-server", "pos-server"},
            "printer.core.formatter": {"fiscal-server", "pos-server"},
            "export.dsfinvk": {"fiscal-server", "pos-server"},
            "features.dsfinvk": {"fiscal-server", "pos-server"},
            "features.kassenbuch": {"fiscal-server", "pos-server"},
            "features.monthlyclose": {"fiscal-server", "pos-server"},
            "features.monthlyclose.checks": {"fiscal-server", "pos-server"},
            "features.tse": {"fiscal-server", "pos-server"},
            "features.zBon": {"fiscal-server", "pos-server"}}
        fiscal_test_only_splits = [
            "export.dsfinvk", "features.dsfinvk", "features.kassenbuch", "features.monthlyclose",
            "features.monthlyclose.checks", "features.tse", "features.zBon"]
        # Host test fixtures stay in pos-server (open decision 1); platform-api has its own commonTest testutils.
        testutils = ["testutils"]
        # Task 9 itself: packages whose platform files moved to platform/server while files of the same package stay in
        # pos-server, as host code (Koin modules, plugins, security, schema: file placements A§0.2) or other-area files ...
        platform_server_vs_pos_server_main = [
            "infrastructure.di", "infrastructure.plugins", "infrastructure.security"]
        # Stage B2-5 (backoffice-server move): the statistics, custom-export and backoffice configuration routes left pos-server
        # for backoffice/server, 16 tests with them; the host-fixture tests (`test-stays`) keep a pos-server half of two
        # test-only packages.
        backoffice_server_splits = {"features.customexport": {"backoffice-server", "pos-server"},
                                    "features.statistics": {"backoffice-server", "pos-server"}}
        backoffice_test_only_splits = ["features.customexport", "features.statistics"]
        # Stage B2-5 A4 (G3): the managed table list and the cross-area FK declarations moved below the host to
        # backoffice-data (placement rows; core-data until the B3 extraction), so the custom-export schema graph can read them; the DDL stays in
        # pos-server and the Flyway/data-source files in platform-server.
        backoffice_data_splits = {"database": {"backoffice-data", "platform-server", "pos-server"},
                                 # Stage B2-5 (backoffice-data move): the time-tracking tables left core-data; platform-data keeps one
                                 "features.timetracking.database.tables": {"backoffice-data", "platform-data"}}
        # Stage B2-5 C4: each of the 46 route rules (one RuleModule object per feature) lives in the server tier of the area
        # that owns the feature, all in the one package infrastructure.security.rules; RoutePermissions stays in the host.
        route_rules_splits = {"infrastructure.security.rules": {
            "platform-server", "ecr-server", "fiscal-server", "accounting-server", "backoffice-server"}}
        # ... and test packages whose tests partly stay in pos-server (`test-stays`: host fixtures, host resources).
        platform_server_vs_pos_server_tests = [
            "core.security", "core.websocket", "features.auth", "features.backup", "features.maintenance",
            "features.scheduler", "features.startpage", "features.terminal", "features.update"]
        split = mm.split_packages(rows)
        self.assertEqual(sorted(split), sorted(residual_blobs + shared_residual_vs_platform_api
                                               + list(settled) + list(ecr_data_splits) + list(ecr_server_splits) + list(fiscal_data_splits) + list(fiscal_server_splits) + list(accounting_server_splits) + list(backoffice_data_splits) + list(backoffice_server_splits) + list(route_rules_splits) + testutils
                                               + platform_server_vs_pos_server_main
                                               + platform_server_vs_pos_server_tests))
        for package, modules in shared_residual_vs_api.items():
            self.assertEqual({r[0] for r in split[package]}, modules, package)
        for package, modules in {**ecr_data_splits, **settled, **ecr_server_splits, **fiscal_data_splits, **fiscal_server_splits, **accounting_api_splits, **accounting_server_splits, **backoffice_data_splits, **backoffice_server_splits, **route_rules_splits}.items():
            self.assertEqual({r[0] for r in split[package]}, modules, package)
        for package in platform_server_vs_pos_server_main + platform_server_vs_pos_server_tests:
            self.assertEqual({r[0] for r in split[package]}, {"platform-server", "pos-server"}, package)
        for package in platform_server_vs_pos_server_tests:
            self.assertEqual({r[1] for r in split[package] if r[0] == "pos-server"}, {"test"}, package)
        for package in ("features.bill", "features.order", "features.pfand"):
            self.assertEqual({r[1] for r in split[package] if r[0] in ("ecr-server", "pos-server")}, {"test"}, package)
        for package in backoffice_test_only_splits:
            self.assertEqual({r[1] for r in split[package] if r[0] in ("backoffice-server", "pos-server")}, {"test"}, package)
        for package in accounting_test_only_splits:
            self.assertEqual({r[1] for r in split[package] if r[0] in ("accounting-server", "pos-server")}, {"test"}, package)
        for package in fiscal_test_only_splits:
            self.assertEqual({r[1] for r in split[package] if r[0] in ("fiscal-server", "pos-server")}, {"test"}, package)

    def test_manifest_lists_file_rows_and_marks_package_mixed(self):
        base = "shared/src/commonMain/kotlin/org/example/project/core/domain/enums/tse"
        files = {f"{base}/Status.kt": "package org.example.project.core.domain.enums.tse\n",
                 f"{base}/Admin.kt": "package org.example.project.core.domain.enums.tse\n"}
        fo = {"core/domain/enums/tse/Status.kt": "ecr"}
        rows = mm.build_manifest(files, AREAS, OVERRIDES)
        self.assertEqual(rows, [("shared", "commonMain", "core.domain.enums.tse", "fiscal-api", 2)])
        frows = mm.file_rows(files, AREAS, OVERRIDES, fo)
        self.assertEqual([(r.package, r.target, r.path) for r in frows], [("core.domain.enums.tse", "ecr-api", "core/domain/enums/tse/Status.kt")])
        mixed = mm.mixed_packages(files, AREAS, OVERRIDES, fo)
        self.assertEqual([m.package for m in mixed], ["core.domain.enums.tse"])
        self.assertEqual(mm.mixed_packages(files, AREAS, OVERRIDES), [])
        self.assertIn("\tfile\tcore/domain/enums/tse/Status.kt", mm.render_tsv(rows, frows))
        self.assertEqual(mm.exit_code(rows, []), 0)  # the relaxed invariant: file rows are not a second package target
        report = mm.render_report(rows, [], AREAS, mixed, frows)
        self.assertIn("same-package references carry no import statement", report)
        self.assertNotIn("same-package references carry no import statement", mm.render_report(rows, [], AREAS, [], []))
        # an override equal to the package target adds no row
        self.assertEqual(mm.file_rows(files, AREAS, OVERRIDES, {"core/domain/enums/tse/Admin.kt": "fiscal"}), [])

    def test_manifest_omits_package_row_whose_files_are_all_file_placed(self):
        # the stale `utils.config -> platform-*` rows: their only file is placed into ecr by a file row
        placed = kt("core-data", "main", "utils/config", "VatMappingUtil.kt")
        kept = kt("core-data", "main", "utils/money", "Rounding.kt")
        partly = [kt("pos-server", "main", "utils/config", n) for n in ("VoucherConfigUtil.kt", "PortConfig.kt")]
        files = {placed: pkg_line("utils/config"), kept: pkg_line("utils/money"), **{p: pkg_line("utils/config") for p in partly}}
        fo = {"utils/config/VatMappingUtil.kt": "ecr"}
        rows = mm.build_manifest(files, AREAS, OVERRIDES)
        frows = mm.file_rows(files, AREAS, OVERRIDES, fo)
        self.assertEqual(mm.fully_placed_rows(rows, frows), frozenset({("core-data", "main", "utils.config")}))
        kept_rows = mm.omit_fully_placed(rows, frows)
        self.assertEqual(kept_rows, [("core-data", "main", "utils.money", "platform-data", 1),
                                     ("pos-server", "main", "utils.config", "platform-server", 2)])
        tsv = mm.render_tsv(kept_rows, frows)
        self.assertNotIn("core-data\tmain\tutils.config\tplatform-data", tsv)
        self.assertIn("core-data\tmain\tutils.config\tecr-data\t1\tfile\tutils/config/VatMappingUtil.kt", tsv)
        # pos-server's row keeps PortConfig.kt, so it stays even when VoucherConfigUtil is placed too
        frows2 = mm.file_rows(files, AREAS, OVERRIDES, {**fo, "utils/config/VoucherConfigUtil.kt": "ecr"})
        self.assertIn(("pos-server", "main", "utils.config", "platform-server", 2), mm.omit_fully_placed(rows, frows2))

    def test_file_in_platform_server_dir_targets_itself(self):
        rows = manifest(("platform/server", "main", "core/utils"), ("platform/api", "commonMain", "core/domain/dto/order"),
                        ("platform/data", "test", "features/bill/database/tables"))
        self.assertEqual(rows, [("platform-api", "commonMain", "core.domain.dto.order", "platform-api", 1),
                                ("platform-data", "test", "features.bill.database.tables", "platform-data", 1),
                                ("platform-server", "main", "core.utils", "platform-server", 1)])
        self.assertEqual(mm.exit_code(rows, []), 0)

    def test_moved_module_is_settled_not_mixed_nor_borderline(self):
        files = {kt("platform/api", "commonMain", "core/data/remote", f"{n}.kt"): pkg_line("core/data/remote")
                 for n in ("KtorOrderApiClient", "KtorTseApiClient")}
        self.assertEqual(mm.mixed_packages(files, AREAS, OVERRIDES), [])
        rows = mm.build_manifest(files, AREAS, OVERRIDES)
        self.assertFalse(mm.is_borderline(rows[0], AREAS))

    def test_a_deleted_blob_module_contributes_no_files_and_no_moves(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "pos-server/src/main/kotlin/org/example/project/features/auth/A.kt"
            target.parent.mkdir(parents=True)
            target.write_text(pkg_line("features/auth"), encoding="utf-8")
            saved = mm.ROOT
            mm.ROOT = root  # core-data/ and shared/ do not exist here: the roots are skipped, not errors
            try:
                files = mm.collect_files()
            finally:
                mm.ROOT = saved
        self.assertEqual(list(files), ["pos-server/src/main/kotlin/org/example/project/features/auth/A.kt"])
        self.assertEqual({r[0] for r in mm.build_manifest(files, AREAS, OVERRIDES)}, {"pos-server"})

    def test_collect_files_scans_module_dirs_roots(self):
        self.assertEqual(mm.source_roots(), ["shared", "core-data", "pos-server", "accounting/api", "accounting/data", "accounting/server", "backoffice/api", "backoffice/data", "backoffice/server",
                                             "ecr/api", "ecr/data", "ecr/server", "fiscal/api", "fiscal/data", "fiscal/server", "platform/api", "platform/data", "platform/server"])

    def test_package_of_names_in_place_module_by_gradle_name(self):
        path = kt("platform/api", "wasmJsMain", "core/utils")
        self.assertEqual(mm.package_of(path, pkg_line("core/utils")), ("platform-api", "wasmJsMain", "core.utils"))

    def test_package_of_ignores_modules_outside_the_manifest(self):
        for module in ("ui/till", "ecr/printer", "platform/audit", "pos-tse"):
            self.assertIsNone(mm.package_of(kt(module, "commonMain", "core/utils"), pkg_line("core/utils")), module)

    def test_module_dirs_are_injectable(self):
        text = ":x-api\tx/api\tshared\tcommonMain\n"
        path = kt("x/api", "commonMain", "core/utils")
        self.assertIsNone(mm.package_of(path, pkg_line("core/utils")))
        self.assertEqual(mm.package_of(path, pkg_line("core/utils"), mm.parse_in_place(text)), ("x-api", "commonMain", "core.utils"))
        self.assertEqual(mm.target_for("x-api", "core.utils", AREAS, OVERRIDES, mm.parse_in_place(text)), "x-api")
        with mm.use_module_dirs(text):
            self.assertEqual(mm.build_manifest({path: pkg_line("core/utils")}, AREAS, OVERRIDES),
                             [("x-api", "commonMain", "core.utils", "x-api", 1)])
            self.assertEqual(mm.in_place_of("shared"), ["x-api"])
        self.assertIsNone(mm.package_of(path, pkg_line("core/utils")))  # the override is gone again

    def test_overridden_file_in_a_settled_module_is_a_pending_file_row(self):
        path = kt("platform/api", "commonMain", "core/domain/enums/tse", "Status.kt")
        files = {path: "package org.example.project.core.domain.enums.tse\n"}
        self.assertEqual(mm.file_rows(files, AREAS, OVERRIDES, {}), [])
        self.assertEqual(mm.file_rows(files, AREAS, OVERRIDES, {"core/domain/enums/tse/Status.kt": "platform"}), [])  # equal to the module: settled
        rows = mm.file_rows(files, AREAS, OVERRIDES, {"core/domain/enums/tse/Status.kt": "ecr"})  # now belongs elsewhere: a move out
        self.assertEqual([(r.module, r.target) for r in rows], [("platform-api", "ecr-api")])


if __name__ == "__main__":
    unittest.main()
