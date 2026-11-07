package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Z-Bon panels showed the raw enum name ("INTERNAL") as the voucher-source badge. */
class VoucherSourceTypeStringsTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun everySourceHasARealLabelInEveryLanguage() {
        for (language in Language.entries) {
            for (source in VoucherSourceType.entries) {
                val label = source.getName(language)
                assertTrue(label.isNotBlank(), "$source has no $language label")
                assertNotEquals(source.name, label, "$source shows its raw enum name in $language")
                assertTrue(!label.startsWith("enum."), "$source is untranslated in $language: $label")
            }
        }
    }

    @Test
    fun germanLabels() {
        assertEquals("Intern", VoucherSourceType.INTERNAL.getName(Language.GERMAN))
        assertEquals("Geschenkkarte", VoucherSourceType.GIFT_CARD.getName(Language.GERMAN))
    }
}
