import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extraction_blockers as eb  # noqa: E402
import move_manifest as mm  # noqa: E402
import pull_down as pd  # noqa: E402
import upward_refs as ur  # noqa: E402

P = "org.example.project."
B = "org/example/project"
AREAS = {"order": "ecr", "tse": "fiscal", "kassenbuch": "fiscal", "auth": "platform"}
SERVER_UTIL = f"pos-server/src/main/kotlin/{B}/utils/builder/ProcessDataBuilder.kt"
SERVER_UTIL_REL = "utils/builder/ProcessDataBuilder.kt"
ENUM = f"shared/src/commonMain/kotlin/{B}/core/domain/enums/tse/Kind.kt"
ENUM_REL = "core/domain/enums/tse/Kind.kt"
ORDER = f"pos-server/src/main/kotlin/{B}/features/order/domain/service/OrderService.kt"


def pkg(directory: str) -> str:
    return "package org.example.project." + directory.replace("/", ".") + "\n"


class PlacementLoaderTest(unittest.TestCase):
    def test_load_parses_area_tier_and_host(self):
        text = "# c\n\na/A.kt\tecr:data\tbecause\nb/B.kt\thost\tkeeps wiring\nc/C.kt\tfiscal\treason\n"
        self.assertEqual(ur.load_placement_overrides(text), {"a/A.kt": ur.Placement("ecr", "data"), "b/B.kt": ur.Placement("host", None),
                                                               "c/C.kt": ur.Placement("fiscal", None)})

    def test_malformed_placement_line_names_line_number(self):
        bad = {"x.kt\tecr\n": "line 1", "# c\na/A.kt\tnowhere\tr\n": "line 2", "a/A.kt\tecr:wide\tr\n": "line 1", "a/A.kt\thost:data\tr\n": "line 1",
               "a/A.kt\tecr\tr\n\na/A.kt\tfiscal\tr\n": "line 3", "a/A.kt\tecr:\tr\n": "line 1", "a/A.kt\tecr\t\n": "line 1"}
        for text, where in bad.items():
            with self.assertRaises(ValueError, msg=repr(text)) as caught:
                ur.load_placement_overrides(text)
            self.assertIn(where, str(caught.exception))
        self.assertEqual(len(ur.load_placement_overrides("a/A.kt\tecr\tr\na/A.kt\tecr\tr\n")), 1)  # identical duplicate is fine

    def test_placement_conflict_with_generated_is_rejected(self):
        placements = {"a/A.kt": ur.Placement("fiscal", None)}
        with self.assertRaises(ValueError) as caught:
            ur.merge_placements(placements, {"a/A.kt": "ecr"})
        self.assertIn("a/A.kt", str(caught.exception))
        self.assertEqual(ur.merge_placements({"a/A.kt": ur.Placement("ecr", "data")}, {"a/A.kt": "ecr"}), {"a/A.kt": "ecr"})  # same area: tier refines

    def test_placement_override_beats_generated_override(self):
        files = {ENUM: "", ORDER: f"import {P}core.domain.enums.tse.Kind\n"}
        generated = {ENUM_REL: "ecr"}
        self.assertEqual(ur.find_upward(files, AREAS, {}, generated), [])
        placed = ur.merge_placements({"core/domain/enums/tse/Kind.kt": ur.Placement("ecr", "api")}, generated)
        self.assertEqual(placed[ENUM_REL], "ecr")
        # a placement on a path the generator did not list wins over the package rule (tse = fiscal)
        merged = ur.merge_placements({ENUM_REL: ur.Placement("platform", None)}, {})
        self.assertEqual(ur.find_upward(files, AREAS, {}, merged), [])
        self.assertEqual(len(ur.find_upward(files, AREAS, {}, {})), 1)

    def test_placement_host_area(self):
        merged = ur.merge_placements({SERVER_UTIL_REL: ur.Placement("host", None)}, {})
        self.assertEqual(merged[SERVER_UTIL_REL], ur.WIRING)
        files = {SERVER_UTIL: pkg("utils/builder") + f"import {P}core.domain.enums.tse.Kind\n", ENUM: pkg("core/domain/enums/tse")}
        self.assertEqual(ur.file_area(SERVER_UTIL, ur.area_of_file(SERVER_UTIL, AREAS), merged), ur.WIRING)
        rows = mm.file_rows(files, AREAS, {}, {}, {SERVER_UTIL_REL: ur.Placement("host", None)})
        # utils.builder is platform-server by package rule, so the host placement is a file row with target `host` (no tier)
        self.assertEqual([(r.path, r.target) for r in rows], [(SERVER_UTIL_REL, "host")])
        # the host-placed file is scanned as host/wiring (informational group), and importing it is never upward
        self.assertEqual([r.from_area for r in ur.find_upward(files, AREAS, {}, merged)], [ur.WIRING])

    def test_placement_tier_override_changes_target_module(self):
        files = {SERVER_UTIL: pkg("utils/builder"), ENUM: pkg("core/domain/enums/tse")}
        placements = {SERVER_UTIL_REL: ur.Placement("ecr", "data")}
        rows = mm.file_rows(files, AREAS, {}, {}, placements)
        self.assertEqual([(r.path, r.target) for r in rows], [(SERVER_UTIL_REL, "ecr-data")])
        default = mm.file_rows(files, AREAS, {}, {}, {SERVER_UTIL_REL: ur.Placement("ecr", None)})
        self.assertEqual([(r.path, r.target) for r in default], [(SERVER_UTIL_REL, "ecr-server")])
        tiered = mm.file_rows(files, AREAS, {}, {}, {ENUM_REL: ur.Placement("ecr", "data")})
        self.assertEqual([(r.path, r.target) for r in tiered], [(ENUM_REL, "ecr-data")])
        self.assertEqual(mm.mixed_packages(files, AREAS, {}, {}, placements)[0].package, "utils.builder")

    @staticmethod
    def fake_root(root: Path) -> contextlib.ExitStack:
        """Point every tool (and the upward_refs instance each one imported; test modules load several) at a temp tree."""
        stack = contextlib.ExitStack()
        for module in {id(m): m for m in (ur, mm, eb, pd, mm.ur, eb.ur, eb.mm, eb.mm.ur, pd.ur, pd.eb, pd.eb.mm)}.values():
            stack.enter_context(mock.patch.object(module, "ROOT", root))
        return stack

    def test_unmatched_placement_path_fails_cli(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "config/structure").mkdir(parents=True)
            (root / "config/structure/package-areas.tsv").write_text("order\tecr\n", encoding="utf-8")
            (root / "config/structure/package-overrides.tsv").write_text("", encoding="utf-8")
            (root / "config/structure/placement-overrides.tsv").write_text("gone/Missing.kt\tecr:data\treason\n", encoding="utf-8")
            source = root / f"pos-server/src/main/kotlin/{B}/features/order/A.kt"
            source.parent.mkdir(parents=True)
            source.write_text(pkg("features/order"), encoding="utf-8")
            out = str(root / "out.md")
            with self.fake_root(root), contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(ur.main(["--out", out]), 1)
                self.assertEqual(mm.main(["--tsv", out, "--report", out]), 1)
                self.assertEqual(eb.main(["--out", out]), 1)
                self.assertEqual(pd.main(["--area", "ecr", "--out", out]), 1)
            self.assertEqual(err.getvalue().count("placement-overrides: 'gone/Missing.kt' matches no file"), 2)  # ur + pull_down print; mm/eb report it in the exit code
            self.assertIn("1 override problems", err.getvalue())
            (root / "config/structure/placement-overrides.tsv").write_text("features/order/A.kt\tecr:data\treason\n", encoding="utf-8")
            with self.fake_root(root), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(ur.main(["--out", out]), 0)
                self.assertEqual(eb.main(["--out", out]), 0)

    def test_blockers_use_placement_for_importer_and_imported(self):
        manifest = [("pos-server", "main", "utils.builder", "platform-server", 1), ("pos-server", "main", "features.order.domain.service", "ecr-server", 1),
                    ("shared", "commonMain", "core.domain.enums.tse", "fiscal-api", 1)]
        files = {SERVER_UTIL: pkg("utils/builder") + f"import {P}core.domain.enums.tse.Kind\n", ENUM: pkg("core/domain/enums/tse"),
                 ORDER: pkg("features/order/domain/service") + f"import {P}utils.builder.ProcessDataBuilder\n"}
        placements = {SERVER_UTIL_REL: ur.Placement("ecr", "data"), ENUM_REL: ur.Placement("platform", "api")}
        merged = ur.merge_placements(placements, {})
        refs = ur.find_upward(files, AREAS, {}, merged)
        self.assertEqual(refs, [])  # the util is ecr (imports platform enum), the order service is ecr (imports ecr util)
        targets = {r.path: r.target for r in mm.file_rows(files, AREAS, {}, {}, placements)}
        self.assertEqual(targets, {SERVER_UTIL_REL: "ecr-data", ENUM_REL: "platform-api"})
        # without placements the util is platform and the enum fiscal: the platform util imports fiscal (an upward ref owned by platform)
        before = ur.find_upward(files, AREAS, {}, {})
        self.assertEqual([(r.from_area, r.to_area) for r in before], [(ur.WIRING, "fiscal")])
        # importer placed in ecr, imported file still fiscal: owned by ecr, tier-aware module names ecr-data -> fiscal-api
        part = ur.merge_placements({SERVER_UTIL_REL: ur.Placement("ecr", "data")}, {})
        found = eb.blockers(manifest, ur.find_upward(files, AREAS, {}, part), file_targets={SERVER_UTIL_REL: "ecr-data"})
        self.assertEqual([(b.from_target_module, b.to_target_module) for b in found["ecr"]], [("ecr-data", "fiscal-api")])
        # imported file placed in host: its importers see no upward reference at all
        host = ur.merge_placements({ENUM_REL: ur.Placement("host", None)}, {})
        self.assertEqual(ur.find_upward(files, AREAS, {}, host), [])

    def test_pull_down_respects_hand_placement(self):
        tse_files = {
            f"core-data/src/main/kotlin/{B}/features/order/A.kt": f"import {P}core.domain.enums.tse.Kind\nimport {P}core.domain.enums.tse.Hand\n",
            f"shared/src/commonMain/kotlin/{B}/core/domain/enums/tse/Kind.kt": "",
            f"shared/src/commonMain/kotlin/{B}/core/domain/enums/tse/Hand.kt": "",
        }
        hand = "core/domain/enums/tse/Hand.kt"
        placements = {hand: ur.Placement("fiscal", None)}
        merged = ur.merge_placements(placements, {})
        policy = pd.Policy(AREAS, {}, merged, frozenset(), None, frozenset(placements))
        result = pd.stabilise("ecr", tse_files, policy)
        self.assertEqual(result.moves, ["core/domain/enums/tse/Kind.kt"])
        self.assertIn(hand, {ur.import_file(fqn) for _, fqn, _ in result.kept})
        # a hand placement in the area itself survives stabilise and is not re-emitted
        placed_here = {hand: ur.Placement("ecr", "data")}
        merged_here = ur.merge_placements(placed_here, {"core/domain/enums/tse/Kind.kt": "ecr"})
        again = pd.stabilise("ecr", tse_files, pd.Policy(AREAS, {}, merged_here, frozenset(), None, frozenset(placed_here)))
        self.assertEqual(again.moves, ["core/domain/enums/tse/Kind.kt"])


if __name__ == "__main__":
    unittest.main()
