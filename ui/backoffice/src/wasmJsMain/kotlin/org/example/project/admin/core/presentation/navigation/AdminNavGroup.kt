package org.example.project.admin.core.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey

/**
 * Sidebar groups, in render order. [PINNED] carries no header — its items sit above the first
 * group label, which is why it alone has neither a label nor an [icon].
 *
 * The organising axis is cadence and audience: [OPERATIONS] is what changes as the business
 * changes, [DEVICES] is hardware you pair and monitor, [COMPLIANCE] is what the Finanzamt cares
 * about, [SYSTEM] is automation and set-once configuration.
 *
 * The three middle groups apply that same axis to what used to be one 11-item `FINANCIAL` list:
 * [CASH_CLOSING] is the day's takings — the one in progress, then the closed periods and their
 * evaluation — [DOCUMENT_FLOW] is touched daily, and [MASTER_DATA] is configured once and then
 * referenced. Splitting by cadence rather than by accounting direction keeps Lieferanten and
 * Debitoren together, which is what a bookkeeper editing master data actually wants.
 *
 * [CASH_CLOSING] sits directly under [OPERATIONS] because "what did we take today" is the question
 * the back office opens for most, and [DEVICES] moved below the bookkeeping groups: pairing a till
 * or a printer is something you do when installing one, not daily. Note this makes the group's
 * first entry, the current-day report, the only item in it about a period that is still open.
 *
 * **The order of the entries below is the order the sidebar renders its groups in, and it must
 * match the order the items are declared in [AdminNavItem].** `groupedFor` builds its map with
 * `groupBy`, which keys on encounter order, so the item file is what actually drives the drawer;
 * this enum only looks authoritative. `AdminNavItemTest.adminGetsEveryGroupInDeclarationOrder`
 * asserts the two lists are equal precisely so they cannot drift apart unnoticed — change one and
 * you must change the other.
 *
 * [icon] exists so a header renders as a row of the same build as the destinations under it —
 * icon, label, trailing control — rather than as floating text with nothing to its left. Each is
 * chosen NOT to collide with any destination's own icon, so a header never reads as a place you
 * can navigate to.
 */
enum class AdminNavGroup(
    val labelKey: StringKey?,
    val icon: ImageVector?,
) {
    PINNED(null, null),
    OPERATIONS(AdminStringKeys.NavGroupOperations, Icons.Default.Storefront),
    CASH_CLOSING(AdminStringKeys.NavGroupCashClosing, Icons.Default.AccountBalanceWallet),
    DOCUMENT_FLOW(AdminStringKeys.NavGroupDocumentFlow, Icons.Default.Inbox),
    MASTER_DATA(AdminStringKeys.NavGroupMasterData, Icons.Default.Storage),
    DEVICES(AdminStringKeys.NavGroupDevices, Icons.Default.Devices),
    COMPLIANCE(AdminStringKeys.NavGroupCompliance, Icons.Default.VerifiedUser),
    SYSTEM(AdminStringKeys.NavGroupSystem, Icons.Default.Tune),
}
