package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.ProductsTabKeys
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The product form's Typ picker had borrowed the category placeholder ("Kategorie wählen"). */
class ProductTypePlaceholderTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun typePickerHasItsOwnPlaceholder() {
        assertEquals("Typ wählen", getString(ProductsTabKeys.selectType, Language.GERMAN))
        Language.entries.forEach { language ->
            assertNotEquals(
                getString(ProductsTabKeys.selectCategory, language),
                getString(ProductsTabKeys.selectType, language),
                "$language",
            )
        }
    }
}
