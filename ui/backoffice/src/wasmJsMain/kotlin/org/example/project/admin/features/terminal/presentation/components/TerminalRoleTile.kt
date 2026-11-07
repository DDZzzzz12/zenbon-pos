package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TabletMac
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.terminal.TerminalRole

/** The role's icon: Kasse, Bestellstation, Küchendisplay, Kundendisplay, Kiosk (anything else). */
internal fun terminalRoleIcon(role: String): ImageVector =
    when (role) {
        TerminalRole.CASHIER.name -> Icons.Filled.PointOfSale
        TerminalRole.ORDER_STATION.name -> Icons.Filled.TabletMac
        TerminalRole.KITCHEN.name -> Icons.Filled.Restaurant
        TerminalRole.CUSTOMER_DISPLAY.name -> Icons.Filled.Monitor
        else -> Icons.Filled.Storefront
    }

/** A square tile with the role's icon; a deactivated terminal gets the grey tile. Decorative: the row says the role in words. */
@Composable
internal fun TerminalRoleTile(
    role: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.size(size).background(container), contentAlignment = Alignment.Center) {
        Icon(terminalRoleIcon(role), contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}
