import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shared_placement as sp  # noqa: E402

P = "org.example.project."
UTILS = "package org.example.project.core.utils\n"
REMOTE = "package org.example.project.core.data.remote\n"
IMPL = "package org.example.project.core.data.remote.impl\n"
ENUMS = "package org.example.project.core.utils.enumStrings\n"
AREA = {P + "core.domain.dto.order.OrderDto": "ecr", P + "core.domain.dto.tse.TseDto": "fiscal",
        P + "core.domain.dto.datev.DatevDto": "accounting", P + "core.domain.dto.setup.VatDto": "platform",
        P + "core.domain.enums.order.ItemStatus": "ecr", P + "core.domain.enums.tse.TseMode": "fiscal"}


def area_of(fqn):
    return AREA.get(fqn)


def place(files, imports, fixed=None):
    return sp.place(files, imports, area_of, fixed)


class SharedPlacementTest(unittest.TestCase):
    def test_fixpoint_uses_computed_placement_of_set_imports(self):
        # B has no upward import itself; it imports A (placed ecr by its dto import) and so follows A, not A's package row
        files = {"core/utils/A.kt": UTILS + "class A\n", "core/utils/B.kt": UTILS + "class B\n", "core/utils/C.kt": UTILS + "class C\n"}
        imports = {"core/utils/A.kt": [P + "core.domain.dto.order.OrderDto"], "core/utils/B.kt": [P + "core.utils.A"],
                   "core/utils/C.kt": [P + "core.domain.dto.tse.TseDto", P + "core.utils.B"]}
        self.assertEqual(place(files, imports), {"core/utils/A.kt": "ecr", "core/utils/B.kt": "ecr", "core/utils/C.kt": "fiscal"})

    def test_highest_import_wins_and_no_upward_import_is_platform(self):
        files = {"core/utils/A.kt": UTILS + "class A\n", "core/utils/B.kt": UTILS + "class B\n"}
        imports = {"core/utils/A.kt": [P + "core.domain.dto.order.OrderDto", P + "core.domain.dto.datev.DatevDto", P + "core.domain.dto.setup.VatDto"],
                   "core/utils/B.kt": [P + "core.domain.dto.setup.VatDto", "kotlinx.coroutines.Job"]}
        self.assertEqual(place(files, imports), {"core/utils/A.kt": "accounting", "core/utils/B.kt": "platform"})

    def test_impl_follows_interface(self):
        files = {"core/data/remote/FooApiClient.kt": REMOTE + "interface FooApiClient\n",
                 "core/data/remote/impl/KtorFooApiClient.kt": IMPL + "class KtorFooApiClient : FooApiClient\n"}
        imports = {"core/data/remote/FooApiClient.kt": [P + "core.domain.dto.datev.DatevDto"], "core/data/remote/impl/KtorFooApiClient.kt": []}
        self.assertEqual(place(files, imports)["core/data/remote/impl/KtorFooApiClient.kt"], "accounting")
        # and the impl may be higher than its interface
        imports = {"core/data/remote/FooApiClient.kt": [], "core/data/remote/impl/KtorFooApiClient.kt": [P + "core.domain.dto.tse.TseDto"]}
        self.assertEqual(place(files, imports)["core/data/remote/FooApiClient.kt"], "platform")
        self.assertEqual(place(files, imports)["core/data/remote/impl/KtorFooApiClient.kt"], "fiscal")

    def test_same_package_use_counts_as_import(self):
        files = {"core/utils/A.kt": UTILS + "fun a() = Heavy()\n", "core/utils/Heavy.kt": UTILS + "class Heavy\n"}
        imports = {"core/utils/A.kt": [], "core/utils/Heavy.kt": [P + "core.domain.dto.tse.TseDto"]}
        self.assertEqual(place(files, imports)["core/utils/A.kt"], "fiscal")
        # a name only in a comment or a string is not a use
        files["core/utils/A.kt"] = UTILS + '// Heavy\nval s = "Heavy"\n'
        self.assertEqual(place(files, imports)["core/utils/A.kt"], "platform")

    def test_name_declared_twice_in_a_package_is_ambiguous_and_ignored(self):
        files = {"core/utils/A.kt": UTILS + "fun a() = getName()\n", "core/utils/X.kt": UTILS + "fun getName() = 1\n", "core/utils/Y.kt": UTILS + "fun getName() = 2\n"}
        imports = {"core/utils/A.kt": [], "core/utils/X.kt": [P + "core.domain.dto.tse.TseDto"], "core/utils/Y.kt": []}
        self.assertEqual(place(files, imports)["core/utils/A.kt"], "platform")

    def test_enum_strings_matched_by_receiver_type(self):
        # each file declares the same `getName`; the area is the receiver enum's, the other imports (Language, a sibling enum) do not count
        a, b = "core/utils/enumStrings/ItemStatusStrings.kt", "core/utils/enumStrings/TseModeStrings.kt"
        files = {a: ENUMS + "fun ItemStatus.getName(language: Language): String = when (this) { else -> getString(EnumKeys.x, language) }\n",
                 b: ENUMS + "fun TseMode.getName(language: Language): String = TseMode.AUTO.toString() + getName2(ItemStatus.OPEN)\n"}
        imports = {a: [P + "core.domain.enums.order.ItemStatus", P + "core.presentation.strings.keys.EnumKeys"],
                   b: [P + "core.domain.enums.tse.TseMode", P + "core.domain.enums.order.ItemStatus"]}
        self.assertEqual(place(files, imports), {a: "ecr", b: "fiscal"})

    def test_wildcard_import_is_an_edge_to_every_file_of_the_package(self):
        files = {"core/utils/User.kt": UTILS + "class User\n", "core/data/models/M1.kt": "package org.example.project.core.data.models\nclass M1\n",
                 "core/data/models/M2.kt": "package org.example.project.core.data.models\nclass M2\n"}
        imports = {"core/utils/User.kt": [P + "core.data.models."], "core/data/models/M1.kt": [P + "core.domain.dto.tse.TseDto"],
                   "core/data/models/M2.kt": [P + "core.domain.dto.datev.DatevDto"]}
        placed = place(files, imports)
        self.assertEqual(placed["core/utils/User.kt"], "accounting")  # the highest file of the wildcard's package, at its computed placement
        imports["core/data/models/M2.kt"] = []
        self.assertEqual(place(files, imports)["core/utils/User.kt"], "fiscal")

    def test_manual_row_wins_over_generated(self):
        files = {"core/utils/A.kt": UTILS + "class A\n", "core/utils/B.kt": UTILS + "class B\n"}
        imports = {"core/utils/A.kt": [P + "core.domain.dto.tse.TseDto"], "core/utils/B.kt": [P + "core.utils.A"]}
        solution = sp.solve(files, imports, area_of, {"core/utils/A.kt": "accounting"})
        self.assertEqual(solution.placed, {"core/utils/A.kt": "accounting", "core/utils/B.kt": "accounting"})  # dependents follow the manual area
        self.assertEqual(solution.disagreements, {"core/utils/A.kt": ("accounting", "fiscal")})  # (manual, computed)

    def test_lower_area_importer_is_a_conflict_not_a_placement(self):
        files = {"core/utils/A.kt": UTILS + "class A\n"}
        imports = {"core/utils/A.kt": [P + "core.domain.dto.tse.TseDto"]}
        placed = place(files, imports)
        self.assertEqual(placed, {"core/utils/A.kt": "fiscal"})  # the platform importer does not pull A down
        importers = [sp.Importer("pos-server/x/Low.kt", "platform", [P + "core.utils.A"]),
                     sp.Importer("pos-server/x/Same.kt", "fiscal", [P + "core.utils.A"]),
                     sp.Importer("pos-server/x/High.kt", "accounting", [P + "core.utils.A"])]
        conflicts = sp.find_conflicts(files, placed, importers)
        self.assertEqual([(c.importer, c.importer_area, c.target, c.placed, c.symbol) for c in conflicts],
                         [("pos-server/x/Low.kt", "platform", "core/utils/A.kt", "fiscal", "A")])

    def test_ui_importers_are_exempt(self):
        files = {"core/utils/A.kt": UTILS + "class A\n"}
        placed = place(files, {"core/utils/A.kt": [P + "core.domain.dto.tse.TseDto"]})
        importers = [sp.Importer("ui/till/Screen.kt", "ui", [P + "core.utils.A"]), sp.Importer("pos-server/Wiring.kt", "host/wiring", [P + "core.utils.A"])]
        self.assertEqual(sp.find_conflicts(files, placed, importers), [])

    def test_block_replacement_keeps_manual_rows(self):
        manual = "# header\nfoo/Bar.kt\tecr\treason\n"
        first = sp.replace_block(manual, [("core/utils/A.kt", "fiscal", "fixpoint: x")])
        self.assertTrue(first.startswith(manual))
        self.assertIn(sp.BEGIN, first)
        self.assertIn("core/utils/A.kt\tfiscal:api\tfixpoint: x", first)
        second = sp.replace_block(first, [("core/utils/B.kt", "ecr", "fixpoint: y")])
        self.assertTrue(second.startswith(manual))
        self.assertNotIn("core/utils/A.kt", second)
        self.assertEqual(sp.replace_block(second, [("core/utils/B.kt", "ecr", "fixpoint: y")]), second)  # idempotent
        self.assertEqual(sp.split_block(second)[0], manual)  # manual text unchanged, block separable
        tail = second + "after.kt\tecr\tmanual after the block\n"
        self.assertIn("after.kt", sp.split_block(tail)[0])


BASE = "org/example/project"
KT = "src/commonMain/kotlin/" + BASE


class MovedTreeTest(unittest.TestCase):
    """Files already moved from shared/ to platform/api/ must give the same solution, block and report as the unmoved tree."""

    FILES = {"core/utils/A.kt": UTILS + "import " + P + "core.domain.dto.order.OrderDto\nclass A\n",
             "core/utils/B.kt": UTILS + "class B(val a: A)\n",
             "core/data/remote/KtorX.kt": REMOTE + "class KtorX\n"}

    def tree(self, moved: set[str]) -> dict[str, str]:
        return {f"{'platform/api' if rel in moved else 'shared'}/{KT}/{rel}": text for rel, text in self.FILES.items()} | {
            "pos-server/src/main/kotlin/" + BASE + "/Wiring.kt": "package org.example.project\nimport " + P + "core.utils.A\n"}

    def outcome(self, tree):
        files, imports = sp.set_files(tree)
        solution = sp.solve(files, imports, area_of, {})
        scanned = {p: t for p, t in tree.items()}
        importers = sp.importer_areas(scanned, tree, set(files))
        rows = [(p, a, f"fixpoint: {solution.reasons[p]}") for p, a in sorted(solution.placed.items()) if a != "platform"]
        conflicts = sp.find_conflicts(files, solution.placed, importers)
        report = sp.render_report(files, solution, rows, conflicts, {}, {p: "platform" for p in files}, {})
        return files, imports, solution.placed, importers, conflicts, sp.render_block(rows), report

    def test_split_tree_gives_the_same_solution_block_and_report(self):
        unmoved = self.outcome(self.tree(set()))
        self.assertEqual(sorted(unmoved[0]), sorted(self.FILES))
        for moved in ({"core/utils/B.kt"}, {"core/utils/A.kt", "core/data/remote/KtorX.kt"}, set(self.FILES)):
            with self.subTest(moved=sorted(moved)):
                got = self.outcome(self.tree(moved))
                self.assertEqual(got[:3], unmoved[:3])
                self.assertEqual(got[5:], unmoved[5:])
                self.assertEqual([(i.area, i.imports) for i in got[3]], [(i.area, i.imports) for i in unmoved[3]])  # moved set files are not outside importers
                self.assertEqual(got[4], unmoved[4])


if __name__ == "__main__":
    unittest.main()
