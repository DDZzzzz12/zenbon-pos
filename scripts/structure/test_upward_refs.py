import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = ROOT / "scripts/structure/upward_refs.py"
_spec = importlib.util.spec_from_file_location("upward_refs", _SCRIPT)
assert _spec is not None and _spec.loader is not None
ur = importlib.util.module_from_spec(_spec)
sys.modules["upward_refs"] = ur
_spec.loader.exec_module(ur)

TABLE = {"order": "ecr", "zBon": "fiscal", "tse": "fiscal", "auth": "platform", "document": "accounting"}
P = "pos-server/src/main/kotlin/org/example/project"


class UpwardRefsTest(unittest.TestCase):
    def test_a_deleted_blob_module_is_no_source_root(self):
        self.assertNotIn("core-data", ur.SOURCE_ROOTS)
        self.assertNotIn("shared", ur.SOURCE_ROOTS)

    def test_area_of_file_features_segment(self):
        self.assertEqual(ur.area_of_file(f"{P}/features/order/domain/A.kt", TABLE), "ecr")

    def test_area_of_file_dto_segment(self):
        path = "shared/src/commonMain/kotlin/org/example/project/core/domain/dto/zBon/X.kt"
        self.assertEqual(ur.area_of_file(path, TABLE), "fiscal")

    def test_files_without_segment_are_wiring_not_platform(self):
        self.assertEqual(ur.area_of_file(f"{P}/core/Util.kt", TABLE), "host/wiring")
        self.assertEqual(ur.area_of_file(f"{P}/features/auth/A.kt", TABLE), "platform")

    def test_wiring_refs_counted_separately(self):
        files = {
            f"{P}/infrastructure/di/Module.kt": "import org.example.project.features.order.X\n",
            f"{P}/features/auth/A.kt": "import org.example.project.features.order.X\n",
        }
        refs = ur.find_upward(files, TABLE)
        self.assertEqual(sorted((r.from_area, r.to_area) for r in refs), [("host/wiring", "ecr"), ("platform", "ecr")])
        report = ur.render_report(refs, {}, 2)
        self.assertIn("| host/wiring | ecr | 1 | 1 |", report)
        self.assertIn("| platform | ecr | 1 | 1 |", report)

    def test_report_has_caveats(self):
        report = ur.render_report([], {"settings": ["x.kt"]}, 0)
        for text in ("host/wiring", "EXCLUDED", "composeApp", "test sources"):
            self.assertIn(text, report)

    def test_wildcard_import_is_an_edge_to_every_file_of_the_package(self):
        pkg = "package org.example.project.core.utils\n"
        files = {f"{P}/features/order/S.kt": "import org.example.project.core.utils.*\n",
                 "shared/src/commonMain/kotlin/org/example/project/core/utils/Low.kt": pkg + "class Low\n",
                 "shared/src/commonMain/kotlin/org/example/project/core/utils/High.kt": pkg + "class High\n"}
        fo = {"core/utils/High.kt": "fiscal"}
        refs = ur.find_upward(files, TABLE, {}, fo)
        self.assertEqual([(r.from_area, r.to_area) for r in refs], [("ecr", "fiscal")])
        self.assertIn("High.kt", refs[0].imported_fqn)

    def test_find_upward_flags_ecr_importing_fiscal(self):
        files = {f"{P}/features/order/S.kt": "import org.example.project.features.zBon.domain.ZBonService\n"}
        refs = ur.find_upward(files, TABLE)
        self.assertEqual(len(refs), 1)
        self.assertEqual((refs[0].from_area, refs[0].to_area), ("ecr", "fiscal"))
        self.assertEqual(refs[0].imported_fqn, "org.example.project.features.zBon.domain.ZBonService")

    def test_find_upward_ignores_same_and_lower_area(self):
        files = {
            f"{P}/features/order/S.kt": "import org.example.project.features.order.X\nimport org.example.project.features.auth.Y\n",
        }
        self.assertEqual(ur.find_upward(files, TABLE), [])

    def test_unmapped_package_reported_not_dropped(self):
        files = {
            f"{P}/features/mystery/S.kt": "import org.example.project.features.other.Z\nimport org.example.project.features.order.O\n"
        }
        self.assertEqual(ur.find_upward(files, TABLE), [])
        self.assertEqual(set(ur.find_unmapped(files, TABLE)), {"mystery", "other"})

    def test_tse_root_package_is_fiscal(self):
        files = {f"{P}/features/order/S.kt": "import org.example.project.tse.utils.ProcessDataValidator\n"}
        refs = ur.find_upward(files, TABLE)
        self.assertEqual([(r.from_area, r.to_area) for r in refs], [("ecr", "fiscal")])


OV = {"tse.database.tables": "ecr", "tse.domain.models": "ecr", "zBon.domain.models": "ecr", "zBon.database.tables": "ecr"}


SH = "shared/src/commonMain/kotlin/org/example/project"


class SharedTypePackagesTest(unittest.TestCase):
    def test_enum_import_is_upward_when_feature_is_higher(self):
        files = {f"{P}/features/order/T.kt": "import org.example.project.core.domain.enums.tse.TseSigningStatus\n"}
        refs = ur.find_upward(files, TABLE)
        self.assertEqual([(r.from_area, r.to_area) for r in refs], [("ecr", "fiscal")])

    def test_models_import_is_upward_when_feature_is_higher(self):
        files = {f"{P}/features/order/T.kt": "import org.example.project.core.domain.models.zBon.X\n"}
        self.assertEqual([r.to_area for r in ur.find_upward(files, TABLE)], ["fiscal"])

    def test_enum_directory_importer_takes_the_feature_area(self):
        self.assertEqual(ur.area_of_file(f"{SH}/core/domain/enums/tse/TseState.kt", TABLE), "fiscal")
        files = {f"{SH}/core/domain/enums/tse/TseState.kt": "import org.example.project.core.domain.dto.document.D\n"}
        self.assertEqual([(r.from_area, r.to_area) for r in ur.find_upward(files, TABLE)], [("fiscal", "accounting")])

    def test_models_directory_importer_takes_the_feature_area(self):
        self.assertEqual(ur.area_of_file(f"{SH}/core/domain/models/order/M.kt", TABLE), "ecr")

    def test_enum_import_downward_or_unmapped_not_upward(self):
        files = {f"{P}/features/order/T.kt": "import org.example.project.core.domain.enums.auth.Role\nimport org.example.project.core.domain.enums.mystery.X\n"}
        self.assertEqual(ur.find_upward(files, TABLE), [])
        self.assertIn("mystery", ur.find_unmapped(files, TABLE))

    def test_file_directly_in_enums_dir_stays_wiring_and_other_models_dirs_ignored(self):
        self.assertEqual(ur.area_of_file(f"{SH}/core/domain/enums/Plain.kt", TABLE), ur.WIRING)
        self.assertEqual(ur.area_of_file(f"{SH}/core/data/models/order/M.kt", TABLE), ur.WIRING)


class OverridesTest(unittest.TestCase):
    def test_override_beats_feature_row(self):
        self.assertEqual(ur.package_area_for("tse.database.tables", TABLE, OV), "ecr")
        self.assertEqual(ur.package_area_for("tse.database.tables.TseTransactionTable", TABLE, OV), "ecr")
        self.assertEqual(ur.package_area_for("tse.domain.service", TABLE, OV), "fiscal")

    def test_longest_override_wins(self):
        ov = {"tse.database": "ecr", "tse.database.tables": "platform"}
        self.assertEqual(ur.package_area_for("tse.database.tables.X", TABLE, ov), "platform")
        self.assertEqual(ur.package_area_for("tse.database.other", TABLE, ov), "ecr")

    def test_unlisted_subpackage_uses_feature_row(self):
        self.assertEqual(ur.package_area_for("zBon.domain.service.S", TABLE, OV), "fiscal")
        self.assertEqual(ur.package_area_for("tse.databasex", TABLE, OV), "fiscal")
        self.assertIsNone(ur.package_area_for("nope.x", TABLE, OV))

    def test_override_unknown_area_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            ur.load_overrides("# c\ntse.database.tables\tecr\nzBon.x\tbogus\n")
        self.assertIn("line 3", str(ctx.exception))
        with self.assertRaises(ValueError) as ctx:
            ur.load_overrides("malformed\n")
        self.assertIn("line 1", str(ctx.exception))

    def test_load_overrides(self):
        self.assertEqual(ur.load_overrides("# c\n\ntse.database.tables\tecr\tnote\n"), {"tse.database.tables": "ecr"})

    def test_area_of_file_uses_override(self):
        path = f"{P}/features/tse/database/tables/TseTransactionTable.kt"
        self.assertEqual(ur.area_of_file(path, TABLE, OV), "ecr")
        self.assertEqual(ur.area_of_file(f"{P}/features/tse/domain/service/S.kt", TABLE, OV), "fiscal")

    def test_models_import_not_upward_service_import_is(self):
        files = {
            f"{P}/features/order/S.kt": (
                "import org.example.project.features.zBon.domain.models.ZBon\n"
                "import org.example.project.features.zBon.domain.service.ZBonService\n"
            ),
        }
        refs = ur.find_upward(files, TABLE, OV)
        self.assertEqual([r.imported_fqn for r in refs], ["org.example.project.features.zBon.domain.service.ZBonService"])

    def test_ecr_override_file_importing_fiscal_service_is_upward(self):
        files = {f"{P}/features/tse/domain/models/M.kt": "import org.example.project.features.tse.domain.service.S\n"}
        refs = ur.find_upward(files, TABLE, OV)
        self.assertEqual([(r.from_area, r.to_area) for r in refs], [("ecr", "fiscal")])

    def test_file_override_beats_package_rule(self):
        path = "shared/src/commonMain/kotlin/org/example/project/core/domain/enums/tse/TseSigningStatus.kt"
        self.assertEqual(ur.area_of_file(path, TABLE), "fiscal")
        self.assertEqual(ur.file_area(path, "fiscal", {"core/domain/enums/tse/TseSigningStatus.kt": "ecr"}), "ecr")
        self.assertEqual(ur.file_area(path, "fiscal", {}), "fiscal")
        # the importing side: an overridden fiscal-package file counts as ecr, so its fiscal import is upward again only past ecr
        files = {path: "import org.example.project.core.domain.dto.zBon.Z\nimport org.example.project.core.domain.dto.order.O\n"}
        refs = ur.find_upward(files, TABLE, None, {"core/domain/enums/tse/TseSigningStatus.kt": "ecr"})
        self.assertEqual([(r.from_area, r.to_area) for r in refs], [("ecr", "fiscal")])

    def test_import_resolves_to_file_override(self):
        shared = "shared/src/commonMain/kotlin/org/example/project/core/domain/enums/tse"
        files = {
            f"{P}/features/order/domain/A.kt": "import org.example.project.core.domain.enums.tse.TseSigningStatus\nimport org.example.project.core.domain.enums.tse.TseState\n",
            f"{shared}/TseSigningStatus.kt": "package x\n", f"{shared}/TseState.kt": "package x\n",
        }
        fo = {"core/domain/enums/tse/TseSigningStatus.kt": "ecr"}
        self.assertEqual(ur.import_file("org.example.project.core.domain.enums.tse.TseSigningStatus.Inner"), "core/domain/enums/tse/TseSigningStatus.kt")
        self.assertIsNone(ur.import_file("org.example.project.core.utils.helper"))
        refs = ur.find_upward(files, TABLE, None, fo)
        self.assertEqual([r.imported_fqn.rsplit(".", 1)[1] for r in refs], ["TseState"])  # the overridden type is no longer upward
        # no file with that name in the tree: falls back to the package rule
        refs = ur.find_upward({k: v for k, v in files.items() if "Status.kt" not in k}, TABLE, None, fo)
        self.assertEqual([(r.from_area, r.to_area, r.imported_fqn.rsplit(".", 1)[1]) for r in refs],
                         [("ecr", "fiscal", "TseSigningStatus"), ("ecr", "fiscal", "TseState")])

    def test_missing_file_override_path_is_reported(self):
        files = {f"{P}/core/domain/enums/tse/A.kt": ""}
        problems = ur.validate_file_overrides({"core/domain/enums/tse/A.kt": "ecr", "core/domain/enums/tse/Gone.kt": "ecr"}, files)
        self.assertEqual(len(problems), 1)
        self.assertIn("Gone.kt", problems[0])
        with self.assertRaises(ValueError):
            ur.load_file_overrides("a/B.kt\tnowhere\n")
        with self.assertRaises(ValueError):
            ur.load_file_overrides("no tab here\n")
        self.assertEqual(ur.load_file_overrides("# c\n\na/B.kt\tecr\n"), {"a/B.kt": "ecr"})


if __name__ == "__main__":
    unittest.main()
