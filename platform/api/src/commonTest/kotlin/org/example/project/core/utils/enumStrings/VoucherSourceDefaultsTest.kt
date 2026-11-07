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

/**
 * The voucher source dialog pre-fills a new source in the operator's UI language: it used to write
 * hardcoded English ("Legacy Vouchers") into the stored name the till shows on its redeem chips.
 */
class VoucherSourceDefaultsTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun everyTypeHasADefaultInEveryLanguage() {
        VoucherSourceType.entries.forEach { type ->
            Language.entries.forEach { language ->
                val name = type.defaultSourceName(language)
                val description = type.defaultSourceDescription(language)
                assertTrue(name.isNotBlank() && !name.startsWith("enum."), "$type name in $language: $name")
                assertTrue(description.isNotBlank() && !description.startsWith("enum."), "$type description in $language")
            }
        }
    }

    @Test
    fun germanDefaultsAreGerman() {
        assertEquals("Altgutscheine", VoucherSourceType.LEGACY.defaultSourceName(Language.GERMAN))
        assertEquals("Geschenkkarten", VoucherSourceType.GIFT_CARD.defaultSourceName(Language.GERMAN))
        assertNotEquals(
            VoucherSourceType.PROMOTIONAL.defaultSourceName(Language.ENGLISH),
            VoucherSourceType.PROMOTIONAL.defaultSourceName(Language.GERMAN),
        )
    }
}
