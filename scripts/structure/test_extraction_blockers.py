import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extraction_blockers as eb  # noqa: E402
import upward_refs as ur  # noqa: E402

P = "org.example.project."
MANIFEST = [
    ("pos-server", "main", "features.order.domain.service", "ecr-server", 1),
    ("pos-server", "main", "features.tse.domain.service", "fiscal-server", 1),
    ("pos-server", "main", "features.tse.database.tables", "ecr-server", 1),
    ("pos-server", "main", "features.kassenbuch.domain", "fiscal-server", 1),
    ("core-data", "main", "features.kassenbuch.domain", "fiscal-data", 1),
    ("pos-server", "test", "features.order.domain.service", "ecr-server", 1),
]
ORDER_FILE = "pos-server/src/main/kotlin/org/example/project/features/order/domain/service/OrderService.kt"


def ref(fqn: str, to_area: str = "fiscal", from_area: str = "ecr", path: str = ORDER_FILE) -> ur.Ref:
    return ur.Ref(path, from_area, fqn, to_area)


class ExtractionBlockersTest(unittest.TestCase):
    def test_data_import_classified_upward_data(self):
        found = eb.blockers(MANIFEST, [ref(P + "features.tse.database.tables.TseTransactionTable")])
        self.assertEqual(found["ecr"][0].kind, "upward-data")

    def test_service_import_classified_upward_behaviour(self):
        found = eb.blockers(MANIFEST, [ref(P + "features.tse.domain.service.TseService")])
        blocker = found["ecr"][0]
        self.assertEqual(blocker.kind, "upward-behaviour")
        self.assertEqual((blocker.from_target_module, blocker.to_target_module), ("ecr-server", "fiscal-server"))

    def test_blockers_grouped_by_target_area_in_extraction_order(self):
        refs = [ref(P + "features.tse.domain.service.A", from_area="platform", path="platform/audit/src/main/kotlin/org/example/project/A.kt"),
                ref(P + "features.tse.domain.service.B", from_area="fiscal", to_area="accounting"),
                ref(P + "features.tse.domain.service.C", from_area=ur.WIRING, path="pos-server/src/main/kotlin/org/example/project/Application.kt"),
                ref(P + "features.tse.domain.service.D", from_area="ecr")]
        self.assertEqual(list(eb.blockers(MANIFEST, refs)), ["platform", "ecr", "fiscal", ur.WIRING])

    def test_no_blockers_when_all_references_are_downward(self):
        self.assertEqual(eb.blockers(MANIFEST[:2], []), {})

    def test_host_sources_counted_separately(self):
        found = eb.blockers(MANIFEST[:2], [ref(P + "features.tse.domain.service.A", from_area=ur.WIRING, path="pos-server/src/main/kotlin/org/example/project/Application.kt")])
        self.assertEqual(list(found), [ur.WIRING])
        self.assertNotIn("platform", found)

    def test_same_area_split_package_is_a_blocker(self):
        found = eb.blockers(MANIFEST, [])
        self.assertEqual(list(found), ["fiscal"])
        blocker = found["fiscal"][0]
        self.assertEqual((blocker.kind, blocker.imported_fqn), ("same-area-split", "features.kassenbuch.domain"))
        self.assertEqual({blocker.from_target_module, blocker.to_target_module}, {"fiscal-data", "fiscal-server"})

    def test_function_import_uses_enclosing_package(self):
        self.assertEqual(eb.imported_package(P + "features.tse.domain.toLateSigningCause"), "features.tse.domain")


    def test_blockers_use_file_area(self):
        enum = "shared/src/commonMain/kotlin/org/example/project/core/domain/enums/tse/TseSigningStatus.kt"
        manifest = MANIFEST + [("shared", "commonMain", "core.domain.enums.tse", "fiscal-api", 2)]
        rel = "core/domain/enums/tse/TseSigningStatus.kt"
        # fiscal-package file overridden to ecr: it is an ecr-api file now and its fiscal import belongs to ecr
        r = ur.Ref(enum, "ecr", P + "features.tse.domain.service.TseService", "fiscal")
        found = eb.blockers(manifest, [r], file_targets={rel: "ecr-api"})
        self.assertEqual([b.from_target_module for b in found["ecr"]], ["ecr-api"])
        # an import of the overridden file reports the file's target module, not its package's
        imp = ref(P + "core.domain.enums.tse.TseSigningStatus", to_area="ecr", from_area="platform", path=ORDER_FILE)
        self.assertNotIn("ecr", eb.blockers(manifest, [imp], file_targets={rel: "ecr-api"}))
        self.assertEqual(eb._to_module(P + "core.domain.enums.tse.TseSigningStatus", "ecr", "ecr-server", eb._target_index(manifest), None, {rel: "ecr-api"}), "ecr-api")

    def test_classify_dto_and_enum_packages_are_upward_data(self):
        self.assertEqual(eb.classify(P + "core.domain.dto.bill.BillDto"), "upward-data")
        self.assertEqual(eb.classify(P + "core.domain.enums.tse.TseState"), "upward-data")

    def test_classify_repository_interface_is_upward_behaviour(self):
        self.assertEqual(eb.classify(P + "features.order.domain.repository.OrderRepository"), "upward-behaviour")

    def test_counts_by_kind_includes_zero_kinds(self):
        items = [eb.Blocker("f", "a", "x", "b", "upward-data"), eb.Blocker("g", "a", "y", "b", "upward-data")]
        self.assertEqual(eb.counts_by_kind(items), {"upward-data": 2, "upward-behaviour": 0, "same-area-split": 0})

    def test_clusters_ties_sorted_by_key(self):
        b = lambda path, fqn: eb.Blocker(path, "m", P + fqn, "n", "upward-behaviour")  # noqa: E731
        base = "pos-server/src/main/kotlin/org/example/project/"
        items = [b(base + "features/zeta/A.kt", "features.x.A"), b(base + "features/alpha/B.kt", "features.x.B")]
        self.assertEqual([key[0] for key, _ in eb.clusters(items)], ["features.alpha", "features.zeta"])

    def test_clusters_largest_first(self):
        base = "pos-server/src/main/kotlin/org/example/project/features/"
        items = [eb.Blocker(base + "a/A.kt", "m", P + "f.x.A", "n", "k"), eb.Blocker(base + "z/A.kt", "m", P + "f.y.A", "n", "k"),
                 eb.Blocker(base + "z/B.kt", "m", P + "f.y.B", "n", "k")]
        self.assertEqual(eb.clusters(items)[0][0], ("features.z", "f.y"))

    def test_to_module_prefers_importer_layer_and_tse_branch(self):
        index = eb._target_index(MANIFEST)
        self.assertEqual(eb._to_module(P + "features.kassenbuch.domain.K", "fiscal", "ecr-data", index), "fiscal-data")
        self.assertEqual(eb._to_module(P + "features.kassenbuch.domain.K", "fiscal", "ecr-server", index), "fiscal-server")
        self.assertEqual(eb._to_module(P + "tse.Whatever", "fiscal", "ecr-server", index), "pos-tse")
        self.assertEqual(eb._to_module(P + "features.nope.X", "fiscal", "ecr-server", index), "fiscal-?")

    def test_from_package_variants(self):
        self.assertEqual(eb._from_package(eb.Blocker(ORDER_FILE, "m", "x", "n", "k")), "features.order.domain.service")
        self.assertEqual(eb._from_package(eb.Blocker("pos-server/src/main/kotlin/org/example/project/Main.kt", "m", "x", "n", "k")), eb.mm.ROOT_PACKAGE)
        self.assertEqual(eb._from_package(eb.Blocker("ecr/printer", "m", "x", "n", "k")), "ecr/printer")

    def _found(self):
        b = eb.Blocker
        return {"ecr": [b(ORDER_FILE, "ecr-server", P + "features.nope.X", "fiscal-?", "upward-behaviour"),
                        b(ORDER_FILE, "ecr-server", P + "features.tse.domain.service.S", "fiscal-server", "upward-behaviour")],
                ur.WIRING: [b("pos-server/src/main/kotlin/org/example/project/Application.kt", "host", P + "a.B", "ecr-server", "upward-behaviour"),
                            b("shared/src/commonMain/kotlin/org/example/project/core/data/remote/XClient.kt", "platform-api", P + "a.C", "ecr-api", "upward-behaviour")]}

    def test_render_report_host_split_artefact_and_gap_sections(self):
        report = eb.render_report(self._found(), frozenset({"core.data.remote", "features.order.domain.service"}))
        self.assertIn("2 host-sourced references: 1 from files whose manifest target is `host`, 1 from files outside the manifest", report)
        self.assertIn("Host vs other source: 1 from files whose manifest target is `host`, 1 from files outside the manifest", report)
        self.assertIn("B2-6 host/cleanup**: 2 blockers", report)
        self.assertIn("of which 1 from `host`-targeted files, 1 from files outside the manifest", report)
        self.assertIn("[artefact?]", report)
        self.assertIn("ecr 2/2", report)  # both ecr blockers come from ORDER_FILE, which sits in the mixed package
        self.assertIn("`<area>-?` target", report)
        self.assertIn("Distinct unresolved packages (1): `features.nope`", report)

    def test_is_artefact_only_for_mixed_or_split_packages(self):
        mixed = eb.Blocker(ORDER_FILE, "ecr-server", "x", "n", "k")
        self.assertTrue(eb.is_artefact(mixed, frozenset({"features.order.domain.service"})))
        self.assertFalse(eb.is_artefact(mixed, frozenset()))
        extracted = eb.Blocker("ecr/printer/src/main/kotlin/org/example/project/A.kt", "ecr/printer", "x", "n", "k")
        self.assertFalse(eb.is_artefact(extracted, frozenset()))

    def test_main_returns_zero_and_writes_file(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            # --out is resolved against ROOT; an absolute tmp path stays absolute (Path / absolute -> absolute)
            self.assertEqual(eb.main(["--out", str(Path(tmp) / "r.md")]), 0)
            text = (Path(tmp) / "r.md").read_text(encoding="utf-8")
        self.assertTrue(text.startswith("# Extraction blockers per area"))
        self.assertIn("## Caveats", text)


PLATFORM_FILE = "pos-server/src/main/kotlin/org/example/project/utils/builder/tse/ProcessDataBuilder.kt"
PLATFORM_MANIFEST = [("pos-server", "main", "utils.builder.tse", "platform-server", 1),
                     ("pos-server", "main", "infrastructure.di", "host", 1),
                     ("shared", "commonMain", "core.domain.enums.tse", "fiscal-api", 1),
                     ("shared", "commonMain", "core.domain.dto.kassenbuch", "fiscal-api", 1),
                     ("core-data", "main", "features.zBon.database.tables", "ecr-data", 1),
                     ("shared", "commonMain", "core.domain.dto.order", "ecr-api", 1)]
DI_FILE = "pos-server/src/main/kotlin/org/example/project/infrastructure/di/Module.kt"
ZBON_FILE = "core-data/src/main/kotlin/org/example/project/features/zBon/database/tables/ZBonTable.kt"


class OwnerAreaTest(unittest.TestCase):
    def test_platform_target_file_without_feature_segment_is_a_platform_blocker(self):
        found = eb.blockers(PLATFORM_MANIFEST, [ref(P + "features.tse.domain.service.TseService", from_area=ur.WIRING, path=PLATFORM_FILE)])
        self.assertEqual(list(found), ["platform"])
        self.assertEqual(found["platform"][0].from_target_module, "platform-server")

    def test_host_target_file_stays_informational(self):
        found = eb.blockers(PLATFORM_MANIFEST, [ref(P + "features.tse.domain.service.TseService", from_area=ur.WIRING, path=DI_FILE)])
        self.assertEqual(list(found), [ur.WIRING])

    def test_file_absent_from_manifest_keeps_its_scanned_area(self):
        path = "ecr/printer/src/main/kotlin/org/example/project/P.kt"
        found = eb.blockers(PLATFORM_MANIFEST, [ref(P + "features.tse.domain.service.S", from_area="ecr", path=path)])
        self.assertEqual(list(found), ["ecr"])

    def test_same_or_lower_destination_is_dropped_after_reowning(self):
        found = eb.blockers(PLATFORM_MANIFEST, [ref(P + "features.order.domain.service.S", to_area="ecr", from_area=ur.WIRING, path=ZBON_FILE)])
        self.assertEqual(found, {})

    def test_ui_sources_are_not_blockers_but_counted(self):
        ui = ref(P + "features.tse.domain.service.S", from_area=ur.WIRING, path="ui/shared/src/commonMain/kotlin/org/example/project/A.kt")
        self.assertEqual(eb.blockers(PLATFORM_MANIFEST, [ui]), {})
        self.assertEqual(eb.ui_reference_count([ui, ref(P + "features.tse.domain.service.S")]), 1)

    def test_extracted_module_package_resolves_instead_of_area_question_mark(self):
        index = eb._target_index(PLATFORM_MANIFEST)
        self.assertEqual(eb._to_module(P + "features.document.domain.D", "accounting", "ecr-server", index, {"features.document.domain": "accounting/document"}), "accounting/document")
        self.assertEqual(eb._to_module(P + "features.nope.X", "accounting", "ecr-server", index, {}), "accounting-?")


class DestinationAndDecisionsTest(unittest.TestCase):
    def _b(self, path, frm, fqn, to, kind="upward-data"):
        return eb.Blocker(path, frm, P + fqn, to, kind)

    def test_dest_area_from_target_module(self):
        self.assertEqual(eb.dest_area("fiscal-server"), "fiscal")
        self.assertEqual(eb.dest_area("accounting/document"), "accounting")
        self.assertEqual(eb.dest_area("pos-tse"), "fiscal")
        self.assertIsNone(eb.dest_area("host"))

    def test_destination_table_in_area_section(self):
        found = {"fiscal": [self._b("a/A.kt", "fiscal-server", "features.datev.S", "accounting-server", "upward-behaviour"),
                            self._b("a/B.kt", "fiscal-server", "features.datev.T", "accounting-server", "upward-behaviour")]}
        report = eb.render_report(found)
        self.assertIn("Blockers by destination area:", report)
        self.assertIn("| fiscal -> accounting | 2 |", report)

    def test_decisions_block_lists_kassenbuch_family_from_ecr_data(self):
        found = {"ecr": [self._b("core-data/src/main/kotlin/org/example/project/features/zBon/database/tables/ZBon.kt", "ecr-data", "features.kassenbuch.database.tables.KassenbuchHeader", "fiscal-data"),
                         self._b("x/Other.kt", "ecr-server", "features.kassenbuch.domain.service.K", "fiscal-server", "upward-behaviour")],
                 "fiscal": [self._b("f/F.kt", "fiscal-server", "features.datev.S", "accounting-server", "upward-behaviour")]}
        report = eb.render_report(found)
        self.assertIn("## Decisions needed before B2-2 / B2-3", report)
        self.assertIn("ZBon.kt", report)
        self.assertNotIn("Other.kt` imports `org.example.project.features.kassenbuch.domain.service.K` (fiscal-server) - decision", report)
        self.assertIn("unless the Kassenbuch header moves down or ZBon holds only an id", report)
        self.assertIn("contradicts the area order fiscal < accounting", report)
        self.assertIn("1 reference", report)

    def test_decisions_block_absent_without_the_conditions(self):
        found = {"ecr": [self._b("x/Other.kt", "ecr-server", "features.tse.domain.service.K", "fiscal-server", "upward-behaviour")]}
        self.assertNotIn("Decisions needed", eb.render_report(found))

    def test_decisions_block_only_with_accounting_condition(self):
        found = {"fiscal": [self._b("f/F.kt", "fiscal-server", "features.datev.S", "accounting-server", "upward-behaviour")]}
        report = eb.render_report(found)
        self.assertIn("Decisions needed", report)
        self.assertNotIn("Kassenbuch header moves down", report)


SPLIT = [
    ("platform-api", "commonMain", "core.utils", "platform-api", 2),
    ("platform-data", "main", "core.utils", "platform-data", 1),
]


class SplitPackageTest(unittest.TestCase):
    def test_settled_same_area_split_is_not_a_blocker(self):
        self.assertEqual(eb.blockers(SPLIT, []), {})
        settled = eb.settled_splits(SPLIT)
        self.assertEqual([(s.package, s.area, s.targets) for s in settled], [("core.utils", "platform", ("platform-api", "platform-data"))])
        report = eb.render_report({}, settled=settled)
        self.assertIn("## Split packages (settled) (1)", report)
        self.assertIn("- `core.utils` (platform): platform-api, platform-data", report)

    def test_pending_same_area_split_is_a_blocker(self):
        pending = SPLIT + [("shared", "commonMain", "core.utils", "platform-api", 1)]
        found = eb.blockers(pending, [])
        self.assertEqual([(b.kind, b.imported_fqn) for b in found["platform"]], [("same-area-split", "core.utils")])
        self.assertEqual(eb.settled_splits(pending), [])

    def test_colliding_settled_split_is_a_blocker(self):
        self.assertEqual(list(eb.blockers(SPLIT, [], collisions=frozenset({"core.utils"}))), ["platform"])
        self.assertEqual(eb.settled_splits(SPLIT, frozenset({"core.utils"})), [])

    def test_collisions_found_by_package_and_file_name(self):
        base = "org/example/project/core/utils"
        files = {f"platform/api/src/commonMain/kotlin/{base}/A.kt": "package org.example.project.core.utils\n",
                 f"shared/src/commonMain/kotlin/{base}/A.kt": "package org.example.project.core.utils\n",
                 f"shared/src/commonMain/kotlin/{base}/B.kt": "package org.example.project.core.utils\n"}
        self.assertEqual(eb.colliding_packages(files), frozenset({"core.utils"}))
        del files[f"shared/src/commonMain/kotlin/{base}/A.kt"]
        self.assertEqual(eb.colliding_packages(files), frozenset())

    # Task 9: after the server move, pos-server still holds a `core.config` row whose package target is platform-server,
    # but its one file (AccountingSettings) is placed to accounting by a file row: nothing of the row is left to move.
    RESIDUE = [("platform-data", "main", "core.config", "platform-data", 1),
               ("platform-server", "main", "core.config", "platform-server", 4),
               ("pos-server", "main", "core.config", "platform-server", 1)]

    def test_row_whose_files_are_all_placed_elsewhere_is_not_pending(self):
        file_rows = [eb.mm.FileRow("pos-server", "main", "core.config", "accounting-server", "core/config/AccountingSettings.kt")]
        placed = eb.fully_placed_rows(self.RESIDUE, file_rows)
        self.assertEqual(placed, frozenset({("pos-server", "main", "core.config")}))
        self.assertEqual(eb.blockers(self.RESIDUE, [], fully_placed=placed), {})
        self.assertEqual([(s.package, s.targets) for s in eb.settled_splits(self.RESIDUE, fully_placed=placed)],
                         [("core.config", ("platform-data", "platform-server"))])
        # without the file rows the residue row is a file still to move
        self.assertEqual([b.imported_fqn for b in eb.blockers(self.RESIDUE, [])["platform"]], ["core.config"])

    def test_row_with_a_file_left_to_move_stays_pending(self):
        manifest = self.RESIDUE[:2] + [("pos-server", "main", "core.config", "platform-server", 2)]
        file_rows = [eb.mm.FileRow("pos-server", "main", "core.config", "accounting-server", "core/config/AccountingSettings.kt")]
        placed = eb.fully_placed_rows(manifest, file_rows)
        self.assertEqual(placed, frozenset())
        self.assertEqual([b.imported_fqn for b in eb.blockers(manifest, [], fully_placed=placed)["platform"]], ["core.config"])

    def test_empty_settled_section_is_rendered_as_none(self):
        self.assertIn("## Split packages (settled) (0)\n\nInformational", eb.render_report({}))


if __name__ == "__main__":
    unittest.main()
