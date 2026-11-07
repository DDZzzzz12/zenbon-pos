package org.example.project.features.settings.presentation.components.printer

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UI review follow-up 5: the print-job filter chips showed the raw enum names (`FAILED`, `BILL`). */
class PrintJobLabelsTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun everyStatusHasALocalizedLabelInEveryLanguage() {
        PrinterDto.PrintJobStatus.entries.forEach { status ->
            Language.entries.forEach { language ->
                val label = getString(printJobStatusKey(status), language)
                assertTrue(label.isNotBlank() && label != status.name && label != printJobStatusKey(status).id, "$status in $language: '$label'")
            }
        }
        assertEquals("Fehlgeschlagen", getString(printJobStatusKey(PrinterDto.PrintJobStatus.FAILED), Language.GERMAN))
    }

    @Test
    fun everyPrintTypeHasALocalizedLabelInEveryLanguage() {
        PrinterDto.PrintType.entries.forEach { type ->
            Language.entries.forEach { language ->
                val label = getString(printTypeKey(type), language)
                assertTrue(label.isNotBlank() && label != type.name && label != printTypeKey(type).id, "$type in $language: '$label'")
            }
        }
        assertEquals("Rechnung", getString(printTypeKey(PrinterDto.PrintType.BILL), Language.GERMAN))
    }
}
