package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NEW-13: Analysen > Katalog printed the system category's stored identifier "VOUCHER". */
class CategoryDisplayNameTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun systemCategories_areLocalizedInEveryLanguage() {
        listOf("VOUCHER", "PFAND").forEach { name ->
            Language.entries.forEach { language ->
                val label = categoryDisplayName(name, language)
                assertTrue(label.isNotBlank() && label != name, "$name in $language: '$label'")
            }
        }
        assertEquals("Gutscheine", categoryDisplayName("VOUCHER", Language.GERMAN))
    }

    @Test
    fun operatorCategories_keepTheirOwnName() {
        assertEquals("Getränke", categoryDisplayName("Getränke", Language.ENGLISH))
        assertEquals("voucher", categoryDisplayName("voucher", Language.GERMAN), "only the exact stored identifier is a system category")
    }
}
