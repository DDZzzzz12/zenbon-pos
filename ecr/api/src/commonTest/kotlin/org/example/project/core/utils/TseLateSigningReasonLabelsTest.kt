package org.example.project.core.utils

import org.example.project.core.domain.enums.tse.TseLateSigningReason
import kotlin.test.Test
import kotlin.test.assertEquals

class TseLateSigningReasonLabelsTest {
    @Test
    fun `a TSE that is connected but not operational is not called unreachable`() {
        assertEquals("TSE nicht betriebsbereit", lateSigningReasonLabelDe(TseLateSigningReason.TSE_NOT_OPERATIONAL.name))
    }

    @Test
    fun `an unreachable TSE keeps its label`() {
        assertEquals("TSE nicht erreichbar", lateSigningReasonLabelDe(TseLateSigningReason.TSE_UNAVAILABLE.name))
    }
}
