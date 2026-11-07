package org.example.project.features.settings.presentation.state

import org.example.project.core.presentation.error.UiError

/**
 * UI state for voucher configuration screen.
 *
 * Wraps [VoucherConfigManagementState] with top-level loading, error,
 * and save success fields used by [VoucherConfigViewModel].
 *
 * @property voucherConfig Voucher configuration management sub-state
 * @property isLoading Whether a top-level operation is in progress
 * @property error Typed error if an operation fails
 * @property saveSuccess Whether last save operation was successful
 */
data class VoucherConfigUiState(
    val voucherConfig: VoucherConfigManagementState = VoucherConfigManagementState(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val saveSuccess: Boolean = false,
)
