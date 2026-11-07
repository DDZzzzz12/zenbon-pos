package org.example.project.admin.features.contracts.presentation

import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.error.UiError

/**
 * One contract's form and its instalments.
 *
 * A null [id] is the **create** form: the same fields, with no instalments, because a contract has
 * none until the nightly job has run against it.
 *
 * [pristine] is the form as last loaded or saved, so [isDirty] reports clean after an edit-and-undo.
 */
data class ContractDetailState(
    val id: Long? = null,
    val form: ContractForm = ContractForm(),
    val pristine: ContractForm = ContractForm(),
    val instalments: List<ContractDto.InstalmentDto> = emptyList(),
    /** This contract's alerts only — the endpoint answers for every active contract. */
    val alerts: List<ContractAlertDto.AlertDto> = emptyList(),
    val selectedInstalmentId: Long? = null,
    /** The suppliers the picker offers; the form itself keeps only the chosen id. */
    val suppliers: List<SupplierDto.ResponseDto> = emptyList(),
    /** The name the server resolved for the loaded supplier id, shown until the picker's list arrives. */
    val loadedSupplierName: String? = null,
    /** The contract's currency, for the amount fields' suffix. */
    val currency: String = "EUR",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errors: ContractFormErrors = ContractFormErrors(),
    /** Bumped on every Save press that found errors, so the screen scrolls to the first one each time. */
    val validationAttempt: Int = 0,
    val error: UiError? = null,
) {
    /** A new contract is dirty from the start — there is nothing saved to compare it against. */
    val isDirty: Boolean get() = id == null || form != pristine

    /**
     * True only when the form differs from what was last loaded or saved. Unlike [isDirty] this is
     * false for an untouched create form, so the leave guard never prompts on an empty one.
     */
    val hasUnsavedEdits: Boolean get() = form != pristine

    val selectedInstalment: ContractDto.InstalmentDto?
        get() = instalments.firstOrNull { it.id == selectedInstalmentId }

    val nameError get() = errors.name

    val dueDayError get() = errors.dueDay

    val weekdayError get() = errors.dueWeekday

    val intervalError get() = errors.interval

    val amountError get() = errors.amount

    val dateError get() = errors.date

    val endDateError get() = errors.endDate

    val supplierError get() = errors.supplier
}
