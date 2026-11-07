package org.example.project.core.presentation.error

import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.RulesKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An order station never owns a table session (spec 2026-09-14). The till denies these opens itself,
 * but its round-robin flag can be stale; the server's 409 must then read exactly like the till's own
 * denial, never like the generic unknown-error text or the untranslated server detail.
 */
class StationTableErrorMappingTest {
    private fun conflict(code: PosErrorCode): UiError.FromServer =
        PosApiException(code = code, httpStatus = 409, message = "raw server text", traceId = "t-1").toUiError()

    @Test
    fun requiresRotationMapsToTheRotationSentence() {
        val error = conflict(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION)

        assertEquals(RulesKeys.stationTableDeniedTitle, error.titleKey)
        assertEquals(RulesKeys.stationTableRequiresRotation, error.messageKey)
        assertNull(error.serverDetailFallback())
    }

    @Test
    fun virtualTableMapsToTheVirtualSentence() {
        val error = conflict(PosErrorCode.STATION_TABLE_VIRTUAL)

        assertEquals(RulesKeys.stationTableDeniedTitle, error.titleKey)
        assertEquals(RulesKeys.stationCannotOpenVirtualTable, error.messageKey)
        assertNull(error.serverDetailFallback())
    }

    @Test
    fun emptyRotationPoolMapsToTheStationDeniedTitle() {
        // A station hits this on every free-table open once the pool is empty; the generic
        // "Unexpected error" title read as a crash rather than the actionable denial it is.
        val error = conflict(PosErrorCode.ROTATION_POOL_EMPTY)

        assertEquals(RulesKeys.stationTableDeniedTitle, error.titleKey)
        assertEquals(RulesKeys.rotationPoolEmptyToast, error.messageKey)
        assertNull(error.serverDetailFallback())
    }
}
