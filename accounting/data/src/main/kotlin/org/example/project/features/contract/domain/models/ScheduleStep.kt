package org.example.project.features.contract.domain.models

/**
 * How a contract's schedule advances from one due date to the next.
 *
 * A sealed type rather than `months: Int`, because two of the six intervals cannot be expressed as
 * a whole number of months: [Once] does not recur at all, and a week is not a month. Making the
 * step a type turns `InstalmentSchedule` into a total `when` — a new interval shape fails to
 * compile there instead of silently multiplying by the wrong thing.
 */
sealed interface ScheduleStep {
    /** Exactly one due date. The contract is a single payment, not a series. */
    data object Once : ScheduleStep

    /** Advance by [count] days, anchored on a weekday rather than a day of month. */
    data class Days(
        val count: Int,
    ) : ScheduleStep

    /** Advance by [count] months, anchored on the contract's day of month. */
    data class Months(
        val count: Int,
    ) : ScheduleStep
}
