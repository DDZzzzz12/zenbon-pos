package org.example.project.core.presentation.error

import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * QA 2026-10-06: saving the TSE configuration (e.g. in SIMULATION) without a TimeAdmin PIN answered a
 * generic VALIDATION_FAILED, shown as "Unerwarteter Fehler" with English details. The specific code
 * must read as the localized "enter the TimeAdmin PIN" sentence on the till and in adminApp.
 */
class TimeAdminPinErrorMappingTest {
    @Test
    fun aMissingTimeAdminPinReadsAsTheLocalizedHint() {
        val error =
            PosApiException(
                code = PosErrorCode.TSE_TIME_ADMIN_PIN_REQUIRED,
                httpStatus = 400,
                message = "A TimeAdmin PIN is required.",
                traceId = "t-1",
            ).toUiError()

        assertEquals(TseSettingsKeys.configurationSection, error.titleKey)
        assertEquals(TseSettingsKeys.timeAdminPinRequiredForSave, error.messageKey)
        assertNull(error.serverDetailFallback())
    }
}
