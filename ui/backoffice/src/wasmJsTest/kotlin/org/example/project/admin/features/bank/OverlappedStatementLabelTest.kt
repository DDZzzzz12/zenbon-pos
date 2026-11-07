package org.example.project.admin.features.bank

import org.example.project.admin.features.bank.presentation.overlappedStatementLabel
import org.example.project.core.domain.dto.bank.BankAccountDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlappedStatementLabelTest {
    private fun statement(
        id: Long,
        number: String?,
        overlaps: Long? = null,
    ) = BankAccountDto.StatementDto(statementId = id, statementNumber = number, overlappingStatementId = overlaps)

    @Test
    fun `a statement that overlaps nothing has no label`() {
        val s = statement(1, "1/2026")
        assertNull(s.overlappedStatementLabel(listOf(s)))
    }

    @Test
    fun `the other statement's number is the label when it differs`() {
        val earlier = statement(1, "1/2026")
        val later = statement(2, "2/2026", overlaps = 1)
        assertEquals("1/2026", later.overlappedStatementLabel(listOf(earlier, later)))
    }

    @Test
    fun `an identical number is disambiguated with the id`() {
        val earlier = statement(1, "3/2026")
        val reimport = statement(2, "3/2026", overlaps = 1)
        assertEquals("3/2026 (#1)", reimport.overlappedStatementLabel(listOf(earlier, reimport)))
    }

    @Test
    fun `an unnumbered or unknown overlapped statement falls back to its id`() {
        val earlier = statement(1, null)
        val later = statement(2, "2/2026", overlaps = 1)
        assertEquals("#1", later.overlappedStatementLabel(listOf(earlier, later)))
        assertEquals("#9", statement(3, "3/2026", overlaps = 9).overlappedStatementLabel(listOf(later)))
    }
}
