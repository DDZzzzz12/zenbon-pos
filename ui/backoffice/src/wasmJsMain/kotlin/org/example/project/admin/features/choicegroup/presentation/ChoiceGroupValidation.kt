package org.example.project.admin.features.choicegroup.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import org.example.project.core.utils.toCanonicalMoneyOrNull

/** Min selections as sent: blank or unreadable counts as 0. */
internal val GroupEditor.minSelectionsValue: Int get() = minSelections.toIntOrNull() ?: 0

/** Max selections as sent: blank or unreadable counts as 1. */
internal val GroupEditor.maxSelectionsValue: Int get() = maxSelections.toIntOrNull() ?: 1

/** True when this row's delta cannot be read as money; blank is read as 0 and is fine. */
internal fun OptionRow.hasUnreadablePriceDelta(): Boolean = priceDelta.isNotBlank() && priceDelta.toCanonicalMoneyOrNull() == null

/** Client-side validation of a choice-group draft: one message key per invalid field; empty = savable. */
internal fun validateGroupDraft(e: GroupEditor): Map<ChoiceGroupField, StringKey> =
    buildMap {
        if (e.name.isBlank()) put(ChoiceGroupField.NAME, AdminStringKeys.ProductFieldRequired)
        val min = e.minSelectionsValue
        val max = e.maxSelectionsValue
        if (min < 0 || max < 1 || min > max) put(ChoiceGroupField.SELECTION_RANGE, ChoiceGroupKeys.invalidSelectionRange)
        if (e.options.any { it.hasUnreadablePriceDelta() }) put(ChoiceGroupField.OPTION_PRICES, BackOfficeErrorKeys.priceInvalid)
    }

/** Drops the error of every field whose value changed from [previous]. */
internal fun GroupEditor.withEditedErrorsCleared(previous: GroupEditor): GroupEditor {
    if (fieldErrors.isEmpty()) return this
    val changed =
        buildSet {
            if (name != previous.name) add(ChoiceGroupField.NAME)
            if (minSelections != previous.minSelections || maxSelections != previous.maxSelections) {
                add(ChoiceGroupField.SELECTION_RANGE)
            }
            if (options.map { it.priceDelta } != previous.options.map { it.priceDelta }) add(ChoiceGroupField.OPTION_PRICES)
        }
    return copy(fieldErrors = fieldErrors.filterKeys { it !in changed })
}
