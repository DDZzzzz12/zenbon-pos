package org.example.project.ui.navigation

/**
 * What the phone bottom bar shows. Pure, so the composition rules are testable without composing.
 *
 * Notifications are not a bar destination on a phone — the dashboard top bar carries a badged bell
 * instead — and Settings lives in the Verwaltung menu. The menu is pinned to the middle slot
 * (clamped to the item count) so Dashboard stays on the left whatever the user is allowed to see.
 */
internal data class PhoneBarPlan(
    val barItems: List<PosNavDestination>,
    val menuSlot: Int,
    val menuItems: List<PosNavDestination>,
)

internal fun planPhoneBar(
    primaries: List<PosNavDestination>,
    management: List<PosNavDestination>,
    settings: PosNavDestination?,
): PhoneBarPlan {
    val barItems = primaries.filterNot { it == PosNavDestination.NOTIFICATIONS }
    return PhoneBarPlan(
        barItems = barItems,
        menuSlot = minOf(1, barItems.size),
        menuItems = management + listOfNotNull(settings),
    )
}

/**
 * Picks Settings out of the already-gated UTILITY destinations. Lives here rather than in
 * `PosBottomBar` because `NavContainersGateDestinationsTest` forbids a nav container from naming a
 * utility destination; the input has passed `rememberVisibleDestinations(NavGroup.UTILITY)`.
 */
internal fun settingsEntry(gatedUtility: List<PosNavDestination>): PosNavDestination? = gatedUtility.firstOrNull { it == PosNavDestination.SETTINGS }
