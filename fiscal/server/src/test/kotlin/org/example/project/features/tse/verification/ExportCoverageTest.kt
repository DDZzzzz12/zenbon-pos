package org.example.project.features.tse.verification

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.tse.TseDto.ParsedLogMessageDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity
import org.example.project.features.tse.findings.TanrRow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@DisplayName("ExportCoverage: POS-signed transactions the export's range should hold")
class ExportCoverageTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private val serial = "AB".repeat(32)
    private val dayStart = 1_778_018_400L // 2026-05-06T00:00+02:00

    private fun systemLog(at: Long) = ParsedLogMessageDto(operationType = "SelfTest", logTime = at, serialNumberHex = serial, signatureCounter = at)

    private fun txLog(
        tanr: Long,
        at: Long,
    ) = ParsedLogMessageDto(operationType = "FinishTransaction", transactionNumber = tanr, logTime = at, serialNumberHex = serial, signatureCounter = at)

    private fun pos(
        tanr: Long,
        at: Long,
        serialHex: String = serial,
    ) = TanrRow(serialHex, tanr, Instant.fromEpochSeconds(at).toLocalDateTime(zone))

    @Test
    fun `an export holding no transaction while the POS signed some in its log-time span is CRITICAL`() {
        // QA rt12: the day's time-filtered export held 146 system + 8 audit logs and 0 transaction
        // logs, while the POS had signed TANR 64–89 that day. It verified PASS_WITH_WARNINGS.
        val records = listOf(systemLog(dayStart + 3_600), systemLog(dayStart + 80_000))
        val posRows = (64L..89L).map { pos(it, dayStart + 30_000 + it * 60) }

        val finding = ExportCoverage.missingPosTransactions(records, posRows, zone).single()

        assertEquals(Severity.CRITICAL, finding.severity)
        assertEquals(FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT, finding.code)
        assertTrue(finding.detail!!.contains("missingCount=26"), finding.detail!!)
    }

    @Test
    fun `a hole inside the export's TANR span is reported, the span's edges are not invented`() {
        val records = listOf(txLog(27, dayStart + 100), txLog(30, dayStart + 100))
        val posRows = listOf(pos(26, dayStart - 50_000), pos(27, 0), pos(28, 0), pos(29, 0), pos(30, 0), pos(31, dayStart + 90_000))

        val finding = ExportCoverage.missingPosTransactions(records, posRows, zone).single()

        assertTrue(finding.detail!!.contains("missing=28,29"), finding.detail!!)
    }

    @Test
    fun `a transaction within the clock tolerance of the export's edge is not judged by time`() {
        val records = listOf(systemLog(dayStart), systemLog(dayStart + 3_600))
        val atEdge = pos(5, dayStart + ExportCoverage.CLOCK_TOLERANCE_SECONDS - 1)

        assertEquals(emptyList(), ExportCoverage.missingPosTransactions(records, listOf(atEdge), zone))
    }

    @Test
    fun `another device's transactions and a fully covered export produce nothing`() {
        val records = listOf(txLog(1, dayStart + 100), txLog(2, dayStart + 7_200))
        val posRows = listOf(pos(1, dayStart + 100), pos(2, dayStart + 7_200), pos(7, dayStart + 3_600, serialHex = "CD".repeat(32)))

        assertEquals(emptyList(), ExportCoverage.missingPosTransactions(records, posRows, zone))
    }
}
