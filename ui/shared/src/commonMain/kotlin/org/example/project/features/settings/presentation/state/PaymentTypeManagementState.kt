package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.presentation.error.UiError

/**
 * Payment type management state.
 */
data class PaymentTypeManagementState(
    val paymentTypes: List<PaymentTypeDto.PaymentTypeResponseDto> = emptyList(),
    val isLoading: Boolean = false,
    val showAddDialog: Boolean = false,
    val editingPaymentType: PaymentTypeDto.PaymentTypeResponseDto? = null,
    /** A create/update from the add/edit dialog is in flight: its Save shows progress and ignores presses. */
    val isSaving: Boolean = false,
    val error: UiError? = null,
)
