package org.example.project.core.domain.reason

import org.example.project.core.presentation.strings.StringKey

/**
 * One tappable reason for a till action. [labelKey] is shown in the cashier's language; [storedText]
 * is the fixed German text written to the audit trail whatever language the till shows. For the
 * "Other" entry [storedText] is empty: the cashier's typed text is stored instead.
 */
data class ActionReason(
    val labelKey: StringKey,
    val storedText: String,
    val isOther: Boolean = false,
)
