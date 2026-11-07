package org.example.project.features.settings.presentation.components.table

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Client-side validation of a table's inputs, run when the add/save action is pressed (the action is
 * never disabled for an empty or invalid row): one message key per invalid field; empty = savable.
 * The location always holds a value (it defaults to the first location type) and seats are optional
 * digits, so only the table number can be missing.
 */
internal fun validateTableInput(numberText: String): Map<TableFormField, StringKey> =
    buildMap {
        if (numberText.isBlank()) put(TableFormField.NUMBER, CommonKeys.fieldRequired)
    }
