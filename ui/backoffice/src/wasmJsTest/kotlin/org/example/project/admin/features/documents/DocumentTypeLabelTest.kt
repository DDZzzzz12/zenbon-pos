package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.components.documentTypeLabel
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The type filter chips rendered the raw server identifier ("INVOICE", "BANK_STATEMENT", …)
 * instead of a localized label, while the result table's [DocumentTypePill] resolved the very
 * same value correctly a few rows below. [documentTypeLabel] is the shared resolution both now
 * call.
 */
class DocumentTypeLabelTest {
    @BeforeTest
    fun initTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun knownType_resolvesToItsGermanDisplayName() {
        assertEquals("Rechnung", documentTypeLabel("INVOICE", Language.GERMAN))
    }

    @Test
    fun knownType_resolvesToItsEnglishDisplayName() {
        assertEquals("Invoice", documentTypeLabel("INVOICE", Language.ENGLISH))
    }

    @Test
    fun chineseLanguage_isTranslated() {
        assertEquals("银行对账单", documentTypeLabel("BANK_STATEMENT", Language.CHINESE))
    }

    // Server-configured additional types are not DocumentType entries; the raw value must survive
    // rather than disappearing behind a blank chip.
    @Test
    fun unrecognisedType_fallsBackToTheRawValue() {
        assertEquals("CUSTOM_TYPE", documentTypeLabel("CUSTOM_TYPE", Language.GERMAN))
    }
}
