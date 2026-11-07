package org.example.project.tse.models

import org.example.project.core.domain.dto.tse.TseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TseStatusAuthFailureTest {
    @Test
    fun `record then get then clear last auth failure`() {
        val status = TseStatus()
        assertNull(status.getLastAuthFailure())
        status.recordAuthFailure(TseDto.TseAuthFailureInfo(remainingRetries = 2, pinBlocked = false))
        assertEquals(2, status.getLastAuthFailure()?.remainingRetries)
        assertEquals(false, status.getLastAuthFailure()?.pinBlocked)
        status.clearAuthFailure()
        assertNull(status.getLastAuthFailure())
    }
}
