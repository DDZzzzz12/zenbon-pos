package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.isPairingOpen
import org.example.project.admin.features.terminal.presentation.isTseMissing
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.theme.LocalPosColors

/**
 * The compact layout's list: one row per terminal, at least 76dp tall (a 48dp target with room for the
 * flags). Left the role tile and `TER-003 · Name`, `Rolle · Standort`, then only the flags that apply
 * (TSE nicht registriert in amber, Kopplung offen in blue, inaktiv: Browser-Stationen aus in red);
 * right the status (dot + word) and the last-seen timestamp. A tap opens the detail page.
 *
 * The page scrolls, so this is a plain column, never a lazy list.
 */
@Composable
fun TerminalsCardList(
    terminals: List<TerminalDto.TerminalSummaryDto>,
    onSelect: (Long) -> Unit,
    isInactiveBrowserStation: (TerminalDto.TerminalSummaryDto) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        terminals.forEach { terminal ->
            CompactTerminalRow(terminal, isInactiveBrowserStation(terminal)) { onSelect(terminal.id) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CompactTerminalRow(
    terminal: TerminalDto.TerminalSummaryDto,
    inactiveBrowser: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalRoleTile(terminal.role, terminal.isActive)
        Column(Modifier.weight(1f)) {
            Text(
                text = terminalTitleText(terminal),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val where = listOfNotNull(roleText(terminal), terminal.location?.takeIf { it.isNotBlank() })
            Text(
                text = where.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (terminal.isTseMissing()) FlagLine(str(AdminStringKeys.TerminalTseNotRegistered), MaterialTheme.colorScheme.tertiary)
            if (terminal.isPairingOpen()) FlagLine(str(AdminStringKeys.TerminalFilterPairingOpen), LocalPosColors.current.posReserved)
            if (inactiveBrowser) FlagLine(str(AdminStringKeys.TerminalBrowserStationsOff), MaterialTheme.colorScheme.error)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TerminalActiveStatus(terminal)
            Text(
                text = terminal.lastSeenAt.formatShortDateTime(LocalAppLanguage.current),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FlagLine(
    text: String,
    color: Color,
) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = color)
}
