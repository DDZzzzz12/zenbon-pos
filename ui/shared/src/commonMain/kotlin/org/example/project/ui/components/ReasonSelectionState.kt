package org.example.project.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.project.core.domain.reason.ActionReason
import org.example.project.core.domain.reason.ActionReasons

/**
 * What the cashier has picked in a [ReasonChips] row. [value] is the text to send to the server:
 * the chip's German stored text, or the trimmed typed text for "Other"; null while nothing valid is chosen.
 */
class ReasonSelectionState(
    val reasons: List<ActionReason>,
) {
    var selected: ActionReason? by mutableStateOf(null)
        private set

    var otherText: String by mutableStateOf("")
        private set

    /** Set by [submitOrNull] when confirm was tapped without a valid reason; cleared on the next pick. */
    var showRequiredHint: Boolean by mutableStateOf(false)
        private set

    val value: String?
        get() {
            val chosen = selected ?: return null
            if (!chosen.isOther) return chosen.storedText
            return otherText.trim().ifEmpty { null }
        }

    val isValid: Boolean get() = value != null

    fun select(reason: ActionReason) {
        showRequiredHint = false
        otherText = ""
        selected = if (selected == reason) null else reason
    }

    fun onOtherTextChange(text: String) {
        showRequiredHint = false
        otherText = text.take(ActionReasons.MAX_LENGTH)
    }

    /** The reason to submit, or null after raising the "reason needed" hint. */
    fun submitOrNull(): String? {
        val current = value
        showRequiredHint = current == null
        return current
    }
}
