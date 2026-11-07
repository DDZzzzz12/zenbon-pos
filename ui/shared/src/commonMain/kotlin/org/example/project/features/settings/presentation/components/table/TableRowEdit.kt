package org.example.project.features.settings.presentation.components.table

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.StringKey

internal fun seatsSeedOf(table: RTable): String = if (table.seats > 0L) table.seats.toString() else ""

/**
 * Per-row edit buffer (number/seats/location) with its dirty check; shared by the table and card forms.
 * The table is passed to each query (not captured) because the buffer outlives list refreshes.
 */
@Stable
internal class TableRowEdit(
    seed: RTable,
) {
    private var number by mutableStateOf(seed.tableNumber)

    /** Typing into the number clears its validation error. */
    var numberText: String
        get() = number
        set(value) {
            number = value
            fieldErrors = fieldErrors - TableFormField.NUMBER
        }
    var seatsText by mutableStateOf(seatsSeedOf(seed))
    var locationTypeId by mutableStateOf(seed.locationTypeId)

    /** Errors of the last failed [submit]; transient, not part of [isDirty]. */
    var fieldErrors by mutableStateOf<Map<TableFormField, StringKey>>(emptyMap())
        private set

    /** Bumped on every failed [submit], so the form reveals the first invalid field each time. */
    var validationAttempt by mutableStateOf(0)
        private set

    fun isDirty(table: RTable): Boolean = numberText != table.tableNumber || seatsText != seatsSeedOf(table) || locationTypeId != table.locationTypeId

    fun locationName(locationTypes: List<Pair<Long, String>>): String =
        locationTypes.firstOrNull { it.first == locationTypeId }?.second ?: locationTypeId.toString()

    /** Validates the buffer; on errors records them (and bumps [validationAttempt]) instead of calling [onUpdate]. */
    fun submit(
        id: Long,
        onUpdate: (id: Long, tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    ) {
        val errors = validateTableInput(numberText)
        if (errors.isNotEmpty()) {
            fieldErrors = errors
            validationAttempt++
            return
        }
        onUpdate(id, numberText, seatsText.toIntOrNull(), locationTypeId)
    }
}

@Composable
internal fun rememberTableRowEdit(table: RTable): TableRowEdit = remember(table.id) { TableRowEdit(table) }
