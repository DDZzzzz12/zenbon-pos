package org.example.project.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.MainScreenKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownAnchor
import org.example.project.ui.components.PosDropdownItem

@Composable
fun PosBottomBar(
    selectedDestination: PosNavDestination,
    onDestinationSelected: (PosNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    managementItems: List<PosNavDestination> = emptyList(),
    onManagementItemClick: (PosNavDestination) -> Unit = {},
    /** Station only: the cold-reset path (clears session + device token, back to pairing). */
    onRepair: () -> Unit = {},
    /** Opens the web admin (`<base>/admin/`) in the system browser. Offered on both shells, ungated. */
    onOpenBackoffice: () -> Unit = {},
) {
    val primaries = rememberVisibleDestinations(NavGroup.PRIMARY)
    // Settings is a UTILITY destination, so it is not in `managementItems`; ask the same gate the
    // rail uses (capability + station axis) rather than enumerating destinations here.
    val settings = settingsEntry(rememberVisibleDestinations(NavGroup.UTILITY))
    val plan = planPhoneBar(primaries, managementItems, settings)

    // "Re-pair this device" is the compact shell's ONLY way off a station, so the menu has to open
    // even when it would otherwise be empty. A person needs no such item here: their leave action
    // is Logout inside Settings, a destination a station does not get (`visibleToTerminal = false`),
    // which is exactly why the bar had nothing for a station before.
    val isStation = rememberIsStation()
    val repairOnMenu = if (isStation) onRepair else null
    // The footer actions this menu carries beyond the destinations: the back office on every
    // shell, plus the cold-reset exit on a station. Never zero, which is what makes the menu open
    // for a station holding no management destinations at all.
    val footerActions = 1 + if (repairOnMenu != null) 1 else 0
    val hasMenu = plan.menuItems.isNotEmpty() || footerActions > 0

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        plan.barItems.forEachIndexed { index, destination ->
            if (index == plan.menuSlot && hasMenu) {
                ManagementBarItem(plan.menuItems, selectedDestination, onManagementItemClick, onOpenBackoffice, repairOnMenu)
            }
            DestinationBarItem(
                destination = destination,
                selected = destination == selectedDestination,
                onClick = { onDestinationSelected(destination) },
            )
        }
        if (plan.menuSlot >= plan.barItems.size && hasMenu) {
            ManagementBarItem(plan.menuItems, selectedDestination, onManagementItemClick, onOpenBackoffice, repairOnMenu)
        }
    }
}

/**
 * The Verwaltung slot: a bar item that opens its destinations as a [PosDropdown] instead of
 * navigating.
 *
 * `TopCenter` because the bar sits at the bottom of the window — a menu below it would open off
 * screen — and because a menu hanging from the left edge of a centred bar item reads as belonging
 * to the item on its left. The `Box` is load-bearing: [PosDropdown] positions against its parent
 * layout node, so as a bare sibling it would anchor to the whole bar rather than to this slot.
 *
 * [onOpenBackoffice] is offered on every shell; [onRepair] is non-null only on a station. Both add
 * an action below the destinations — the compact counterpart of the rail's footer items, put here
 * rather than in a fourth bar slot because the bar holds three comfortably and neither is a
 * destination. Settings and the management destinations are the menu's items; Reservations stays
 * in the bar.
 */
@Composable
private fun RowScope.ManagementBarItem(
    items: List<PosNavDestination>,
    selectedDestination: PosNavDestination,
    onItemClick: (PosNavDestination) -> Unit,
    onOpenBackoffice: () -> Unit,
    onRepair: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    // The Box carries the bar's weight so this slot is as wide as the others, and the inner Row
    // gives NavigationBarItem the RowScope it requires. Reaching past the Box with an explicit
    // `this@ManagementBarItem.NavigationBarItem` instead puts a Row weight on a Box child: the Box
    // then wraps its content, the weighted siblings are starved, and the bar collapses to this one
    // item. The Box cannot simply be dropped — PosDropdown anchors to it.
    Box(modifier = Modifier.weight(1f)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            NavigationBarItem(
                selected = expanded || selectedDestination in items,
                onClick = { expanded = true },
                icon = { Icon(Icons.Default.Apps, contentDescription = str(MainScreenKeys.management)) },
                label = {
                    Text(
                        text = str(MainScreenKeys.management),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 12.sp, 1.sp),
                    )
                },
                colors = barItemColors(),
            )
        }
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchor = PosDropdownAnchor.TopCenter,
            minWidth = 220.dp,
        ) {
            items.forEach { destination ->
                PosDropdownItem(
                    text = str(destination.labelKey),
                    leadingIcon = destination.icon,
                    selected = destination == selectedDestination,
                    onClick = {
                        expanded = false
                        onItemClick(destination)
                    },
                )
            }
            // Back office, on both shells — the compact counterpart of the rail's footer item.
            PosDropdownItem(
                text = str(LaunchKeys.openBackoffice),
                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = {
                    expanded = false
                    onOpenBackoffice()
                },
            )
            if (onRepair != null) {
                PosDropdownItem(
                    text = str(LaunchKeys.repairDevice),
                    leadingIcon = Icons.Default.QrCode,
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        expanded = false
                        onRepair()
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.DestinationBarItem(
    destination: PosNavDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = destination.icon,
                contentDescription = str(destination.labelKey),
            )
        },
        label = {
            Text(
                text = str(destination.labelKey),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 12.sp, 1.sp),
            )
        },
        colors = barItemColors(),
    )
}

@Composable
private fun barItemColors() =
    NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
