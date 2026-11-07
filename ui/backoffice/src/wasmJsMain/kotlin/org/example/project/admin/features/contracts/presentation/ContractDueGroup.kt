package org.example.project.admin.features.contracts.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey

/**
 * How urgently a contract's next payment falls due.
 *
 * Declaration order is urgency order, which is the order the groups are rendered in — the enum is
 * the ranking, so there is no second list to keep in step with it.
 */
enum class ContractDueGroup(
    val labelKey: StringKey,
) {
    OVERDUE(AdminStringKeys.ContractsGroupOverdue),
    THIS_WEEK(AdminStringKeys.ContractsGroupThisWeek),
    THIS_MONTH(AdminStringKeys.ContractsGroupThisMonth),
    LATER(AdminStringKeys.ContractsGroupLater),

    /**
     * Deactivated contracts, last and separate. An inactive contract is not a thing that falls due,
     * so filing a cancelled one under „Überfällig" would invent an obligation that no longer exists.
     */
    INACTIVE(AdminStringKeys.ContractsGroupInactive),
}
