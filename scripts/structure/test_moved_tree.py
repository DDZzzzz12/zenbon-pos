"""A file keeps its classification when the mover relocates it from a blob module into a platform/* module (Stage B2-1b)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import extraction_blockers as eb  # noqa: E402
import move_manifest as mm  # noqa: E402
import same_package_refs as sp  # noqa: E402
import upward_refs as ur  # noqa: E402

ROOT = ur.ROOT
BASE = "org/example/project"
AREAS = ur.load_table((ROOT / "config/structure/package-areas.tsv").read_text(encoding="utf-8"))
OVERRIDES = ur.load_overrides((ROOT / "config/structure/package-overrides.tsv").read_text(encoding="utf-8"))
PKG = "org.example.project."

# (relative dir below src/<set>/kotlin/org/example/project, file, text, blob module, source set, moved dir)
FIXTURE = [
    ("core/utils", "Fmt.kt", PKG + "core.utils", "import " + PKG + "features.tse.domain.service.TseService\nclass Fmt\n", "shared", "commonMain", "platform/api"),
    ("core/data/remote", "KtorAuthApiClient.kt", PKG + "core.data.remote", "import " + PKG + "core.domain.dto.order.OrderDto\nclass K\n", "shared", "commonMain", "platform/api"),
    ("features/auth/domain/service", "AuthX.kt", PKG + "features.auth.domain.service", "import " + PKG + "features.order.domain.service.OrderService\nclass AuthX\n", "pos-server", "main", "platform/server"),
    ("features/auth/database/tables", "UserT.kt", PKG + "features.auth.database.tables", "class UserT\n", "core-data", "main", "platform/data"),
]


def tree(moved: bool) -> dict[str, str]:
    files = {}
    for directory, name, package, body, blob, source_set, dest in FIXTURE:
        files[f"{dest if moved else blob}/src/{source_set}/kotlin/{BASE}/{directory}/{name}"] = f"package {package}\n{body}"
    return files


def shape(files: dict[str, str]):
    """Everything a report derives from a tree, with the module part of every path/source stripped."""
    manifest = mm.build_manifest(files, AREAS, OVERRIDES)
    refs = ur.find_upward(files, AREAS, OVERRIDES, {})
    found = eb.blockers(manifest, refs, collisions=eb.colliding_packages(files))
    targets, _ = sp.effective_targets(files)
    return {
        "targets": sorted((p, t, n) for _, _, p, t, n in manifest),
        "refs": sorted((r.from_area, r.imported_fqn, r.to_area) for r in refs),
        "areas": sorted(ur.area_of_file(p, AREAS, OVERRIDES) or "?" for p in files),
        "blockers": {a: sorted((ur.rel_path(b.from_file), b.from_target_module, b.imported_fqn, b.to_target_module, b.kind) for b in bs) for a, bs in found.items()},
        "same_package_targets": sorted((ur.rel_path(p), t) for p, t in targets.items()),
    }


class MovedTreeTest(unittest.TestCase):
    def test_moved_files_keep_area_targets_references_and_blockers(self):
        before, after = shape(tree(False)), shape(tree(True))
        self.assertEqual(before, after)
        self.assertTrue(after["refs"], "the fixture must carry upward references or the comparison proves nothing")
        self.assertTrue(after["blockers"], "the fixture must carry blockers")

    def test_moved_rows_are_settled_and_blob_rows_pending(self):
        moved = mm.build_manifest(tree(True), AREAS, OVERRIDES)
        self.assertTrue(all(module == target for module, _, _, target, _ in moved))
        blob = mm.build_manifest(tree(False), AREAS, OVERRIDES)
        self.assertTrue(all(module != target for module, _, _, target, _ in blob))

    def test_half_moved_package_is_pending_until_the_rest_moves(self):
        files = tree(False)
        files[f"platform/data/src/main/kotlin/{BASE}/core/utils/Other.kt"] = f"package {PKG}core.utils\nclass Other\n"
        found = eb.blockers(mm.build_manifest(files, AREAS, OVERRIDES), [])
        self.assertEqual([b.imported_fqn for b in found["platform"]], ["core.utils"])  # shared core.utils still pending
        files = {p: t for p, t in files.items() if not p.startswith("shared/src/commonMain/kotlin/org/example/project/core/utils")}
        files[f"platform/api/src/commonMain/kotlin/{BASE}/core/utils/Fmt.kt"] = f"package {PKG}core.utils\nclass Fmt\n"
        manifest = mm.build_manifest(files, AREAS, OVERRIDES)
        self.assertEqual(eb.blockers(manifest, []), {})
        self.assertEqual([s.package for s in eb.settled_splits(manifest)], ["core.utils"])

    def test_file_override_still_applies_after_the_move(self):
        rel = "core/utils/Fmt.kt"
        for moved in (False, True):
            files = tree(moved)
            rows = mm.file_rows(files, AREAS, OVERRIDES, {rel: "ecr"})
            self.assertEqual([(r.target, r.path) for r in rows], [("ecr-api", rel)])
            self.assertEqual(mm.file_rows(files, AREAS, OVERRIDES, {rel: "platform"}), [])  # an override equal to the module adds nothing


if __name__ == "__main__":
    unittest.main()
