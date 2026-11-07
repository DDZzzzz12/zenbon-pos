import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("same_package_refs", ROOT / "scripts/structure/same_package_refs.py")
assert _spec is not None and _spec.loader is not None
sp = importlib.util.module_from_spec(_spec)
sys.modules["same_package_refs"] = sp
_spec.loader.exec_module(sp)

PKG = "package org.example.project.plugins\n"
A, B = "plugins/Claims.kt", "plugins/Key.kt"


def find(files, targets):
    return sp.find_same_package_refs(files, targets)


class SamePackageRefsTest(unittest.TestCase):
    def test_same_package_reference_across_areas_detected(self):
        files = {A: PKG + "fun f() = verifiedKey\n", B: PKG + "val verifiedKey = 1\n"}
        refs = find(files, {A: "platform-server", B: "ecr-server"})
        self.assertEqual([(r.user, r.name, r.declarer) for r in refs], [(A, "verifiedKey", B)])

    def test_import_is_not_a_same_package_reference(self):
        files = {A: PKG + "import org.example.project.plugins.Key\nfun f() = 1\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "ecr-server"}), [])

    def test_qualified_name_is_not_a_same_package_reference(self):
        files = {A: PKG + "val x = org.example.project.plugins.Key()\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "ecr-server"}), [])

    def test_same_target_not_reported(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "platform-server"}), [])

    def test_name_in_comment_ignored(self):
        files = {A: PKG + "// uses Key\n/* Key */\n/** Key */\nfun f() = 1\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "ecr-server"}), [])

    def test_higher_area_declaration_is_blocker(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        refs = find(files, {A: "platform-server", B: "fiscal-server"})
        self.assertEqual(refs[0].kind, "blocker")

    def test_host_declaration_is_blocker_for_an_area_file(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "host"})[0].kind, "blocker")

    def test_lower_area_same_module_is_not_reported(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "fiscal-server", B: "fiscal-server"}), [])
        self.assertEqual(find(files, {A: "host", B: "platform-server"}), [])

    def test_internal_declaration_used_across_modules_is_reported(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "internal class Key\n"}
        for user, declarer in (("platform-server", "platform-data"), ("fiscal-server", "platform-server"), ("platform-server", "fiscal-server"), ("host", "platform-server")):
            refs = find(files, {A: user, B: declarer})
            self.assertEqual([(r.name, r.kind) for r in refs], [("Key", "internal")], (user, declarer))
        self.assertEqual(sp.internal_names("internal suspend fun a() {}\n@Foo internal data class B(val x: Int)\nclass C\nprivate fun d() {}\n"), {"a", "B"})
        self.assertEqual(sp.declared_names("internal class Key\n"), {"Key"})

    def test_internal_used_within_same_module_not_reported(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "internal class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "platform-server"}), [])

    def test_lower_area_different_module_reported_informational(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        refs = find(files, {A: "fiscal-server", B: "platform-server"})
        self.assertEqual([(r.name, r.kind) for r in refs], [("Key", "lower-module")])
        report = sp.render_report(refs)
        self.assertIn("| fiscal | platform | 0 | 0 | 0 | 1 |", report)
        self.assertIn("1 lower-module", report)

    def test_extension_overload_on_other_receiver_is_not_a_reference(self):
        # shaped like PaymentSummaryOrder -> StatisticsPaymentNameExt: a sibling declares the same name on a receiver the user names
        C = "plugins/PaymentExt.kt"
        user = PKG + "fun List<PaymentDto.PaymentSummary>.sorted() = sortedBy { it.getLocalizedName(l) }\n"
        declarer = PKG + "fun StatisticsDto.PaymentRecordDto.getLocalizedName(l: Language): String = \"\"\n"
        other = PKG + "fun PaymentDto.PaymentSummary.getLocalizedName(l: Language): String = \"\"\n"
        targets = {A: "ecr-api", B: "backoffice-api", C: "ecr-api"}
        self.assertEqual(find({A: user, B: declarer, C: other}, targets), [])
        named = PKG + "fun g(r: StatisticsDto.PaymentRecordDto) = r.getLocalizedName(l)\n"
        self.assertEqual([r.name for r in find({A: named, B: declarer, C: other}, targets)], ["getLocalizedName"])
        bare = PKG + "fun g() = getLocalizedName(l)\n"
        self.assertEqual([r.name for r in find({A: bare, B: declarer, C: other}, targets)], ["getLocalizedName"])

    def test_extension_with_inferred_receiver_is_still_reported(self):
        files = {A: PKG + "fun g() = call.shopIdOrThrow()\n", B: PKG + "fun ApplicationCall.shopIdOrThrow(): Long = 1\n"}
        refs = find(files, {A: "accounting-server", B: "platform-server"})
        self.assertEqual([(r.name, r.kind) for r in refs], [("shopIdOrThrow", "lower-module")])

    def test_other_module_same_area_is_informational(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "platform-data"})[0].kind, "module")

    def test_extension_function_and_private_declarations(self):
        files = {A: PKG + "fun g() = call.getClaims() + hidden\n",
                 B: PKG + "suspend fun ApplicationCall.getClaims() = 1\nprivate val hidden = 2\n"}
        refs = find(files, {A: "platform-server", B: "ecr-server"})
        self.assertEqual([r.name for r in refs], ["getClaims"])

    def test_nested_receiver_extension_declares_the_function_name(self):
        files = {A: PKG + "fun f() = x.localName()\n", B: PKG + "fun Outer.Inner.localName(): String = \"\"\n"}
        refs = find(files, {A: "platform-server", B: "ecr-server"})
        self.assertEqual([r.name for r in refs], ["localName"])

    def test_name_the_user_declares_itself_is_not_a_reference(self):
        files = {A: PKG + "fun Foo.getName(): String = \"\"\n", B: PKG + "fun Bar.getName(): String = getName()\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "ecr-server"}), [])
        self.assertEqual(find(files, {A: "ecr-server", B: "platform-server"}), [])

    def test_different_packages_not_compared(self):
        files = {A: PKG + "fun f() = Key()\n", B: "package org.example.project.other\nclass Key\n"}
        self.assertEqual(find(files, {A: "platform-server", B: "ecr-server"}), [])

    def test_report_counts_and_caveat(self):
        files = {A: PKG + "fun f() = Key()\n", B: PKG + "class Key\n"}
        report = sp.render_report(find(files, {A: "platform-server", B: "fiscal-server"}))
        self.assertIn("over-inclusive", report)
        self.assertIn("| platform | fiscal | 1 | 0 | 0 | 0 |", report)


if __name__ == "__main__":
    unittest.main()
