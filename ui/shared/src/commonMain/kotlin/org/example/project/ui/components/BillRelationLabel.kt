package org.example.project.ui.components

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.presentation.strings.StringKey

/**
 * One visible relation of a bill — a chip in the list or a link in the detail panel.
 *
 * [key] is localized with [argument] as `{0}`; [target] is the bill a tap opens, or null for a pure
 * status ("Storniert", "Zahlungsart geändert"). [alsoTargets] are further bills the same chip names
 * ("Neubuchung zu RE-4, RE-7" after the table was re-opened twice). [isWarning] tints the chip as a
 * reversal rather than a neutral note.
 */
data class BillRelationLabel(
    val key: StringKey,
    val target: BillDto.BillLinkDto? = null,
    val isWarning: Boolean = false,
    val alsoTargets: List<BillDto.BillLinkDto> = emptyList(),
) {
    /** What fills `{0}`: the linked bill numbers, comma-joined; null for a pure status. */
    val argument: String?
        get() = target?.let { first -> (listOf(first) + alsoTargets).joinToString(", ") { it.billNumber } }
}
