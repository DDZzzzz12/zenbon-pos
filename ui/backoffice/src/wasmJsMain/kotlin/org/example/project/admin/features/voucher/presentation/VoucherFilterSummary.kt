package org.example.project.admin.features.voucher.presentation

/**
 * The badge count of the Gutscheine filter button: how many filters the panel holds that are
 * currently applied. The period is not counted (it is always set and always visible in the bar).
 */
object VoucherFilterSummary {
    /**
     * @param canPickEmployee false when the caller lacks the reporting capability: the picker is not
     *   offered, so a stale employee id must not show as an active filter.
     * @param searchInPanel true where search moves into the panel (a phone, or a bar too narrow for
     *   it beside the full period stepper); otherwise the search field sits in the bar and is not a
     *   panel filter.
     */
    fun activeCount(
        employeeId: String?,
        canPickEmployee: Boolean,
        searchTerm: String,
        searchInPanel: Boolean,
    ): Int {
        val employee = if (canPickEmployee && employeeId != null) 1 else 0
        val search = if (searchInPanel && searchTerm.isNotBlank()) 1 else 0
        return employee + search
    }
}
