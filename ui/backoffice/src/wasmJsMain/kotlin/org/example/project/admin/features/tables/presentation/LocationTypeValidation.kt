package org.example.project.admin.features.tables.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Client-side validation of the location-type dialog, run when Save is pressed (Save is never disabled
 * for an empty or invalid form): one message key per invalid field; empty = savable. A blank sort order
 * still means 0, as before; only text that is not a whole number is refused.
 */
internal fun validateLocationTypeInput(
    name: String,
    sortOrderText: String,
): Map<LocationTypeField, StringKey> =
    buildMap {
        if (name.isBlank()) put(LocationTypeField.NAME, CommonKeys.fieldRequired)
        if (sortOrderText.isNotBlank() && sortOrderText.trim().toIntOrNull() == null) {
            put(LocationTypeField.SORT_ORDER, AdminStringKeys.LocationTypeSortOrderInvalid)
        }
    }
