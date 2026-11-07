package org.example.project.admin.features.contracts.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.toCanonicalMoneyOrNull

/** `yyyy-MM-dd` — the shape `ContractServiceImpl.parseDate` accepts, and nothing else. */
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * Which fields a contract form fails on, empty when it passes.
 *
 * This is the **client half of rule 8**, mirroring what `ContractServiceImpl` rejects with a 400:
 * a blank name, a `dueDay` outside 1..31, an interval that is not one of the six, a `dueWeekday`
 * outside 1..7 on a WEEKLY contract, an amount that is not a decimal, and a date that is not ISO.
 * The server still enforces every one of them — this only stops the operator before the request
 * rather than after it.
 */
data class ContractFormErrors(
    val name: StringKey? = null,
    val dueDay: StringKey? = null,
    val dueWeekday: StringKey? = null,
    val interval: StringKey? = null,
    val amount: StringKey? = null,
    val tolerancePct: StringKey? = null,
    val toleranceAbs: StringKey? = null,
    val date: StringKey? = null,
    val endDate: StringKey? = null,
    val supplier: StringKey? = null,
) {
    val isEmpty: Boolean get() = this == ContractFormErrors()

    /**
     * The same verdicts keyed by the field they belong to, in on-screen order, so a Save press can
     * scroll to and focus the first one (UI-E2-06 / SYS-30).
     */
    fun byField(): Map<ContractFormField, StringKey> =
        buildMap {
            name?.let { put(ContractFormField.NAME, it) }
            supplier?.let { put(ContractFormField.SUPPLIER, it) }
            interval?.let { put(ContractFormField.INTERVAL, it) }
            dueWeekday?.let { put(ContractFormField.DUE_WEEKDAY, it) }
            dueDay?.let { put(ContractFormField.DUE_DAY, it) }
            date?.let { put(ContractFormField.START_DATE, it) }
            endDate?.let { put(ContractFormField.END_DATE, it) }
            amount?.let { put(ContractFormField.EXPECTED_AMOUNT, it) }
            tolerancePct?.let { put(ContractFormField.TOLERANCE_PCT, it) }
            toleranceAbs?.let { put(ContractFormField.TOLERANCE_ABS, it) }
        }.let { unordered -> ContractFormField.entries.filter { it in unordered }.associateWith { unordered.getValue(it) } }

    /**
     * After an edit: the verdicts for [form], but only on fields that were already flagged. A fixed
     * field loses its error as the operator types; a field that was never flagged does not light up
     * mid-typing before Save is pressed again.
     */
    fun recheckedFor(form: ContractForm): ContractFormErrors {
        if (isEmpty) return this
        val now = of(form)
        return ContractFormErrors(
            name = now.name.takeIf { name != null },
            dueDay = now.dueDay.takeIf { dueDay != null },
            dueWeekday = now.dueWeekday.takeIf { dueWeekday != null },
            interval = now.interval.takeIf { interval != null },
            amount = now.amount.takeIf { amount != null },
            tolerancePct = now.tolerancePct.takeIf { tolerancePct != null },
            toleranceAbs = now.toleranceAbs.takeIf { toleranceAbs != null },
            date = now.date.takeIf { date != null },
            endDate = now.endDate.takeIf { endDate != null },
            supplier = now.supplier.takeIf { supplier != null },
        )
    }

    companion object {
        fun of(form: ContractForm) =
            ContractFormErrors(
                name = AdminStringKeys.ContractErrorNameRequired.takeIf { form.name.isBlank() },
                // `?: 0` folds "not a number at all" into the same out-of-range answer.
                dueDay = AdminStringKeys.ContractErrorDueDay.takeIf { (form.dueDay.trim().toIntOrNull() ?: 0) !in 1..31 },
                // WEEKLY is the only interval that uses a weekday, and the server rejects a WEEKLY
                // contract without one with a 400. Fold "not a number at all" into the same
                // out-of-range answer, exactly as dueDay above does.
                dueWeekday =
                    AdminStringKeys.ContractErrorDueWeekday.takeIf {
                        form.interval == "WEEKLY" && (form.dueWeekday.trim().toIntOrNull() ?: 0) !in 1..7
                    },
                interval = AdminStringKeys.ContractErrorInterval.takeIf { form.interval !in ContractForm.INTERVALS },
                amount = AdminStringKeys.ContractErrorAmount.takeIf { form.expectedAmount.isUnparseableMoney() },
                tolerancePct = AdminStringKeys.ContractErrorAmount.takeIf { form.tolerancePct.isUnparseableMoney() },
                toleranceAbs = AdminStringKeys.ContractErrorAmount.takeIf { form.toleranceAbs.isUnparseableMoney() },
                // The start date is required; blank says so rather than asking for a format.
                date =
                    when {
                        form.startDate.isBlank() -> CommonKeys.fieldRequired
                        !ISO_DATE.matches(form.startDate.trim()) -> AdminStringKeys.ContractErrorDate
                        else -> null
                    },
                endDate = AdminStringKeys.ContractErrorDate.takeIf { form.endDate.isNotBlank() && !ISO_DATE.matches(form.endDate.trim()) },
                // A blank id is "no supplier"; anything else that is not an integer must not be
                // quietly turned into null by toLongOrNull on the way out.
                supplier =
                    AdminStringKeys.ContractErrorSupplier.takeIf {
                        form.supplierId.isNotBlank() && form.supplierId.trim().toLongOrNull() == null
                    },
            )
    }
}

/** A blank amount is absent, not invalid — the columns are nullable. */
private fun String.isUnparseableMoney(): Boolean = isNotBlank() && trim().toCanonicalMoneyOrNull() == null
