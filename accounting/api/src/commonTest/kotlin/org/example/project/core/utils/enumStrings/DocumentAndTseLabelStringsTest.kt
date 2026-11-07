package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raw enum names and upper-case folder names were showing on adminApp screens. */
class DocumentAndTseLabelStringsTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    private fun collectIfNotReal(
        misses: MutableList<String>,
        context: String,
        raw: String,
        label: String,
    ) {
        // CJK has no case, so only a Latin label can "shout".
        val shoutsItsName = label.none { it.isLowerCase() || it.code > 0x2E80 } && label.any { it.isUpperCase() } && label.length > 5
        if (label.isBlank() || label == raw || label.startsWith("enum.") || shoutsItsName) {
            misses += "$context: '$label' for $raw"
        }
    }

    @Test
    fun documentTypeHasARealLabelInEveryLanguage() {
        val misses = mutableListOf<String>()
        for (language in Language.entries) {
            for (type in DocumentType.entries) collectIfNotReal(misses, "$language", type.name, type.getName(language))
        }
        assertTrue(misses.isEmpty(), misses.toString())
        assertEquals("Kontoauszug", DocumentType.BANK_STATEMENT.getName(Language.GERMAN))
        assertEquals("Invoice", DocumentType.INVOICE.getName(Language.ENGLISH))
        assertEquals("银行对账单", DocumentType.BANK_STATEMENT.getName(Language.CHINESE))
    }

    @Test
    fun folderNamesAreUntouched() {
        assertEquals("KONTO-AUSZUEGE", DocumentType.BANK_STATEMENT.folderName)
        assertEquals("BANKAUSZÜGE", DocumentType.BANK_STATEMENT.displayNameDe)
    }

    @Test
    fun explanationCategoryHasARealLabelInEveryLanguage() {
        val misses = mutableListOf<String>()
        for (language in Language.entries) {
            for (category in TseExplanationCategory.entries) {
                collectIfNotReal(misses, "$language", category.name, category.getName(language))
            }
        }
        assertTrue(misses.isEmpty(), misses.toString())
        assertEquals("Lücke im Signaturzähler", TseExplanationCategory.SIGNATURE_COUNTER_GAP.getName(Language.GERMAN))
    }

    @Test
    fun rawStringMapsTranslateKnownValuesAndKeepUnknownOnes() {
        val known =
            listOf<Pair<String, (String, Language) -> String>>(
                "STANDARD_19" to ::vatClassLabel,
                "REDUCED_7" to ::vatClassLabel,
                "ZERO" to ::vatClassLabel,
                "WORM_INIT_INITIALIZED" to ::tseInitializationStateLabel,
                "WORM_INIT_UNINITIALIZED" to ::tseInitializationStateLabel,
                "WORM_INIT_DECOMMISSIONED" to ::tseInitializationStateLabel,
                "unixTime" to ::tseLogTimeFormatLabel,
                "utcTime" to ::tseLogTimeFormatLabel,
                "utcTimeWithSeconds" to ::tseLogTimeFormatLabel,
                "generalizedTime" to ::tseLogTimeFormatLabel,
                "WORM_ENTRY_TYPE_TRANSACTION" to ::tseLogEntryTypeLabel,
                "WORM_ENTRY_TYPE_SYSTEM_LOG_MESSAGE" to ::tseLogEntryTypeLabel,
                "WORM_ENTRY_TYPE_SE_AUDIT_LOG_MESSAGE" to ::tseLogEntryTypeLabel,
            )
        val misses = mutableListOf<String>()
        for (language in Language.entries) {
            for ((raw, label) in known) collectIfNotReal(misses, "$language", raw, label(raw, language))
            for (label in listOf(::vatClassLabel, ::tseInitializationStateLabel, ::tseLogTimeFormatLabel, ::tseLogEntryTypeLabel)) {
                assertEquals("SOMETHING_NEW", label("SOMETHING_NEW", language))
            }
        }
        assertTrue(misses.isEmpty(), misses.toString())
        assertEquals("Nicht initialisiert", tseInitializationStateLabel("WORM_INIT_UNINITIALIZED", Language.GERMAN))
        assertEquals("Unix-Zeit", tseLogTimeFormatLabel("unixTime", Language.GERMAN))
        assertEquals("Regelsatz", vatClassLabel("STANDARD_19", Language.GERMAN))
    }

    @Test
    fun eInvoiceFormatsAreProperNames() {
        assertEquals("ZUGFeRD", eInvoiceFormatLabel("ZUGFERD"))
        assertEquals("Factur-X", eInvoiceFormatLabel("FACTUR_X"))
        assertEquals("XRechnung", eInvoiceFormatLabel("xrechnung"))
        assertEquals("UBL", eInvoiceFormatLabel("UBL"))
        assertEquals("CII", eInvoiceFormatLabel("cii"))
        assertEquals("OTHER", eInvoiceFormatLabel("OTHER"))
    }
}
