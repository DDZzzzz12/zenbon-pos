package org.example.project.admin.features.dashboard.presentation.checklist

import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.presentation.strings.StringKey

/** Where a checklist row sends the operator when tapped. */
enum class ChecklistDestination {
    TERMINALS,
    TSE,
    PRODUCTS,
    TABLES,

    /** A settings section — pair with [SetupChecklistItem.settingsSection]. */
    SETTINGS,
}

/**
 * Whether a row is satisfied.
 *
 * [BLOCKED] exists for the TSE row only, for two reasons. A TSE cannot be commissioned before a
 * fiscal terminal is paired, so telling the operator to go commission one would send them to a
 * screen they cannot use; that row points at the prerequisite instead. And commissioning is
 * ADMIN-only while the card itself renders for MANAGER+, so for a manager the row has no
 * destination at all rather than one the route gate would bounce.
 *
 * A blocked row renders greyed, and is clickable only when it still has somewhere useful to go.
 */
enum class ChecklistState { PASSED, FAILED, BLOCKED }

/**
 * One row of the dashboard readiness checklist.
 *
 * Every row is a hard blocker on trading. There is deliberately no "recommended" tier: the moment
 * "you have no logo" sits beside "you have no TSE", the card stops being a readiness gate and becomes
 * a to-do list people learn to ignore.
 */
data class SetupChecklistItem(
    val id: String,
    val state: ChecklistState,
    val labelKey: StringKey,
    val recommendationKey: StringKey,
    /**
     * Where tapping the row leads, or `null` when the operator's role cannot act on it anywhere.
     *
     * A null destination renders the row without a click affordance rather than hiding it: every
     * row is a hard blocker on trading, so an operator who cannot fix one still needs to see that
     * it is outstanding — they just are not offered a navigation the route gate would refuse.
     */
    val destination: ChecklistDestination? = null,
    /** Only meaningful when [destination] is [ChecklistDestination.SETTINGS]. */
    val settingsSection: SettingsSection? = null,
)
