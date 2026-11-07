package org.example.project.ui.components.pdf

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PdfLoadErrorMessageTest {
    @Test
    fun everyErrorHasItsOwnMessage() {
        val keys = PdfLoadError.entries.map { it.messageKey() }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyErrorMessageIsTranslatedInEveryLanguage() {
        val translations = defaultTranslations()
        Language.entries.forEach { language ->
            PdfLoadError.entries.forEach { error ->
                val text = translations[language]?.get(error.messageKey())
                assertTrue(!text.isNullOrBlank(), "$error has no $language text")
            }
        }
    }

    @Test
    fun forbiddenHasItsOwnTranslatedMessageDistinctFromUnauthorized() {
        val translations = defaultTranslations()
        assertNotEquals(PdfLoadError.Unauthorized.messageKey(), PdfLoadError.Forbidden.messageKey())
        Language.entries.forEach { language ->
            val forbidden = translations[language]?.get(PdfLoadError.Forbidden.messageKey())
            assertTrue(!forbidden.isNullOrBlank(), "Forbidden has no $language text")
            assertNotEquals(translations[language]?.get(PdfLoadError.Unauthorized.messageKey()), forbidden, "$language")
        }
    }
}
