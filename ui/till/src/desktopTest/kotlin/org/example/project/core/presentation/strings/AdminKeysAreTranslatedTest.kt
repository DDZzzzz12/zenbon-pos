package org.example.project.core.presentation.strings

import org.example.project.ui.components.PosDialogScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `StringKey` adminApp declares must be translated in all three admin language files.
 *
 * **The hole this closes.** adminApp's own `AdminTranslationCompletenessTest` calls
 * `TranslationRegistry.findMissingTranslations()`, which builds its key universe from the
 * translation maps themselves — `translations.values.flatMap { it.keys }`. It therefore compares the
 * three languages against *each other*: a key translated in two languages and forgotten in the third
 * is caught, but a key declared in `AdminStringKeys` and added to **none** of the maps never enters
 * the union and is invisible. Such a key renders its raw id — `admin.staff.title` — in the panel, and
 * ships green.
 *
 * **Why this is a source scan and not reflection.** Kotlin/Wasm has no `kotlin-reflect`, so adminApp
 * cannot enumerate `AdminStringKeys`' properties at runtime; and its tests run in ChromeHeadless
 * under karma, where there is no filesystem to walk. Hence the scan lives here, in a JVM test source
 * set, exactly as `NoRoleGatesInScreensTest` and `NoNestedScrollInPosDialogTest` reach across for the
 * same reason. `composeApp`'s `tasks.withType<Test>` already registers adminApp's sources as a task
 * input, so this guard re-runs when adminApp changes instead of staying UP-TO-DATE.
 *
 * **Strengthening `findMissingTranslations()` cannot work.** It lives in `shared/commonMain` and the
 * dependency runs the wrong way — it cannot name `AdminStringKeys`, which lives in adminApp. Passing
 * the universe in (`findMissingTranslations(allKeys)`) only moves the omission: adminApp would hand it
 * a hand-maintained list of the very properties that get forgotten.
 *
 * **How this fails safe.** A textual scanner is brittle in a way reflection is not — it can be fooled
 * by formatting, and a pattern that silently matches nothing would report every key translated, which
 * is worse than no test. So the scan is checked before it is trusted:
 *  - each file must yield at least [MINIMUM_EXPECTED_KEYS] matches (1,166 were declared and
 *    translated when this was written), so a moved file or a broken pattern fails loudly;
 *  - every `StringKey(` built in `AdminStringKeys.kt` must be attributable to a declaration the
 *    scanner recognized, so a key written in a shape the pattern misses fails rather than slips;
 *  - a translation entry naming something `AdminStringKeys` does not declare fails, since the file
 *    would not compile — it can only mean the pattern matched text that is not an entry;
 *  - no other adminApp file may declare keys, so a second catalogue fails instead of going unscanned.
 *
 * Sources are comment- and string-stripped first, so a commented-out key cannot demand a translation
 * and a translated *value* that happens to contain `AdminStringKeys.Foo to` cannot invent one.
 *
 * Shared, composeApp and shared-ui keys are **not** covered here; `shared`'s `TranslationCompletenessTest`
 * has the identical blind spot and documents it as one.
 */
class AdminKeysAreTranslatedTest {
    private companion object {
        /** Relative to composeApp's project directory, which is the working directory of its test tasks. */
        val ADMIN_SOURCE_ROOT = File("../backoffice/src/wasmJsMain/kotlin")

        val STRINGS_DIR = File(ADMIN_SOURCE_ROOT, "org/example/project/admin/core/presentation/strings")

        val KEYS_FILE = File(STRINGS_DIR, "AdminStringKeys.kt")

        val TRANSLATION_FILES =
            listOf("English", "German", "Chinese").associateWith { language ->
                File(STRINGS_DIR, "translations/${language}AdminStrings.kt")
            }

        /**
         * Floor for the scanner's own output, well under the 1,166 keys present when this was written.
         * It exists so a pattern that stops matching fails instead of reporting a clean sweep — raise
         * it freely, but never lower it to make a failure go away.
         */
        const val MINIMUM_EXPECTED_KEYS = 1000

        /** Matches the wrapped form too, where `StringKey(` sits on the line after the `=`. */
        val DECLARATION = Regex("""\bval\s+(\w+)\s*=\s*StringKey\(""")

        val CONSTRUCTION = Regex("""\bStringKey\(""")

        /** Translation maps key by property reference, never by the raw `"admin.…"` id. */
        val TRANSLATION_ENTRY = Regex("""\bAdminStringKeys\.(\w+)\s+to\b""")
    }

    /** Source with comments, string and character literals blanked out, so only code is searched. */
    private fun codeOf(file: File): String {
        assertTrue(
            file.isFile,
            "adminApp source not found at ${file.absolutePath}. This guard scans adminApp from composeApp " +
                "because adminApp's own tests run in a browser; if the sources moved, fix the path here.",
        )
        return PosDialogScan.stripCommentsAndStrings(file.readText())
    }

    private fun declaredKeyNames(): List<String> = DECLARATION.findAll(codeOf(KEYS_FILE)).map { it.groupValues[1] }.toList()

    private fun translatedKeyNames(file: File): Set<String> = TRANSLATION_ENTRY.findAll(codeOf(file)).map { it.groupValues[1] }.toSet()

    @Test
    fun `the scanner parses AdminStringKeys`() {
        val code = codeOf(KEYS_FILE)
        val declared = DECLARATION.findAll(code).map { it.groupValues[1] }.toList()

        assertTrue(
            declared.size >= MINIMUM_EXPECTED_KEYS,
            "parsed only ${declared.size} key declarations from AdminStringKeys.kt, expected at least " +
                "$MINIMUM_EXPECTED_KEYS. Either the declarations no longer read `val Name = StringKey(\"…\")` " +
                "or the file moved. Fix the pattern — a scan that matches nothing reports every key as " +
                "translated, which is worse than having no guard.",
        )

        assertEquals(
            declared.size,
            declared.toSet().size,
            "the scanner matched the same property name more than once in AdminStringKeys.kt, so the key " +
                "universe it compares against is not what the file declares",
        )

        assertEquals(
            CONSTRUCTION.findAll(code).count(),
            declared.size,
            "AdminStringKeys.kt builds a StringKey that the scanner did not recognize as a " +
                "`val Name = StringKey(\"…\")` declaration, so that key would ship unguarded. Declare it in " +
                "that form. A StringKey built from a runtime value cannot be translated at all and does not " +
                "belong in this catalogue — see kpiLabelKey and checkLabelKey in the reports components for " +
                "where such a fallback lives.",
        )
    }

    @Test
    fun `the scanner parses every admin translation file`() {
        val declared = declaredKeyNames().toSet()

        TRANSLATION_FILES.forEach { (language, file) ->
            val translated = translatedKeyNames(file)

            assertTrue(
                translated.size >= MINIMUM_EXPECTED_KEYS,
                "parsed only ${translated.size} entries from ${file.name}, expected at least " +
                    "$MINIMUM_EXPECTED_KEYS. Entries must read `AdminStringKeys.Name to \"…\"`; if that shape " +
                    "changed, fix the pattern rather than lowering the floor.",
            )

            val undeclared = (translated - declared).sorted()
            assertTrue(
                undeclared.isEmpty(),
                "$language's map appears to name keys AdminStringKeys does not declare: $undeclared. The file " +
                    "could not compile if it really did, so the scanner matched text that is not a map entry — " +
                    "tighten the pattern.",
            )
        }
    }

    @Test
    fun `every declared admin key is translated in all three languages`() {
        val declared = declaredKeyNames().toSet()

        val missing =
            TRANSLATION_FILES
                .mapValues { (_, file) -> (declared - translatedKeyNames(file)).sorted() }
                .filterValues { it.isNotEmpty() }

        assertTrue(
            missing.isEmpty(),
            buildString {
                append("Some AdminStringKeys carry no translation at all in one or more languages.\n")
                append("adminApp's AdminTranslationCompletenessTest cannot see these: it compares the language\n")
                append("maps against each other, so a key translated in NO language never enters the comparison.\n")
                append("Such a key renders its raw id (e.g. admin.staff.title) in the panel.\n")
                missing.forEach { (language, names) ->
                    append("\n${language}AdminStrings.kt is missing ${names.size} key(s):\n")
                    names.forEach { append("  AdminStringKeys.$it\n") }
                }
            },
        )
    }

    @Test
    fun `AdminStringKeys is the only admin key catalogue`() {
        val sources =
            ADMIN_SOURCE_ROOT
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()

        assertTrue(
            sources.size > 200,
            "expected to find adminApp's sources under ${ADMIN_SOURCE_ROOT.absolutePath}; found ${sources.size}. " +
                "Without them this test would find no stray catalogue simply by looking at nothing.",
        )

        val strays =
            sources
                .filterNot { it.canonicalPath == KEYS_FILE.canonicalPath }
                .filter { DECLARATION.containsMatchIn(PosDialogScan.stripCommentsAndStrings(it.readText())) }
                .map { it.name }

        assertTrue(
            strays.isEmpty(),
            "these adminApp files declare StringKey properties that this guard does not scan, so their keys " +
                "could ship with no translation: $strays. Either declare the keys in AdminStringKeys, or extend " +
                "this test to cover the new catalogue and its translation files.",
        )
    }
}
