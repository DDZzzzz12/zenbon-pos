package org.example.project.core.utils

import org.example.project.core.domain.reason.ActionReasons

/**
 * A till action's reason must be present, not blank and at most [ActionReasons.MAX_LENGTH] characters.
 * Returns the trimmed text; throws [IllegalArgumentException] otherwise.
 */
fun String?.requireValidReason(): String {
    val text = this?.trim()
    require(!text.isNullOrEmpty()) { "A reason is required" }
    // The raw length too: callers store the untrimmed text, and the columns are VARCHAR(500).
    require(this.length <= ActionReasons.MAX_LENGTH) { "Reason cannot exceed ${ActionReasons.MAX_LENGTH} characters" }
    return text
}
