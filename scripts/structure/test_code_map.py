import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("gen_code_map", ROOT / "scripts/gen-code-map.py")
assert _spec is not None and _spec.loader is not None
gcm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gcm)
BASE = "org/example/project"


class CodeMapTest(unittest.TestCase):
    def test_code_map_scans_platform_roots(self):
        files = [f"platform/server/src/main/kotlin/{BASE}/features/auth/domain/AuthService.kt",
                 f"platform/data/src/main/kotlin/{BASE}/features/auth/database/tables/UserTable.kt",
                 f"platform/api/src/commonMain/kotlin/{BASE}/core/domain/dto/auth/LoginDto.kt",
                 f"platform/api/src/commonMain/kotlin/{BASE}/core/data/remote/KtorAuthApiClient.kt"]
        text = gcm.render(files)
        self.assertIn(f"- platform-server: `platform/server/src/main/kotlin/{BASE}/features/auth/` (1 file)", text)
        self.assertIn(f"- platform-data: `platform/data/src/main/kotlin/{BASE}/features/auth/` (1 file)", text)
        self.assertIn(f"- platform-api (dto): `platform/api/src/commonMain/kotlin/{BASE}/core/domain/dto/auth/` (1 file)", text)
        self.assertIn("`KtorAuthApiClient`", text)
        self.assertIn("`UserTable`", text)

    def test_a_deleted_blob_module_has_no_location(self):
        self.assertNotIn("core-data", [label for label, _, _ in gcm.LOCATIONS])
        files = [f"core-data/src/main/kotlin/{BASE}/features/auth/A.kt"]
        self.assertNotIn("core-data", gcm.render(files))

    def test_the_deleted_shared_blob_has_no_location_or_client_root(self):
        self.assertNotIn("shared (dto)", [label for label, _, _ in gcm.LOCATIONS])
        self.assertFalse([root for root in gcm.CLIENT_ROOTS if root.startswith("shared/")])

    def test_blob_and_platform_files_of_one_feature_share_a_section(self):
        files = [f"pos-server/src/main/kotlin/{BASE}/features/auth/A.kt", f"platform/server/src/main/kotlin/{BASE}/features/auth/B.kt"]
        text = gcm.render(files)
        self.assertEqual(text.count("### auth"), 1)
        self.assertLess(text.index("- pos-server:"), text.index("- platform-server:"))


if __name__ == "__main__":
    unittest.main()
