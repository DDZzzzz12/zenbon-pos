package org.example.project.features.tse.verification

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosReconciliationTest {
    private val serial = "2d2f88fbc30bbc9aceb75c28e212105996786c7a0a08b2c6cf74306edc75ec48"

    private fun log(
        tanr: Long,
        op: String,
        type: String? = null,
    ) = TseDto.ParsedLogMessageDto(
        operationType = op,
        transactionNumber = tanr,
        processType = type,
        serialNumberHex = serial,
        signatureCounter = tanr * 2,
    )

    @Test
    fun `a TANR the POS never recorded is reported with what the TSE did with it`() {
        val records = listOf(log(1, "StartTransaction"), log(1, "FinishTransaction", "Kassenbeleg-V1"), log(2, "StartTransaction"))
        val findings = PosReconciliation.reconcile(records, mapOf(serial.uppercase() to setOf(1L)))

        val f = findings.single()
        assertEquals(FindingCode.TANR_WITHOUT_POS_RECORD, f.code)
        assertEquals(Severity.WARNING, f.severity)
        assertTrue(f.detail!!.contains("tanr=2"))
        assertTrue(f.detail!!.contains("operations=StartTransaction"))
    }

    @Test
    fun `an export of a device the POS never used is not reconciled`() {
        assertEquals(emptyList(), PosReconciliation.reconcile(listOf(log(1, "StartTransaction")), emptyMap()))
    }
}
