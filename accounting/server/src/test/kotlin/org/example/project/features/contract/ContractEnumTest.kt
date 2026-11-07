package org.example.project.features.contract

import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.contract.domain.models.ScheduleStep
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Both enums are stored as **strings** in varchar columns, so renaming a constant with no migration
 * orphans every row that used the old name and nothing fails loudly. This pins them.
 *
 * [ContractInterval.step] is pinned too: it is what the schedule advances by, and a wrong value
 * there produces a plausible-looking schedule that is quietly wrong all year.
 */
@DisplayName("Contract enums")
class ContractEnumTest {
    @Test
    fun `the interval names and their steps are fixed`() {
        assertEquals(
            listOf("ONE_TIME", "WEEKLY", "MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL"),
            ContractInterval.entries.map { it.name },
        )
        assertEquals(
            listOf(
                ScheduleStep.Once,
                ScheduleStep.Days(7),
                ScheduleStep.Months(1),
                ScheduleStep.Months(3),
                ScheduleStep.Months(6),
                ScheduleStep.Months(12),
            ),
            ContractInterval.entries.map { it.step },
        )
    }

    @Test
    fun `the instalment status names are fixed`() {
        assertEquals(
            listOf("OPEN", "PAID", "PARTIAL", "OVERDUE", "WAIVED"),
            ContractInstalmentStatus.entries.map { it.name },
        )
    }
}
