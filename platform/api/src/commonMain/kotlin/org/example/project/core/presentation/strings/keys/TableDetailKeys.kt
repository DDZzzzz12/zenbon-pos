package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object TableDetailKeys {
    val cancelItems = StringKey("table_detail.cancel_items")
    val transferItems = StringKey("table_detail.transfer_items")
    val clearSelection = StringKey("table_detail.clear_selection")
    val selected = StringKey("table_detail.selected")
    val items = StringKey("table_detail.items")
    val selectedItems = StringKey("table_detail.selected_items")
    val cancelReason = StringKey("table_detail.cancel_reason")
    val cancelWarning = StringKey("table_detail.cancel_warning")
    val authorizationRequired = StringKey("table_detail.authorization_required")

    /** The Storno dialog under an active manager sign-in: who authorizes it (emu22 NOTE-ee). */
    val authorizedBy = StringKey("table_detail.authorized_by")
    val transferTo = StringKey("table_detail.transfer_to")
    val referencePrint = StringKey("table_detail.reference_print")
    val referencePrintSent = StringKey("table_detail.reference_print_sent")

    /**
     * Shown when the server answered 202: the action is stored and waiting for a manager. Without
     * it the till reported plain success and the cashier could not tell "waiting" from "nothing
     * happened" (E2E-2026-09-20 #6).
     */
    val awaitingManagerApproval = StringKey("table_detail.awaiting_manager_approval")
    val currentOrder = StringKey("table_detail.current_order")
    val noItems = StringKey("table_detail.no_items")
}
