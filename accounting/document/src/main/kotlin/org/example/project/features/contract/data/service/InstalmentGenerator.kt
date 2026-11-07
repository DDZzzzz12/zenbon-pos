package org.example.project.features.contract.data.service

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.contract.domain.models.ScheduleStep
import org.example.project.features.contract.domain.repository.ContractRepository

/**
 * Materialises the rolling window of instalments — the current period plus the next — for every
 * active contract.
 *
 * Generating ahead is the whole point: an instalment that only appears once its due date arrives
 * cannot be *missing*, and a payment that never came is exactly what this feature exists to notice.
 *
 * Idempotent per `(contract_id, due_date)`, so a second run after a restart is a no-op. Requires an
 * ambient transaction.
 */
class InstalmentGenerator(
    private val repository: ContractRepository,
) {
    /**
     * [today] is a parameter rather than a `now()` call, for the same reason [InstalmentSchedule]
     * takes its window as an argument: otherwise nothing below the scheduled job is testable.
     *
     * Returns the number of instalments actually created, which is what the job reports.
     */
    fun generate(today: LocalDate): Int = repository.activeContracts().sumOf { generateFor(it, today) }

    private fun generateFor(
        contract: Contract,
        today: LocalDate,
    ): Int {
        val interval = ContractInterval.valueOf(contract.interval)
        val windowStart = LocalDate(today.year, today.month, 1)
        // The current period plus the next. A month-stepped contract looks one interval ahead; a
        // weekly one only needs the month, since its next period is seven days away; a one-time
        // contract has no next period at all.
        val windowEnd =
            when (val step = interval.step) {
                is ScheduleStep.Once -> windowStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
                is ScheduleStep.Days -> windowStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
                is ScheduleStep.Months ->
                    windowStart.plus(step.count + 1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            }

        val dueDates =
            InstalmentSchedule.dueDatesIn(
                startDate = contract.startDate.date,
                endDate = contract.endDate?.date,
                interval = interval,
                dueDay = contract.dueDay,
                dueWeekday = contract.dueWeekday,
                windowStart = windowStart,
                windowEnd = windowEnd,
            )
        return dueDates.count { dueDate ->
            repository.createInstalmentIfAbsent(
                contractId = contract.id.value,
                dueDate = dueDate.atTime(0, 0),
                expectedAmount = contract.expectedAmount,
            ) != null
        }
    }
}
