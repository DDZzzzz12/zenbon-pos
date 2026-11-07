package org.example.project.core.presentation.strings

import org.example.project.testutils.SourceRoots
import org.example.project.ui.components.PosDialogScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `StringKey` the **shared** catalogue declares must be translated in all three languages.
 *
 * The sibling of [AdminKeysAreTranslatedTest], which closes the identical hole for adminApp. This one
 * covers the other side of the wall: the ~71 `*Keys.kt` objects under
 * `shared/…/core/presentation/strings/keys/` that composeApp, shared-ui and adminApp all draw on.
 *
 * **The hole this closes.** `shared`'s `TranslationCompletenessTest` calls
 * [TranslationRegistry.findMissingTranslations], which builds its key universe from the translation
 * maps themselves — `translations.values.flatMap { it.keys }`. It therefore compares the three
 * languages against *each other*: a key translated in two and forgotten in the third is caught, but a
 * key declared in a `*Keys.kt` object and added to **none** of the maps never enters the union and is
 * invisible. `TranslationRegistry.get` falls back to `key.id`, so such a key renders its raw
 * identifier — `floor_plan.status_occupied` — on screen, and ships green. That test names the blind
 * spot in its own KDoc; this is the test it says cannot be written in `commonTest`.
 *
 * **Why this is a source scan and not reflection.** Enumerating an object's declared properties needs
 * `kotlin-reflect`, which is JVM-only, so the check cannot live in `shared`'s `commonTest` alongside
 * the test it completes. Scanning source text works on every target and, unlike reflection, also sees
 * a key that no language file mentions at all. It lives *here* rather than in a new `shared/jvmTest`
 * because it reuses [PosDialogScan], which is `internal` to composeApp's desktop test source set —
 * the same reason [AdminKeysAreTranslatedTest] reaches across into adminApp from here.
 *
 * **Matching is object-qualified, and that is the whole difficulty.** adminApp has one key object, so
 * a bare property name identifies a key. Here there are ~71, and bare names collide across them:
 * `CommonKeys.save` and a hypothetical `DashboardKeys.save` are different keys, and the former being
 * translated says nothing about the latter. So each declaration is paired with the object that
 * declares it and the translation files are searched for `<ObjectName>.<PropertyName> to`. Matching on
 * the `StringKey("…")` id would be wrong twice over: the maps key by property reference, not by the
 * string literal, and the literals are blanked out before the scan anyway.
 *
 * A key may be translated in **any** file of its language — the maps are split into five sets
 * (Common/Dashboard/Features/Rules/Settings) and the key object decides which set it lands in — so the
 * question asked is "present in this language at all", never "present in a particular file".
 *
 * **How this fails safe.** A textual scanner is brittle in a way reflection is not, and a pattern that
 * silently matches nothing would report every key translated — worse than having no test. So the scan
 * is checked before it is trusted:
 *  - the keys directory must yield at least [MINIMUM_EXPECTED_KEY_FILES] files and
 *    [MINIMUM_EXPECTED_KEYS] declarations (71 files / 2,081 keys when this was written);
 *  - each language must yield at least [MINIMUM_EXPECTED_TRANSLATION_FILES] files and
 *    [MINIMUM_EXPECTED_KEYS] entries, so a renamed set or a broken pattern fails loudly;
 *  - every `StringKey(` built under `keys/` must be attributable to a declaration the scanner
 *    recognized, so a key written in a shape the pattern misses fails rather than slips;
 *  - every key file must declare exactly one object, since a second would make a bare `val` ambiguous
 *    to qualify — the scanner refuses rather than guesses;
 *  - two objects may not declare the same qualified name, so the universe compared against is exactly
 *    what the files declare;
 *  - a translation entry naming something no key object declares fails, since the file could not
 *    compile — it can only mean the pattern matched text that is not a map entry;
 *  - no file outside `keys/` may declare a `StringKey`, so a second catalogue fails instead of going
 *    unscanned, and that walk has its own source-count floor.
 *
 * Sources are comment- and string-stripped first, so a commented-out key cannot demand a translation
 * and a translated *value* that happens to contain `CommonKeys.foo to` cannot invent one.
 */
class SharedKeysAreTranslatedTest {
    private companion object {
        /**
         * The shared strings package, in `shared` today and in `platform/api` once the platform
         * extraction moved it; exactly one of the two must hold it ([SourceRoots.sourceDirUnder]).
         */
        val STRINGS_ROOT: File by lazy {
            SourceRoots.sourceDirUnder(SourceRoots.sharedCommonMainRoots(), "org/example/project/core/presentation/strings")
        }

        val KEYS_DIR: File by lazy { File(STRINGS_ROOT, "keys") }

        val TRANSLATIONS_DIR: File by lazy { File(STRINGS_ROOT, "translations") }

        /** Translation files are named `<Language><Set>Strings.kt`; every set of a language counts as that language. */
        val LANGUAGES = listOf("English", "German", "Chinese")

        /**
         * Roots searched for a key catalogue that escaped `keys/`. composeApp's own desktop test tree is
         * deliberately outside them — this file and [AdminKeysAreTranslatedTest] carry the pattern itself.
         */
        val CATALOGUE_SEARCH_ROOTS: List<File> by lazy {
            listOf(SourceRoots.tillRoot()) + SourceRoots.sharedCommonMainRoots() + listOf(SourceRoots.uiSharedRoot())
        }

        /**
         * Floor for the scanner's own output, well under the 2,081 keys present when this was written.
         * It exists so a pattern that stops matching fails instead of reporting a clean sweep — raise it
         * freely, but never lower it to make a failure go away.
         */
        const val MINIMUM_EXPECTED_KEYS = 1800

        /** 71 key objects existed when this was written. */
        const val MINIMUM_EXPECTED_KEY_FILES = 50

        /** Five sets per language — Common, Dashboard, Features, Rules, Settings. */
        const val MINIMUM_EXPECTED_TRANSLATION_FILES = 5

        /** 982 sources sat under [CATALOGUE_SEARCH_ROOTS], outside `keys/`, when this was written. */
        const val MINIMUM_EXPECTED_SCANNED_SOURCES = 500

        /**
         * Kotlin files under [CATALOGUE_SEARCH_ROOTS], `keys/` included, on 2026-10-27 before the platform
         * extraction moved any: the exact count, so a root that drops out fails rather than thins the scan.
         */
        const val MINIMUM_SCANNED_FILES = 1421

        /** Matches the wrapped form too, where `StringKey(` sits on the line after the `=`. */
        val DECLARATION = Regex("""\bval\s+(\w+)\s*=\s*StringKey\(""")

        val CONSTRUCTION = Regex("""\bStringKey\(""")

        val OBJECT = Regex("""\bobject\s+(\w+)""")

        /** Translation maps key by property reference, never by the raw `"common.save"` id. */
        val TRANSLATION_ENTRY = Regex("""\b([A-Z]\w*)\.(\w+)\s+to\b""")
    }

    /** Source with comments, string and character literals blanked out, so only code is searched. */
    private fun codeOf(file: File): String {
        assertTrue(
            file.isFile,
            "shared source not found at ${file.absolutePath}. This guard scans shared from composeApp because " +
                "it reuses PosDialogScan, which is internal to this test source set; if the sources moved, fix " +
                "the path here.",
        )
        return PosDialogScan.stripCommentsAndStrings(file.readText())
    }

    private fun keyFiles(): List<File> {
        assertTrue(
            KEYS_DIR.isDirectory,
            "shared key objects not found at ${KEYS_DIR.absolutePath}. Without them this guard would compare an " +
                "empty key universe against the translations and report a clean sweep.",
        )
        val files =
            KEYS_DIR
                .listFiles()
                ?.filter { it.isFile && it.extension == "kt" }
                .orEmpty()
                .sortedBy { it.name }

        assertTrue(
            files.size >= MINIMUM_EXPECTED_KEY_FILES,
            "found only ${files.size} key files under ${KEYS_DIR.absolutePath}, expected at least " +
                "$MINIMUM_EXPECTED_KEY_FILES. Fix the path — a scan over too few files reports the keys it never " +
                "looked at as translated.",
        )
        return files
    }

    /**
     * `ObjectName.propertyName` for every key [file] declares.
     *
     * The qualifier is the file's single declared object. Every key file holds exactly one, and that is
     * asserted rather than assumed: with two, a bare `val` could belong to either and the scanner would
     * have to guess which object a translation must name.
     */
    private fun declarationsIn(file: File): List<String> {
        val code = codeOf(file)
        val objects = OBJECT.findAll(code).map { it.groupValues[1] }.toList()

        assertEquals(
            1,
            objects.size,
            "${file.name} declares ${objects.size} objects ($objects), but this guard qualifies each key with " +
                "its file's single object. Keep one key object per file (project rule 4), or teach the scanner " +
                "to track the enclosing object — do not let it guess.",
        )

        val owner = objects.single()
        return DECLARATION.findAll(code).map { "$owner.${it.groupValues[1]}" }.toList()
    }

    private fun declaredKeys(): Set<String> = keyFiles().flatMap { declarationsIn(it) }.toSet()

    private fun translationFilesFor(language: String): List<File> {
        assertTrue(
            TRANSLATIONS_DIR.isDirectory,
            "shared translations not found at ${TRANSLATIONS_DIR.absolutePath}; this guard would otherwise " +
                "report every key as untranslated, or none.",
        )
        val files =
            TRANSLATIONS_DIR
                .listFiles()
                ?.filter { it.isFile && it.extension == "kt" && it.name.startsWith(language) }
                .orEmpty()
                .sortedBy { it.name }

        assertTrue(
            files.size >= MINIMUM_EXPECTED_TRANSLATION_FILES,
            "found only ${files.size} $language translation files in ${TRANSLATIONS_DIR.absolutePath}, expected " +
                "at least $MINIMUM_EXPECTED_TRANSLATION_FILES. Files must be named `$language<Set>Strings.kt`; " +
                "if a set was renamed, fix the pattern rather than lowering the floor — an unmatched file makes " +
                "its keys look untranslated, and a language matching nothing makes every key look missing.",
        )
        return files
    }

    /** Every `Object.property` named as a map key anywhere in [language]'s files. */
    private fun translatedKeys(language: String): Set<String> =
        translationFilesFor(language)
            .flatMap { file ->
                TRANSLATION_ENTRY.findAll(codeOf(file)).map { "${it.groupValues[1]}.${it.groupValues[2]}" }
            }.toSet()

    @Test
    fun `the scanner parses every shared key object`() {
        val files = keyFiles()
        val declared = files.flatMap { declarationsIn(it) }

        assertTrue(
            declared.size >= MINIMUM_EXPECTED_KEYS,
            "parsed only ${declared.size} key declarations from ${files.size} files under ${KEYS_DIR.absolutePath}, " +
                "expected at least $MINIMUM_EXPECTED_KEYS. Either the declarations no longer read " +
                "`val name = StringKey(\"…\")` or the files moved. Fix the pattern — a scan that matches nothing " +
                "reports every key as translated, which is worse than having no guard.",
        )

        val duplicates =
            declared
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
        assertTrue(
            duplicates.isEmpty(),
            "the same qualified key name was parsed more than once: $duplicates. Two objects sharing a name would " +
                "make the universe this guard compares against smaller than what the files declare, hiding one of " +
                "them.",
        )

        val constructions = files.map { file -> CONSTRUCTION.findAll(codeOf(file)).count() }.sum()
        assertEquals(
            constructions,
            declared.size,
            "a key file builds a StringKey that the scanner did not recognize as a `val name = StringKey(\"…\")` " +
                "declaration, so that key would ship unguarded. Declare it in that form. A StringKey built from a " +
                "runtime value cannot be translated at all and does not belong in this catalogue.",
        )
    }

    @Test
    fun `the scanner parses every shared translation file`() {
        val declared = declaredKeys()

        LANGUAGES.forEach { language ->
            val translated = translatedKeys(language)

            assertTrue(
                translated.size >= MINIMUM_EXPECTED_KEYS,
                "parsed only ${translated.size} entries for $language, expected at least $MINIMUM_EXPECTED_KEYS. " +
                    "Entries must read `SomeKeys.name to \"…\"`; if that shape changed, fix the pattern rather " +
                    "than lowering the floor.",
            )

            val undeclared = (translated - declared).sorted()
            assertTrue(
                undeclared.isEmpty(),
                "$language's maps appear to name keys that no shared key object declares: $undeclared. The files " +
                    "could not compile if they really did, so the scanner matched text that is not a map entry — " +
                    "tighten the pattern.",
            )
        }
    }

    @Test
    fun `every declared shared key is translated in all three languages`() {
        val declared = declaredKeys()

        val missing =
            LANGUAGES
                .associateWith { language -> (declared - translatedKeys(language)).sorted() }
                .filterValues { it.isNotEmpty() }

        assertTrue(
            missing.isEmpty(),
            buildString {
                append("Some shared StringKeys carry no translation at all in one or more languages.\n")
                append("shared's TranslationCompletenessTest cannot see these: findMissingTranslations() compares\n")
                append("the language maps against each other, so a key translated in NO language never enters the\n")
                append("comparison. Such a key renders its raw id (e.g. common.save) on screen.\n")
                append("Add each to any one of that language's *Strings.kt sets — whichever set its key object\n")
                append("already lives in (Common / Dashboard / Features / Rules / Settings).\n")
                missing.forEach { (language, names) ->
                    append("\n$language is missing ${names.size} key(s):\n")
                    names.forEach { append("  $it\n") }
                }
            },
        )
    }

    @Test
    fun `no key catalogue escapes the shared keys directory`() {
        val sources =
            SourceRoots
                .kotlinFiles(CATALOGUE_SEARCH_ROOTS)
                .also { SourceRoots.requireScanned("SharedKeysAreTranslatedTest", it.size, MINIMUM_SCANNED_FILES) }
                .filterNot { it.canonicalPath.startsWith(KEYS_DIR.canonicalPath) }

        assertTrue(
            sources.size >= MINIMUM_EXPECTED_SCANNED_SOURCES,
            "expected to find the shared, shared-ui and composeApp sources under " +
                "${CATALOGUE_SEARCH_ROOTS.map { it.absolutePath }}; found ${sources.size}. Without them this test " +
                "would find no stray catalogue simply by looking at nothing.",
        )

        val strays =
            sources
                .filter { DECLARATION.containsMatchIn(PosDialogScan.stripCommentsAndStrings(it.readText())) }
                .map { it.name }
                .sorted()

        assertTrue(
            strays.isEmpty(),
            "these files declare StringKey properties outside ${KEYS_DIR.path}, so this guard does not scan them " +
                "and their keys could ship with no translation: $strays. Move the keys into a `{Feature}Keys.kt` " +
                "under that directory (project rule 10), or extend this test to cover the new catalogue.",
        )
    }
}
