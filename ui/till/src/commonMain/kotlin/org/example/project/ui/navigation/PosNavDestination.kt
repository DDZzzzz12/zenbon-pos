package org.example.project.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys

/**
 * Every destination in the POS shell, and what it takes to reach one.
 *
 * [requiredCapability] replaced a `minRole` floor: the two axes it conflated are now separate, and
 * the sidebar asks the same question the server does. An accountant carries role `MANAGER` and group
 * `ACCOUNTANT`, so an ordinal role comparison offers them screens the server refuses.
 *
 * **[visibleToTerminal] is deliberately NOT a capability**, and stays a flag. It answers "is this
 * principal a person or an unattended station", which is the STATION axis, not the identity one — a
 * login-less terminal holds `SELF_READ` yet has no personal profile to show. Folding it into a
 * capability would mean inventing one that means "is a human", which is a role wearing a different
 * hat. Since the order-station shell (2026-09-06) the flag is read against `TokenStore.stationRole`
 * — the client mirror of the server's `station` JWT claim — and no longer against a role: a person
 * whose group is TERMINAL_DEVICE is a person.
 */
enum class PosNavDestination(
    val group: NavGroup,
    val icon: ImageVector,
    val labelKey: StringKey,
    val hasRoute: Boolean = true,
    val requiredCapability: Capability? = null,
    val visibleToTerminal: Boolean = false,
    val requiresVoucher: Boolean = false,
) {
    // Primary
    DASHBOARD(NavGroup.PRIMARY, Icons.Default.GridView, MainScreenKeys.dashboard, visibleToTerminal = true),
    RESERVATIONS(
        NavGroup.PRIMARY,
        Icons.Default.CalendarMonth,
        ReservationKeys.title,
        requiredCapability = Capability.RESERVATION_READ,
        visibleToTerminal = true,
    ),
    NOTIFICATIONS(
        NavGroup.PRIMARY,
        Icons.Default.Notifications,
        MainScreenKeys.notifications,
        requiredCapability = Capability.NOTIFICATION_READ,
        visibleToTerminal = true,
    ),

    // Management
    // REPORT_READ sits in CASHIER and above since Stage 2b, which is exactly who saw this before.
    REPORTS(
        NavGroup.MANAGEMENT,
        Icons.Default.Assessment,
        MainScreenKeys.reports,
        requiredCapability = Capability.REPORT_READ,
    ),

    // CASHBOOK_READ, not DOCUMENT_READ or ZBON_READ: the screen fronts all three, but only the
    // cash ledger is manager-level, and `minRole = MANAGER` is what it replaces. The other two moved
    // to CASHIER in Stage 2b and would have widened this.
    FINANCIAL_DOCS(
        NavGroup.MANAGEMENT,
        Icons.Default.Folder,
        MainScreenKeys.financialDocuments,
        requiredCapability = Capability.CASHBOOK_READ,
    ),

    // Utility
    MY_PROFILE(
        NavGroup.UTILITY,
        Icons.Default.Person,
        MainScreenKeys.myProfile,
        requiredCapability = Capability.SELF_READ,
    ),

    // CONFIG_READ is in the station mask (a station has to read its config), so the capability
    // alone would keep this visible on an order station. The station axis withholds it: nothing on
    // a station needs local settings, and the back office is one footer tap away.
    SETTINGS(
        NavGroup.UTILITY,
        Icons.Default.Settings,
        MainScreenKeys.setting,
        requiredCapability = Capability.CONFIG_READ,
        visibleToTerminal = false,
    ),
}
