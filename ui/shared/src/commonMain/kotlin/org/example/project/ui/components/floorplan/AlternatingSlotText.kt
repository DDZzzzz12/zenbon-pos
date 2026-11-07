package org.example.project.ui.components.floorplan

/**
 * Picks the text for the alternating employee↔amount info slot on a compact/minimal
 * floor-plan table card.
 *
 * - Both present: alternates — [amount] when [showAmount] is true, otherwise [employee].
 * - Exactly one present: that one, statically (the [showAmount] phase is ignored).
 * - Neither present: null (render nothing).
 */
internal fun alternatingSlotText(
    showAmount: Boolean,
    employee: String?,
    amount: String?,
): String? =
    when {
        employee != null && amount != null -> if (showAmount) amount else employee
        else -> amount ?: employee
    }
