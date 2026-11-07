package org.example.project.admin.core.presentation.belegfluss

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TranslationRegistry.get` falls back to `key.id`, so an untranslated Belegfluss key would render as
 * `"admin.belegfluss.stageCapture"` on screen with nothing failing at compile time. This is the guard,
 * plus one for the `{n}` placeholders and `**bold**` markers the three languages must agree on.
 */
class BelegflowStringsTest {
    private val keys: List<StringKey> =
        listOf(
            AdminStringKeys.BelegflowStripLabel,
            AdminStringKeys.BelegflowStageCapture,
            AdminStringKeys.BelegflowStageReview,
            AdminStringKeys.BelegflowStageAssign,
            AdminStringKeys.BelegflowStageDone,
            AdminStringKeys.BelegflowStageCaptureSub,
            AdminStringKeys.BelegflowStageReviewSub,
            AdminStringKeys.BelegflowStageAssignSub,
            AdminStringKeys.BelegflowStageDoneSub,
            AdminStringKeys.BelegflowSalesDraft,
            AdminStringKeys.BelegflowSalesDraftSub,
            AdminStringKeys.BelegflowSalesIssuedSub,
            AdminStringKeys.BelegflowSalesOpenSub,
            AdminStringKeys.BelegflowStatusCreditNote,
            AdminStringKeys.BelegflowProgressStep,
            AdminStringKeys.BelegflowColumnProgress,
            AdminStringKeys.BelegflowNotCaptured,
            AdminStringKeys.BelegflowNumberDue,
            AdminStringKeys.BelegflowChipAllOpen,
            AdminStringKeys.BelegflowChipAllIssued,
            AdminStringKeys.BelegflowNextOne,
            AdminStringKeys.BelegflowNextThen,
            AdminStringKeys.BelegflowPhraseCapture,
            AdminStringKeys.BelegflowPhraseCaptureOne,
            AdminStringKeys.BelegflowPhraseReview,
            AdminStringKeys.BelegflowPhraseReviewOne,
            AdminStringKeys.BelegflowPhraseAssign,
            AdminStringKeys.BelegflowPhraseAssignOne,
            AdminStringKeys.BelegflowPhraseIssue,
            AdminStringKeys.BelegflowPhraseIssueOne,
            AdminStringKeys.BelegflowActionCapture,
            AdminStringKeys.BelegflowActionReview,
            AdminStringKeys.BelegflowActionAssign,
            AdminStringKeys.BelegflowActionOpenDrafts,
            AdminStringKeys.BelegflowActionAssignPayment,
            AdminStringKeys.BelegflowSalesPaymentOpen,
            AdminStringKeys.BelegflowSalesPaymentOpenDue,
            AdminStringKeys.BelegflowHistoryTitle,
            AdminStringKeys.BelegflowSalesStepArchived,
            AdminStringKeys.BelegflowSalesStepPaid,
            AdminStringKeys.BelegflowActionSaveAndAssign,
            AdminStringKeys.BelegflowAmountMax,
            AdminStringKeys.BelegflowSumExceeds,
            AdminStringKeys.BelegflowCounter,
            AdminStringKeys.BelegflowReverseTitle,
            AdminStringKeys.BelegflowReverseBody,
            AdminStringKeys.BelegflowReverseConfirm,
            AdminStringKeys.BelegflowImportContinue,
            AdminStringKeys.BelegflowImportOtherFile,
            AdminStringKeys.BelegflowEditorSummaryTitle,
            AdminStringKeys.BelegflowEditorVatRow,
            AdminStringKeys.BelegflowEditorImmutableHint,
            AdminStringKeys.BelegflowEditorSaveAndIssue,
            AdminStringKeys.BelegflowEditorSaveDraft,
            AdminStringKeys.BelegflowEditorDraftSubtitle,
            AdminStringKeys.BelegflowTargetCaption,
            AdminStringKeys.BelegflowAssignedNone,
            AdminStringKeys.BelegflowDueOn,
            AdminStringKeys.BelegflowAssignSummary,
            AdminStringKeys.BelegflowCaptureSubtitle,
            AdminStringKeys.BelegflowChanged,
            AdminStringKeys.BelegflowVatSumOk,
            AdminStringKeys.BelegflowEntryVerifyTitle,
            AdminStringKeys.BelegflowEntryVerifyText,
            AdminStringKeys.BelegflowEntryVerifyAction,
            AdminStringKeys.BelegflowEntryCaptureTitle,
            AdminStringKeys.BelegflowEntryCaptureText,
            AdminStringKeys.BelegflowHistoryDocumentTitle,
            AdminStringKeys.BelegflowHistoryUploaded,
            AdminStringKeys.BelegflowHistoryCaptured,
            AdminStringKeys.BelegflowHistoryReviewed,
            AdminStringKeys.BelegflowHistoryLinked,
            AdminStringKeys.BelegflowHistoryUploadedDetail,
            AdminStringKeys.BelegflowHistoryReadAuto,
            AdminStringKeys.BelegflowHistoryLinkedDetail,
            AdminStringKeys.BelegflowHistoryToInvoice,
            AdminStringKeys.BelegflowInvoicesSubtitleLong,
            AdminStringKeys.BelegflowInvoicesSubtitleShort,
            AdminStringKeys.BelegflowActionUploadDocument,
            AdminStringKeys.BelegflowActionCaptureInvoice,
        )

    private val languages =
        mapOf(
            "de" to GermanAdminStrings.strings,
            "en" to EnglishAdminStrings.strings,
            "zh" to ChineseAdminStrings.strings,
        )

    @Test
    fun everyBelegflowKeyIsTranslatedInAllThreeLanguages() {
        val missing =
            keys.flatMap { key ->
                languages.filter { (_, strings) -> strings[key].isNullOrBlank() }.map { (language, _) -> "$language: ${key.id}" }
            }
        assertTrue(missing.isEmpty(), "Untranslated Belegfluss keys: $missing")
    }

    @Test
    fun placeholdersAndBoldMarkersAgreeAcrossLanguages() {
        val signature = Regex("""\{\d+\}|\*\*""")
        keys.forEach { key ->
            val perLanguage =
                languages.mapValues { (_, strings) ->
                    signature
                        .findAll(strings.getValue(key))
                        .map { it.value }
                        .sorted()
                        .toList()
                }
            assertEquals(1, perLanguage.values.toSet().size, "${key.id} differs between languages: $perLanguage")
        }
    }
}
