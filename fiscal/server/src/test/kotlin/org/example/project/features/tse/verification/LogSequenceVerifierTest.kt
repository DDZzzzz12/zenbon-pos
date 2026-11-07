package org.example.project.features.tse.verification

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSequenceVerifierTest {
    // A transaction log record: counter, txNumber, time. operationType "StartTransaction".
    private fun tx(
        counter: Long,
        txNumber: Long,
        time: Long,
    ) = TseDto.ParsedLogMessageDto(
        version = 3,
        operationType = "StartTransaction",
        clientId = "C1",
        transactionNumber = txNumber,
        signatureCounter = counter,
        logTime = time,
    )

    // A set-time system log at a given counter/time (no transaction number).
    private fun setTime(
        counter: Long,
        time: Long,
    ) = TseDto.ParsedLogMessageDto(
        version = 3,
        operationType = "UpdateTime",
        signatureCounter = counter,
        logTime = time,
    )

    // A record that parsed and carries a signature counter, but no logTime of its own (e.g. a
    // time field that failed to decode). Not a set-time event.
    private fun noTime(counter: Long) = TseDto.ParsedLogMessageDto(version = 3, signatureCounter = counter)

    // A set-time system log whose own logTime did not decode. Still a set-time event.
    private fun setTimeNoTime(counter: Long) =
        TseDto.ParsedLogMessageDto(
            version = 3,
            operationType = "UpdateTime",
            signatureCounter = counter,
        )

    // A record that failed to parse a signature counter at all.
    private fun noCounter() = TseDto.ParsedLogMessageDto(version = 3, operationType = "StartTransaction")

    @Test
    fun `a clean sequence passes`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), tx(2, 2, 200), tx(3, 3, 300)))
        assertEquals(Verdict.PASS, r.verdict)
        assertTrue(r.findings.isEmpty())
        assertEquals(3, r.transactionCount)
    }

    @Test
    fun `a duplicate signature counter fails`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), tx(1, 2, 200)))
        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.DUPLICATE_SIGNATURE_COUNTER })
    }

    @Test
    fun `a transaction-number hole fails`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), tx(2, 3, 200))) // missing tx 2
        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.TRANSACTION_NUMBER_GAP })
    }

    @Test
    fun `a single lost signature counter is tolerated`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), tx(3, 2, 200))) // counter 2 lost (gap 1)
        assertEquals(Verdict.PASS, r.verdict)
    }

    @Test
    fun `a large signature-counter gap warns`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), tx(5, 2, 200))) // 3 missing
        assertEquals(Verdict.PASS_WITH_WARNINGS, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.SIGNATURE_COUNTER_GAP })
    }

    @Test
    fun `a backward timestamp without a set-time event warns`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 300), tx(2, 2, 100)))
        assertEquals(Verdict.PASS_WITH_WARNINGS, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.TIMESTAMP_REGRESSION })
    }

    @Test
    fun `a backward timestamp right after a set-time event is allowed`() {
        // counter order: tx@300, set-time@..., tx@100. The jump follows the set-time event.
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 300), setTime(2, 300), tx(3, 2, 100)))
        assertEquals(Verdict.PASS, r.verdict)
    }

    // A non-transaction system log (AuthenticateUser, LogOut, ...).
    private fun sysLog(
        counter: Long,
        operationType: String,
        time: Long,
    ) = TseDto.ParsedLogMessageDto(
        version = 3,
        operationType = operationType,
        signatureCounter = counter,
        logTime = time,
    )

    // Counters 1653..1658 of a real Swissbit full export (rt2, 2026-05-04): the device clock ran
    // ~16 s fast, and the UpdateTime log message itself carries the corrected, EARLIER time.
    private val realSwissbitTimeSync =
        listOf(
            tx(1653, 2, 1777899664),
            sysLog(1654, "AuthenticateUser", 1777899705),
            setTime(1655, 1777899689),
            sysLog(1656, "LogOut", 1777899689),
            tx(1657, 3, 1777899714),
            tx(1658, 3, 1777899714),
        )

    @Test
    fun `a set-time record that itself steps the clock back is allowed`() {
        val r = LogSequenceVerifier.verify(realSwissbitTimeSync)
        assertTrue(r.findings.none { it.code == FindingCode.TIMESTAMP_REGRESSION }, "findings: ${r.findings}")
        assertEquals(Verdict.PASS, r.verdict)
    }

    @Test
    fun `a backward transaction log next to a time sync still warns`() {
        // Same real sequence, but the transaction after the sync goes back past the sync time.
        val tampered = realSwissbitTimeSync.dropLast(1) + tx(1658, 3, 1777899600)
        val r = LogSequenceVerifier.verify(tampered)
        val regressions = r.findings.filter { it.code == FindingCode.TIMESTAMP_REGRESSION }
        assertEquals(1, regressions.size, "findings: ${r.findings}")
        assertTrue(regressions.single().detail!!.startsWith("counter=1658;"))
        assertEquals(Verdict.PASS_WITH_WARNINGS, r.verdict)
    }

    @Test
    fun `an archive with no transaction logs passes with an info finding`() {
        val r = LogSequenceVerifier.verify(listOf(setTime(1, 100)))
        assertEquals(Verdict.PASS, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.NO_TRANSACTION_LOGS })
    }

    @Test
    fun `a null-logTime record between two timestamps does not hide a backward jump`() {
        // counter order: tx@300, a record with no logTime of its own, tx@50. The gap in the
        // middle must not sever the chain and hide the genuine regression.
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 300), noTime(2), tx(3, 2, 50)))
        assertEquals(Verdict.PASS_WITH_WARNINGS, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.TIMESTAMP_REGRESSION })
    }

    @Test
    fun `a timeless set-time event between two timestamps still exempts a backward jump`() {
        // The set-time record itself carries no logTime, but must still exempt the next real jump.
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 300), setTimeNoTime(2), tx(3, 2, 50)))
        assertEquals(Verdict.PASS, r.verdict)
        assertTrue(r.findings.none { it.code == FindingCode.TIMESTAMP_REGRESSION })
    }

    @Test
    fun `a record with no signature counter is flagged but does not fail verification`() {
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), noCounter(), tx(2, 2, 200)))
        assertEquals(Verdict.PASS_WITH_WARNINGS, r.verdict)
        assertTrue(r.findings.any { it.code == FindingCode.UNPARSEABLE_RECORD })
    }

    @Test
    fun `descending signature-counter order produces the same report as ascending`() {
        val ascending = listOf(tx(1, 1, 100), tx(2, 2, 200), tx(5, 3, 500)) // gap of 2 between counters 2 and 5
        val descending = ascending.reversed()
        assertEquals(LogSequenceVerifier.verify(ascending), LogSequenceVerifier.verify(descending))
    }

    @Test
    fun `a transaction that logs three messages under one transaction number passes`() {
        // Start/Update/Finish share the same transaction number but each gets its own counter.
        val r = LogSequenceVerifier.verify(listOf(tx(1, 1, 100), tx(2, 1, 200), tx(3, 1, 300)))
        assertEquals(Verdict.PASS, r.verdict)
        assertEquals(1, r.transactionCount)
        assertTrue(r.findings.none { it.code == FindingCode.DUPLICATE_SIGNATURE_COUNTER })
        assertTrue(r.findings.none { it.code == FindingCode.TRANSACTION_NUMBER_GAP })
    }
}
