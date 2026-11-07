package org.example.project.admin.features.documents.presentation.scanner

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

class ScannerStringsTest {
    private val keys: List<StringKey> =
        listOf(
            AdminStringKeys.ScannerTitle,
            AdminStringKeys.ScannerInsecureTitle,
            AdminStringKeys.ScannerInsecureBody,
            AdminStringKeys.ScannerUnsupported,
            AdminStringKeys.ScannerUseCameraApp,
            AdminStringKeys.ScannerCapture,
            AdminStringKeys.ScannerAutoCapture,
            AdminStringKeys.ScannerHoldStill,
            AdminStringKeys.ScannerReady,
            AdminStringKeys.ScannerTorch,
            AdminStringKeys.ScannerRetake,
            AdminStringKeys.ScannerAccept,
            AdminStringKeys.ScannerAdjustHint,
            AdminStringKeys.ScannerResetCorners,
            AdminStringKeys.ScannerWholeImage,
            AdminStringKeys.ScannerFilterColour,
            AdminStringKeys.ScannerFilterGrey,
            AdminStringKeys.ScannerFilterBlackWhite,
            AdminStringKeys.ScannerRotate,
            AdminStringKeys.ScannerPageLabel,
            AdminStringKeys.ScannerPagesCount,
            AdminStringKeys.ScannerDeletePage,
            AdminStringKeys.ScannerMoveUp,
            AdminStringKeys.ScannerMoveDown,
            AdminStringKeys.ScannerAddPage,
            AdminStringKeys.ScannerDone,
            AdminStringKeys.ScannerCancel,
            AdminStringKeys.ScannerLoadingOpenCv,
            AdminStringKeys.ScannerErrorTitle,
            AdminStringKeys.ScannerErrPermission,
            AdminStringKeys.ScannerErrNoCamera,
            AdminStringKeys.ScannerErrBusy,
            AdminStringKeys.ScannerErrOpenCv,
            AdminStringKeys.ScannerErrTooLarge,
            AdminStringKeys.ScannerErrImage,
            AdminStringKeys.ScannerNothingDetected,
            AdminStringKeys.ScannerErrTooManyPages,
            AdminStringKeys.ScannerChooseFile,
            AdminStringKeys.ScannerRetryCamera,
        )

    @Test
    fun `pages count reads correctly for a single page`() {
        listOf(GermanAdminStrings.strings, EnglishAdminStrings.strings, ChineseAdminStrings.strings).forEach { table ->
            val text = table[AdminStringKeys.ScannerPagesCount].orEmpty()
            assertTrue(!text.contains("Seiten") || text.startsWith("Seiten"), text)
            assertTrue(!text.contains(" pages"), text)
        }
    }

    @Test
    fun `every scanner key is translated in all three languages`() {
        val missing =
            keys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated scanner keys: $missing")
    }
}
