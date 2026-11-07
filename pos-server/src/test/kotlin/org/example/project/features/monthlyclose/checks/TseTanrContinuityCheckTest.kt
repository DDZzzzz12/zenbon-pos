package org.example.project.features.monthlyclose.checks

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.example.project.features.monthlyclose.data.service.checks.TseTanrContinuityCheck
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.findings.TseExplanationRepository
import org.example.project.features.tse.findings.TseFindingService
import org.example.project.features.tse.findings.TseTanrRepository
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The month-end check over the TANR register: a gap the POS cannot account for and nobody has
 * explained warns; explaining it clears the warning.
 */
class TseTanrContinuityCheckTest {
    private val start = LocalDateTime.parse("2026-09-01T00:00:00")
    private val end = LocalDateTime.parse("2026-10-01T00:00:00")
    private val serial = "2D2F88FBC30BBC9ACEB75C28E212105996786C7A0A08B2C6CF74306EDC75EC48"

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun seed(vararg tanrs: Long) =
        transaction {
            for (n in tanrs) {
                TseTransaction.new {
                    tseSerialNumber = serial
                    tseSerialHex = serial
                    transactionNumber = n
                    tseMode = TseMode.NORMAL
                    startTime = LocalDateTime.parse("2026-09-21T11:00:00")
                }
            }
        }

    private fun check() = TseTanrContinuityCheck(TseFindingService(TseTanrRepository(), TseExplanationRepository()))

    @Test
    fun `an unexplained gap warns`() {
        seed(1, 2, 6)
        val item = transaction { check().run(start, end) }
        assertEquals("tse_tanr_continuity", item.id)
        assertEquals(MonthlyCloseDto.ChecklistStatus.WARNING, item.status)
        assertEquals(1, item.affectedCount)
    }

    @Test
    fun `an explained gap passes`() {
        seed(1, 2, 6)
        transaction {
            TseExplanationRepository().insert(serial, 3, 5, TseExplanationCategory.TSE_OUTAGE, "Stromausfall", TseExplanationSource.OPERATOR, "7", null)
        }
        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, transaction { check().run(start, end) }.status)
    }

    @Test
    fun `a month without TSE transactions passes`() {
        assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, transaction { check().run(start, end) }.status)
    }
}
