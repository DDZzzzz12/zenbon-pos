package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.testutils.SourceRoots
import org.example.project.ui.components.PosDialogScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * admin20 FAIL-A20: the extra delete dialog read literally „{0} wirklich löschen?“ because
 * `ExtrasManagerDialog` called `str(ExtrasTabKeys.deleteConfirmation)` — the one-argument overload,
 * which never substitutes — on a key whose template carries `{0}`. It compiles, it renders, and the
 * placeholder reaches the operator.
 *
 * A source scan over composeApp, shared-ui and adminApp: every `str(Obj.key)` call with **no**
 * arguments is resolved to its English and German templates, and fails if either contains a
 * positional placeholder. Shared keys resolve by reflection against [defaultTranslations]; adminApp's
 * keys live in a wasm-only module, so their templates are read from its translation source files
 * (the same reach-across [AdminKeysAreTranslatedTest] uses).
 *
 * The scan is checked before it is trusted: it must find a floor of single-argument calls and resolve
 * most of them, so a pattern that stops matching fails instead of reporting a clean sweep.
 */
class NoPlaceholderKeyWithoutArgsTest {
    private companion object {
        /** ui-till, ui-shared and ui-backoffice; none of them moves in the platform extraction. */
        val ROOTS = listOf(SourceRoots.tillRoot(), SourceRoots.uiSharedRoot(), SourceRoots.uiBackofficeRoot())

        /** Kotlin files under [ROOTS] on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_SCANNED_FILES = 1536

        const val KEYS_PACKAGE = "org.example.project.core.presentation.strings.keys"

        val ADMIN_TRANSLATIONS =
            listOf("English", "German").map { language ->
                File(
                    SourceRoots.uiBackofficeRoot(),
                    "org/example/project/admin/core/presentation/strings/translations/${language}AdminStrings.kt",
                )
            }

        /** `str(SomeKeys.someKey)` with nothing after the key — the overload that never substitutes. */
        val NO_ARG_CALL = Regex("""\bstr\(\s*(\w+)\.(\w+)\s*\)""")

        val ADMIN_ENTRY = Regex("""AdminStringKeys\.(\w+)\s+to\s+"((?:[^"\\]|\\.)*)"""")

        val PLACEHOLDER = Regex("""\{\d+}""")

        /**
         * Keys whose template is fetched once and substituted by hand with `.replace("{0}", …)`.
         * Each entry names why; never add one to silence a real `str(key)` slip.
         */
        val SUBSTITUTED_BY_HAND =
            setOf(
                // KassenbuchScreen.rememberBookLabeller: resolved once outside the row loop, replaced per row.
                "AdminStringKeys.KassenbuchUnknownBook",
            )

        /** Floor for the scanner's own output; raise freely, never lower to silence a failure. */
        const val MINIMUM_CALLS = 1000
    }

    private val sharedTemplates: Map<String, List<String>> by lazy {
        val maps = defaultTranslations()
        listOf(Language.ENGLISH, Language.GERMAN)
            .flatMap { lang -> maps[lang].orEmpty().map { (key, value) -> key.id to value } }
            .groupBy({ it.first }, { it.second })
    }

    private val adminTemplates: Map<String, List<String>> by lazy {
        ADMIN_TRANSLATIONS
            .flatMap { file ->
                assertTrue(file.isFile, "admin translations not found at ${file.absolutePath}")
                ADMIN_ENTRY.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
            }.groupBy({ it.first }, { it.second })
    }

    private fun sharedKeyId(
        objectName: String,
        property: String,
    ): String? =
        runCatching {
            val cls = Class.forName("$KEYS_PACKAGE.$objectName")
            val instance = cls.getField("INSTANCE").get(null)
            val getter = cls.getMethod("get" + property.replaceFirstChar { it.uppercase() })
            (getter.invoke(instance) as? StringKey)?.id
        }.getOrNull()

    /** The templates the key renders with, or null when the scanner cannot resolve the reference. */
    private fun templatesOf(
        objectName: String,
        property: String,
    ): List<String>? =
        if (objectName == "AdminStringKeys") {
            adminTemplates[property]
        } else {
            sharedKeyId(objectName, property)?.let { sharedTemplates[it] }
        }

    @Test
    fun `no placeholder key is resolved through the overload that never substitutes`() {
        var calls = 0
        var resolved = 0
        val offenders = mutableListOf<String>()

        val files = SourceRoots.kotlinFiles(ROOTS)
        SourceRoots.requireScanned("NoPlaceholderKeyWithoutArgsTest", files.size, MINIMUM_SCANNED_FILES)
        files.forEach { file ->
            val code = PosDialogScan.stripCommentsAndStrings(file.readText())
            NO_ARG_CALL.findAll(code).forEach { match ->
                calls++
                val (objectName, property) = match.destructured
                val templates = templatesOf(objectName, property) ?: return@forEach
                resolved++
                val byHand = "$objectName.$property" in SUBSTITUTED_BY_HAND
                if (!byHand && templates.any { PLACEHOLDER.containsMatchIn(it) }) {
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders += "${file.path}:$line: str($objectName.$property) — template ${templates.first()}"
                }
            }
        }

        assertTrue(calls >= MINIMUM_CALLS, "found only $calls `str(Obj.key)` calls; the scan pattern no longer matches")
        assertTrue(
            resolved * 2 >= calls,
            "resolved only $resolved of $calls `str(Obj.key)` calls to a template; the key lookup is broken",
        )
        assertTrue(
            offenders.isEmpty(),
            "these calls render a raw `{0}` placeholder — pass the argument via `str(key, arg)`:\n" +
                offenders.joinToString("\n"),
        )
    }
}
