package org.example.project.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.auth.presentation.state.TemporarySessionUiState
import org.example.project.features.auth.presentation.state.offersRequestSession
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownAnchor
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.screens.auth.components.RequestSessionButton

@Composable
fun PosNavRail(
    selectedDestination: PosNavDestination,
    onDestinationSelected: (PosNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    sessionIndicator: (@Composable () -> Unit)? = null,
    managementItems: List<PosNavDestination> = emptyList(),
    onManagementItemClick: (PosNavDestination) -> Unit = {},
    notificationUnreadCount: Int = 0,
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
    /** Station only: the cold-reset path (clears session + device token, back to pairing). Replaces logout. */
    onRepair: () -> Unit = {},
    /** Opens the web admin (`<base>/admin/`) in the system browser. Offered on both shells, ungated. */
    onOpenBackoffice: () -> Unit = {},
    // Temporary session support
    sessionState: TemporarySessionUiState? = null,
    sessionManager: TemporarySessionManager? = null,
    currentLanguage: Language = Language.ENGLISH,
) {
    NavigationRail(
        modifier = modifier.width(100.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        rememberVisibleDestinations(NavGroup.PRIMARY)
            .forEach { destination ->
                val selected = destination == selectedDestination
                NavigationRailItem(
                    selected = selected,
                    onClick = { onDestinationSelected(destination) },
                    icon = {
                        if (destination == PosNavDestination.NOTIFICATIONS && notificationUnreadCount > 0) {
                            BadgedBox(badge = { Badge { Text(notificationUnreadCount.toString()) } }) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = str(destination.labelKey),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = str(destination.labelKey),
                            )
                        }
                    },
//                    label = { Text(str(destination.labelKey)) },
                    colors =
                        NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }

        if (managementItems.isNotEmpty()) {
            var managementOpen by remember { mutableStateOf(false) }
            // The Box is load-bearing: PosDropdown positions itself against its parent layout node,
            // so as a bare sibling of the rail item it would anchor to the whole rail instead of to
            // the button that opened it.
            Box {
                NavigationRailItem(
                    selected = managementOpen,
                    onClick = { managementOpen = true },
                    icon = { Icon(Icons.Default.Apps, str(MainScreenKeys.management)) },
                    colors =
                        NavigationRailItemDefaults.colors(
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
                // EndTop: the rail is the left edge of the window, so the menu opens beside the
                // button rather than below it, where a full-height rail leaves no room.
                PosDropdown(
                    expanded = managementOpen,
                    onDismissRequest = { managementOpen = false },
                    anchor = PosDropdownAnchor.EndTop,
                    minWidth = 220.dp,
                ) {
                    managementItems.forEach { dest ->
                        PosDropdownItem(
                            text = str(dest.labelKey),
                            leadingIcon = dest.icon,
                            selected = dest == selectedDestination,
                            onClick = {
                                managementOpen = false
                                onManagementItemClick(dest)
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        sessionIndicator?.invoke()

        // Request-session button: the generic manager-override entry (design §3.2). Offered to
        // whoever could still be lent a till override — a low-privilege person — and withheld from
        // a manager, who holds all, and from a station, where a step-up can never succeed and has
        // nothing to unlock (NOTE-10). The station test is the session marker, never a role: a
        // person in the TERMINAL_DEVICE group keeps the button. Read through LocalAccessPolicy so it
        // leaves when a step-up starts and returns when it ends.
        if (sessionState.offersRequestSession(LocalAccessPolicy.current, rememberIsStation())) {
            RequestSessionButton(
                onClick = { sessionManager?.showDialog() },
                compact = true,
                language = currentLanguage,
            )
        }

        // UTILITY goes through the same gate as PRIMARY and MANAGEMENT. Settings used to be a
        // hardcoded item behind `if (!isStation)`, which made PosNavDestination.SETTINGS's
        // `visibleToTerminal = false` decorative — the flag was asserted by tests while a second,
        // untested copy of the rule was what actually ran — and skipped the capability axis
        // entirely, offering Settings to a group holding no CONFIG_READ.
        rememberVisibleDestinations(NavGroup.UTILITY)
            .forEach { destination ->
                NavigationRailItem(
                    selected = selectedDestination == destination,
                    onClick = { onDestinationSelected(destination) },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = str(destination.labelKey),
                        )
                    },
                    label = { Text(str(destination.labelKey)) },
                    colors =
                        NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }

        // Back office, on both shells. Outside the manager-gated menu and reachable from every
        // destination; ungated because the panel enforces its own login, and hiding it on a
        // station would only push staff to type the server address by hand.
        NavigationRailItem(
            selected = false,
            onClick = onOpenBackoffice,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = str(LaunchKeys.openBackoffice),
                )
            },
            label = { Text(str(LaunchKeys.openBackoffice), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) },
            colors =
                NavigationRailItemDefaults.colors(
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )

        // Logout — or, on a station, "re-pair this device": an order station has no person to sign
        // out, and leaving it means handing the terminal back to pairing. `onRepair` goes through
        // TerminalColdResetBus, the one teardown that also clears the device token.
        val isStation = rememberIsStation()
        val leaveLabel = str(if (isStation) LaunchKeys.repairDevice else MainScreenKeys.logout)
        NavigationRailItem(
            selected = false,
            onClick = if (isStation) onRepair else onLogout,
            icon = {
                Icon(
                    imageVector = if (isStation) Icons.Default.QrCode else Icons.AutoMirrored.Filled.Logout,
                    contentDescription = leaveLabel,
                )
            },
            label = { Text(leaveLabel, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) },
            colors =
                NavigationRailItemDefaults.colors(
                    unselectedIconColor = MaterialTheme.colorScheme.error,
                    unselectedTextColor = MaterialTheme.colorScheme.error,
                ),
        )
    }
}
