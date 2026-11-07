import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pull_down as pd  # noqa: E402
import upward_refs as ur  # noqa: E402

P = "org.example.project."
B = "org/example/project"
TABLE = {"order": "ecr", "tse": "fiscal", "kassenbuch": "fiscal", "zBon": "fiscal"}
OV = {"zBon.database.tables": "ecr", "zBon.domain.models": "ecr"}
TABLES = f"core-data/src/main/kotlin/{B}/features/zBon/database/tables/ZBonTable.kt"
ENUMS = f"shared/src/commonMain/kotlin/{B}/core/domain/enums"
DTO = f"shared/src/commonMain/kotlin/{B}/core/domain/dto/tse"
SERVICE = f"pos-server/src/main/kotlin/{B}/features/tse/domain/service/TseService.kt"

FILES = {
    TABLES: f"import {P}core.domain.enums.kassenbuch.EntryType\nimport {P}core.domain.dto.tse.ReceiptDto\nimport {P}features.tse.domain.service.TseService\n",
    f"{ENUMS}/kassenbuch/EntryType.kt": f"import {P}core.domain.enums.tse.Kind\n",
    f"{ENUMS}/tse/Kind.kt": "",
    f"{ENUMS}/tse/AdminOnly.kt": "",
    f"{DTO}/ReceiptDto.kt": f"import {P}core.domain.enums.tse.Kind\nimport {P}core.domain.dto.tse.NoteDto\n",
    f"{DTO}/NoteDto.kt": "",
    f"{DTO}/TseDto.kt": "",
    SERVICE: "",
}


def run(files=FILES, file_overrides=None, excluded=frozenset()):
    policy = pd.Policy(TABLE, OV, file_overrides or {}, excluded)
    refs = ur.find_upward(files, TABLE, OV, file_overrides or {})
    return pd.pull_down("ecr", refs, files, policy)


class PullDownTest(unittest.TestCase):
    def test_pull_down_closure_includes_transitive_data_types(self):
        result = run()
        self.assertEqual(result.moves, ["core/domain/dto/tse/NoteDto.kt", "core/domain/dto/tse/ReceiptDto.kt",
                                        "core/domain/enums/kassenbuch/EntryType.kt", "core/domain/enums/tse/Kind.kt"])
        self.assertNotIn("core/domain/enums/tse/AdminOnly.kt", result.moves)
        self.assertNotIn("core/domain/dto/tse/TseDto.kt", result.moves)

    def test_pull_down_does_not_move_services_reports_ports(self):
        result = run()
        self.assertEqual(result.ports, [(TABLES, P + "features.tse.domain.service.TseService")])
        self.assertNotIn("features/tse/domain/service/TseService.kt", result.moves)

    def test_exclusion_list_keeps_a_file_and_reports_it_as_a_port(self):
        result = run(excluded=frozenset({"core/domain/dto/tse/ReceiptDto.kt"}))
        self.assertNotIn("core/domain/dto/tse/ReceiptDto.kt", result.moves)
        self.assertIn((TABLES, P + "core.domain.dto.tse.ReceiptDto"), result.ports)

    def test_fixed_point_is_idempotent(self):
        first = run()
        overrides = {m: "ecr" for m in first.moves}
        refs = ur.find_upward(FILES, TABLE, OV, overrides)
        self.assertEqual([r for r in refs if r.from_area == "ecr" and pd.is_data(r.imported_fqn)], [])
        # regenerating from the same base (generated rows dropped) yields the same set, and so does a run that sees its own output
        self.assertEqual(run(file_overrides={}).moves, first.moves)
        self.assertEqual(pd.pull_down("ecr", refs, FILES, pd.Policy(TABLE, OV, overrides, frozenset())).moves, [])
        self.assertEqual(pd.stabilise("ecr", FILES, pd.Policy(TABLE, OV, overrides, frozenset())).moves, first.moves)

    def test_emitted_overrides_are_deterministic_and_commented(self):
        text = pd.render_overrides("ecr", ["b/B.kt", "a/A.kt"])
        self.assertEqual(text, pd.render_overrides("ecr", ["a/A.kt", "b/B.kt"]))
        self.assertTrue(text.startswith("#"))
        self.assertLess(text.index("a/A.kt\tecr"), text.index("b/B.kt\tecr"))
        self.assertEqual(ur.load_file_overrides(text), {"a/A.kt": "ecr", "b/B.kt": "ecr"})

    def test_ui_sources_do_not_seed_closure(self):
        ui = f"ui/shared/src/commonMain/kotlin/{B}/features/order/presentation/Screen.kt"
        files = {**FILES, ui: f"import {P}core.domain.dto.tse.TseDto\n", f"{DTO}/TseDto.kt": ""}
        self.assertNotIn("core/domain/dto/tse/TseDto.kt", run(files).moves)
        self.assertEqual(run(files).moves, run().moves)

    def test_accounting_data_is_kept_not_moved(self):
        acc = f"shared/src/commonMain/kotlin/{B}/core/domain/enums/datev/Side.kt"
        files = {**FILES, acc: "", f"{ENUMS}/kassenbuch/EntryType.kt": f"import {P}core.domain.enums.tse.Kind\nimport {P}core.domain.enums.datev.Side\n"}
        table = {**TABLE, "datev": "accounting"}
        policy = pd.Policy(table, OV, {}, frozenset(), frozenset({"fiscal"}))
        result = pd.pull_down("ecr", ur.find_upward(files, table, OV, {}), files, policy)
        self.assertNotIn("core/domain/enums/datev/Side.kt", result.moves)
        self.assertIn("core/domain/enums/kassenbuch/EntryType.kt", result.moves)
        self.assertEqual(result.kept, [(f"shared/src/commonMain/kotlin/{B}/core/domain/enums/kassenbuch/EntryType.kt", P + "core.domain.enums.datev.Side", "accounting")])
        return result

    def test_report_lists_remaining_upward_references_for_kept_types(self):
        result = self.test_accounting_data_is_kept_not_moved()
        report = pd.render_report("ecr", result)
        self.assertIn("## Remaining upward references", report)
        self.assertIn("`org.example.project.core.domain.enums.datev.Side` (accounting) still imported by `core/domain/enums/kassenbuch/EntryType.kt` (pulled down)", report)
        self.assertIn("1 kept types still referenced upward", report)
        self.assertIn("same-package references carry no import statement", report)

    def test_excluded_report_dtos_do_not_pull_their_imports(self):
        files = {**FILES, f"{DTO}/ReceiptDto.kt": f"import {P}core.domain.enums.tse.Other\n", f"{ENUMS}/tse/Other.kt": ""}
        result = run(files, excluded=frozenset({"core/domain/dto/tse/ReceiptDto.kt"}))
        self.assertNotIn("core/domain/enums/tse/Other.kt", result.moves)

    def test_pull_down_skips_paths_with_placement_rows(self):
        existing = {"a/A.kt": "ecr", "b/B.kt": "fiscal", "c/C.kt": "ecr", "d/D.kt": "fiscal"}
        placements = {"a/A.kt": ur.Placement("fiscal", "server"), "b/B.kt": ur.Placement("ecr", "api")}
        with self.assertRaises(ValueError):
            ur.merge_placements(placements, existing)  # what --emit-overrides used to trip over
        kept = pd.skip_placed(existing, placements)
        self.assertEqual(kept, {"c/C.kt": "ecr", "d/D.kt": "fiscal"})
        self.assertEqual(ur.merge_placements(placements, kept), {"a/A.kt": "fiscal", "b/B.kt": "ecr", "c/C.kt": "ecr", "d/D.kt": "fiscal"})
        text = pd.emitted_overrides("ecr", ["a/A.kt", "c/C.kt"], existing, placements)
        self.assertEqual(ur.load_file_overrides(text), {"c/C.kt": "ecr", "d/D.kt": "fiscal"})

    def test_ports_section_label_names_excluded_report_dtos(self):
        result = run(FILES)
        report = pd.render_report("ecr", result)
        self.assertIn("## Ports - behaviour types and excluded report DTOs ecr imports from higher areas (", report)


if __name__ == "__main__":
    unittest.main()
