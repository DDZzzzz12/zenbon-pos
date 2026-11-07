package org.example.project.features.contract.domain.models

/**
 * How often a contract falls due. [step] is what the schedule advances by.
 *
 * Stored as the constant's **name** in `contract.interval`, so a rename without a migration orphans
 * existing rows silently — `ContractEnumTest` is the guard. The four month-stepped constants keep
 * the names they have always had; [ONE_TIME] and [WEEKLY] are additions, so no stored row changes
 * meaning.
 *
 * Quarterly counts from the contract's own start month, not from January: a contract starting in
 * February falls due in February, May, August and November.
 *
 * [WEEKLY] is the one interval that does not use `contract.due_day` — a week has no day of month —
 * and reads `contract.due_weekday` instead.
 */
enum class ContractInterval(
    val step: ScheduleStep,
) {
    ONE_TIME(ScheduleStep.Once),
    WEEKLY(ScheduleStep.Days(7)),
    MONTHLY(ScheduleStep.Months(1)),
    QUARTERLY(ScheduleStep.Months(3)),
    SEMIANNUAL(ScheduleStep.Months(6)),
    ANNUAL(ScheduleStep.Months(12)),
}
