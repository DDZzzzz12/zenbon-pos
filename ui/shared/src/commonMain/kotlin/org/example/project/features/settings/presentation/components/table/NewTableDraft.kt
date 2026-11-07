package org.example.project.features.settings.presentation.components.table

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.example.project.core.presentation.strings.StringKey

/** The new-table draft (number/seats/location) and its create step; shared by the table row and the card form. */
@Stable
internal class NewTableDraft(
    initialLocationTypeId: Long?,
) {
    private var number by mutableStateOf("")

    /** Typing into the number clears its validation error. */
    var numberText: String
        get() = number
        set(value) {
            number = value
            fieldErrors = fieldErrors - TableFormField.NUMBER
        }
    var seatsText by mutableStateOf("")
    var locationTypeId by mutableStateOf(initialLocationTypeId)

    /** Errors of the last failed [submit]; transient, not part of [isDirty]. */
    var fieldErrors by mutableStateOf<Map<TableFormField, StringKey>>(emptyMap())
        private set

    /** Bumped on every failed [submit], so the form reveals the first invalid field each time. */
    var validationAttempt by mutableStateOf(0)
        private set

    /** Unsaved input worth asking about before leaving: a typed number or seat count (the location alone is a default). */
    val isDirty: Boolean get() = numberText.isNotEmpty() || seatsText.isNotEmpty()

    fun locationName(locationTypes: List<Pair<Long, String>>): String =
        locationTypes.firstOrNull { it.first == locationTypeId }?.second
            ?: locationTypes.firstOrNull()?.second ?: ""

    /**
     * Validates the draft; on errors records them (and bumps [validationAttempt]) without creating anything.
     * Otherwise creates the table, then resets to a blank draft on the first location.
     */
    fun submit(
        locationTypes: List<Pair<Long, String>>,
        onCreate: (tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    ) {
        val errors = validateTableInput(numberText)
        if (errors.isNotEmpty()) {
            fieldErrors = errors
            validationAttempt++
            return
        }
        val locId = locationTypeId ?: locationTypes.firstOrNull()?.first ?: return
        onCreate(numberText, seatsText.toIntOrNull(), locId)
        numberText = ""
        seatsText = ""
        locationTypeId = locationTypes.firstOrNull()?.first
    }
}

@Composable
internal fun rememberNewTableDraft(locationTypes: List<Pair<Long, String>>): NewTableDraft {
    val first = locationTypes.firstOrNull()?.first
    return remember(first) { NewTableDraft(first) }
}
