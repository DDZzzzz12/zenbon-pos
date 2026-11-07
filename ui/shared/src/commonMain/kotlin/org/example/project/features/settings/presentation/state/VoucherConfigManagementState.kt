package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.presentation.error.UiError

/**
 * Voucher configuration management state.
 *
 * Manages the state for voucher configuration UI including
 * voucher sources (INTERNAL, LEGACY, EXTERNAL, etc.) and their settings.
 */
data class VoucherConfigManagementState(
    /** Current voucher configuration */
    val voucherConfig: VoucherConfig? = null,
    /** Whether configuration is being loaded from server */
    val isLoading: Boolean = false,
    /** Whether to show the add/edit source dialog */
    val showAddDialog: Boolean = false,
    /** Voucher source being edited (null if adding new) */
    val editingSource: VoucherSource? = null,
    /** Typed error if operation fails */
    val error: UiError? = null,
)
