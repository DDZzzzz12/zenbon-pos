package org.example.project.core.presentation.error

import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.RulesKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiErrorDetailTest {
    private fun fromServer(
        messageKey: org.example.project.core.presentation.strings.StringKey = CommonKeys.unknownErrorMessage,
        rawDetail: String? = null,
        fieldErrors: List<FieldError> = emptyList(),
        httpStatus: Int = 400,
    ) = UiError.FromServer(
        code = PosErrorCode.VALIDATION_FAILED,
        traceId = "t-1",
        titleKey = CommonKeys.unknownErrorTitle,
        messageKey = messageKey,
        httpStatus = httpStatus,
        fieldErrors = fieldErrors,
        rawDetail = rawDetail,
    )

    @Test
    fun unmappedCodeWithDetail_usesTheServerDetail() {
        // The real case: an actionable 400 that would otherwise read "something went wrong".
        val detail = "A Steuernummer is not a USt-IdNr; leave this empty and set the tax number instead."
        assertEquals(detail, fromServer(rawDetail = detail).serverDetailFallback())
    }

    @Test
    fun mappedCodeWins_evenWhenADetailExists() {
        // A dedicated translation must never be replaced by unlocalized server text.
        val error = fromServer(messageKey = RulesKeys.rotationPoolEmptyToast, rawDetail = "raw server text")
        assertNull(error.serverDetailFallback())
    }

    @Test
    fun noDetail_fallsBackToTheLocalizedMessage() {
        assertNull(fromServer(rawDetail = null).serverDetailFallback())
    }

    @Test
    fun blankOrWhitespaceDetail_isTreatedAsAbsent() {
        assertNull(fromServer(rawDetail = "").serverDetailFallback())
        assertNull(fromServer(rawDetail = "   ").serverDetailFallback())
    }

    @Test
    fun detailIsTrimmed() {
        assertEquals("Boom", fromServer(rawDetail = "  Boom  ").serverDetailFallback())
    }

    @Test
    fun networkAndUnknownErrorsHaveNoServerDetail() {
        // Neither carries a rawDetail, and Network has its own dedicated message key.
        assertNull(UiError.Network().serverDetailFallback())
        assertNull(UiError.Unknown(cause = RuntimeException("boom")).serverDetailFallback())
    }
}
