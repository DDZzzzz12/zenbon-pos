package org.example.project.features.launch.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.strings.keys.TerminalRoleKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosTextButton

/**
 * Placeholder screen for a paired terminal that has no full client flow yet.
 *
 * - ORDER_STATION: shows the order-station placeholder.
 * - Any other unsupported role: shows a generic unsupported-role message.
 */
@Composable
fun TerminalRolePlaceholderScreen(
    terminalId: String,
    role: String,
    onPairDifferentDevice: () -> Unit,
) {
    val roleEnum = TerminalRole.entries.firstOrNull { it.name.equals(role.trim(), ignoreCase = true) }
    val isOrderStation = roleEnum == TerminalRole.ORDER_STATION
    val unsupported = !isOrderStation && roleEnum != TerminalRole.CASHIER
    val titleKey = if (isOrderStation) TerminalRoleKeys.orderStationTitle else TerminalRoleKeys.unsupportedRoleTitle
    val subtitleKey = if (isOrderStation) TerminalRoleKeys.orderStationSubtitle else TerminalRoleKeys.unsupportedRoleMessage
    val subtitleArgs = if (unsupported) listOf(role) else emptyList()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = str(titleKey),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = str(subtitleKey, *subtitleArgs.toTypedArray()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${str(TerminalRoleKeys.terminalIdLabel)}: $terminalId",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${str(TerminalRoleKeys.roleLabel)}: $role",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PosTextButton(
                text = str(TerminalRoleKeys.pairDifferentDevice),
                onClick = onPairDifferentDevice,
            )
        }
    }
}
