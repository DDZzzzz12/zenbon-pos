package org.example.project.admin.features.documents

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated key renders as
 * `"admin.documents.title"` on screen with nothing failing at compile time. This is the guard.
 */
class DocumentsStringsTest {
    private val documentKeys: List<StringKey> =
        listOf(
            AdminStringKeys.NavDocuments,
            AdminStringKeys.DocumentUploadUnlockTitle,
            AdminStringKeys.DocumentUploadUnlockExplainer,
            AdminStringKeys.DocumentUploadUnlockPasswordLabel,
            AdminStringKeys.DocumentUploadUnlockPasswordHint,
            AdminStringKeys.DocumentUploadUnlockAction,
            AdminStringKeys.DocumentUploadUnlockDecline,
            AdminStringKeys.DocumentUploadUnlockDeclineTitle,
            AdminStringKeys.DocumentUploadUnlockDeclineWarning,
            AdminStringKeys.DocumentUploadUnlockDeclineConfirm,
            AdminStringKeys.DocumentUploadUnlockBack,
            AdminStringKeys.DocumentsTitle,
            AdminStringKeys.DocumentsCount,
            AdminStringKeys.DocumentsFilterType,
            AdminStringKeys.DocumentsIncludeInactive,
            AdminStringKeys.DocumentsFieldProcessingStatus,
            AdminStringKeys.DocumentsEmpty,
            AdminStringKeys.DocumentsEmptyHint,
            AdminStringKeys.CommonResetFilters,
            AdminStringKeys.DocumentImportTitle,
            AdminStringKeys.DocumentImportExplainer,
            AdminStringKeys.DocumentImportAction,
            AdminStringKeys.DocumentImportExtractedTitle,
            AdminStringKeys.DocumentImportExtracted,
            AdminStringKeys.DocumentImportNoEInvoiceTitle,
            AdminStringKeys.DocumentImportNoEInvoice,
            AdminStringKeys.DocumentImportNotConformantTitle,
            AdminStringKeys.DocumentImportNotConformant,
            AdminStringKeys.DocumentImportUnreadableTitle,
            AdminStringKeys.DocumentImportUnreadable,
            AdminStringKeys.DocumentImportDuplicateTitle,
            AdminStringKeys.DocumentImportDuplicate,
            AdminStringKeys.DocumentImportOpen,
        )

    @Test
    fun `every document key is translated in all three languages`() {
        val missing =
            documentKeys.flatMap { key ->
                listOf(
                    "de" to GermanAdminStrings.strings[key],
                    "en" to EnglishAdminStrings.strings[key],
                    "zh" to ChineseAdminStrings.strings[key],
                ).filter { it.second.isNullOrBlank() }.map { "${it.first}: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated document keys: $missing")
    }
}
