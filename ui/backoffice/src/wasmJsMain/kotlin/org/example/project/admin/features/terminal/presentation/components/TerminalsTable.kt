package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.BROWSER_CLIENT_KIND
import org.example.project.admin.features.terminal.presentation.TerminalsState
import org.example.project.admin.features.terminal.presentation.isPairingOpen
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.theme.LocalPosColors

// Fixed column widths of the design (TerminalsL / TerminalsM); the Terminal column takes the rest.
private val ICON_WIDTH = 40.dp
private val ROLE_WIDTH = 112.dp
private val TSE_WIDTH_LARGE = 128.dp
private val TSE_WIDTH_MEDIUM = 116.dp
private val LAST_SEEN_WIDTH = 100.dp
private val STATUS_WIDTH_LARGE = 96.dp
private val STATUS_WIDTH_MEDIUM = 100.dp
private val COLUMN_GAP = 12.dp
private val SELECTION_BAR = 4.dp
private val HEADER_HEIGHT = 36.dp

/**
 * The terminal list as a table, in the two widths that have one: [wide] (large layout, beside the
 * panel) shows icon · Terminal + Standort · Rolle · TSE · Zuletzt gesehen · Status; narrower (medium,
 * the detail opens as a drawer) folds the role under the name and the last-seen time under the status.
 * The selected row is the primary container with a 4dp bar on its left.
 *
 * Last seen is rendered as a timestamp and never as "online"/"offline". `lastSeenAt` says when the
 * server last heard from the device, not whether it is reachable now, and the two differ by however
 * long the till's heartbeat interval is.
 *
 * Header sorting stays on the large table (Terminal, Rolle, Zuletzt gesehen); the Name sort is reached
 * through the sort menu beside the result line, which both layouts show ([TerminalSortMenu]).
 */
@Composable
fun TerminalsTable(
    terminals: List<TerminalDto.TerminalSummaryDto>,
    selectedId: Long?,
    sort: TerminalsState.TerminalSort,
    sortAscending: Boolean,
    onSort: (TerminalsState.TerminalSort) -> Unit,
    onSelect: (Long?) -> Unit,
    isInactiveBrowserStation: (TerminalDto.TerminalSummaryDto) -> Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            TableHeader(wide, sort, sortAscending, onSort)
        }
        items(terminals, key = { it.id }) { terminal ->
            val selected = terminal.id == selectedId
            TerminalRow(
                terminal = terminal,
                selected = selected,
                inactiveBrowser = isInactiveBrowserStation(terminal),
                wide = wide,
                onClick = { onSelect(if (selected) null else terminal.id) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TableHeader(
    wide: Boolean,
    sort: TerminalsState.TerminalSort,
    sortAscending: Boolean,
    onSort: (TerminalsState.TerminalSort) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = SELECTION_BAR + 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        Box(Modifier.width(ICON_WIDTH))
        if (wide) {
            HeaderCell(
                AdminStringKeys.TerminalColumnTerminalLocation,
                TerminalsState.TerminalSort.TERMINAL_ID,
                sort,
                sortAscending,
                onSort,
                Modifier.weight(1f),
            )
            HeaderCell(AdminStringKeys.TerminalColumnRole, TerminalsState.TerminalSort.ROLE, sort, sortAscending, onSort, Modifier.width(ROLE_WIDTH))
            HeaderCell(AdminStringKeys.TerminalColumnTse, null, sort, sortAscending, onSort, Modifier.width(TSE_WIDTH_LARGE))
            HeaderCell(
                AdminStringKeys.TerminalColumnLastSeen,
                TerminalsState.TerminalSort.LAST_SEEN,
                sort,
                sortAscending,
                onSort,
                Modifier.width(LAST_SEEN_WIDTH),
            )
            HeaderCell(CommonKeys.status, null, sort, sortAscending, onSort, Modifier.width(STATUS_WIDTH_LARGE))
        } else {
            HeaderCell(
                AdminStringKeys.TerminalColumnTerminalRoleLocation,
                TerminalsState.TerminalSort.TERMINAL_ID,
                sort,
                sortAscending,
                onSort,
                Modifier.weight(1f),
            )
            HeaderCell(AdminStringKeys.TerminalColumnTse, null, sort, sortAscending, onSort, Modifier.width(TSE_WIDTH_MEDIUM))
            HeaderCell(CommonKeys.status, null, sort, sortAscending, onSort, Modifier.width(STATUS_WIDTH_MEDIUM))
        }
    }
}

@Composable
private fun HeaderCell(
    label: StringKey,
    sortKey: TerminalsState.TerminalSort?,
    sort: TerminalsState.TerminalSort,
    sortAscending: Boolean,
    onSort: (TerminalsState.TerminalSort) -> Unit,
    modifier: Modifier,
) {
    val active = sortKey != null && sortKey == sort
    val arrow = if (active) (if (sortAscending) " ↑" else " ↓") else ""
    Text(
        text = str(label) + arrow,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(if (sortKey != null) Modifier.clickable(role = Role.Button) { onSort(sortKey) } else Modifier),
    )
}

@Composable
private fun TerminalRow(
    terminal: TerminalDto.TerminalSummaryDto,
    selected: Boolean,
    inactiveBrowser: Boolean,
    wide: Boolean,
    onClick: () -> Unit,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val barWidth = SELECTION_BAR
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (wide) 56.dp else 64.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .drawBehind { if (selected) drawRect(barColor, size = Size(barWidth.toPx(), size.height)) }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = SELECTION_BAR + 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        TerminalRoleTile(terminal.role, terminal.isActive, size = 36.dp, iconSize = 19.dp)
        TerminalNameCell(terminal, inactiveBrowser, wide, Modifier.weight(1f))
        if (wide) {
            Text(
                text = roleText(terminal),
                style = MaterialTheme.typography.bodySmall,
                color = if (terminal.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(ROLE_WIDTH),
            )
            TerminalTseStatus(terminal, Modifier.width(TSE_WIDTH_LARGE))
            Text(
                text = terminal.lastSeenAt.formatShortDateTime(LocalAppLanguage.current),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(LAST_SEEN_WIDTH),
            )
            TerminalActiveStatus(terminal, Modifier.width(STATUS_WIDTH_LARGE))
        } else {
            TerminalTseStatus(terminal, Modifier.width(TSE_WIDTH_MEDIUM))
            Column(Modifier.width(STATUS_WIDTH_MEDIUM)) {
                TerminalActiveStatus(terminal)
                Text(
                    text = terminal.lastSeenAt.formatShortDateTime(LocalAppLanguage.current),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `TER-003 · Terrasse` (no name: "ohne Name", grey) and under it the second line: location · device
 * (large) or role · location (medium), then the flags: "Kopplung offen" in blue, "inaktiv:
 * Browser-Stationen aus" in red.
 */
@Composable
private fun TerminalNameCell(
    terminal: TerminalDto.TerminalSummaryDto,
    inactiveBrowser: Boolean,
    wide: Boolean,
    modifier: Modifier,
) {
    // Large keeps the role in its own column, so the second line is location · device; medium folds the role in.
    val parts =
        if (wide) {
            listOfNotNull(terminal.location?.takeIf { it.isNotBlank() }, deviceLabelOrNull(terminal))
        } else {
            listOfNotNull(roleText(terminal), terminal.location?.takeIf { it.isNotBlank() })
        }
    Column(modifier) {
        Text(
            text = terminalTitleText(terminal),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = terminalSubtitleText(terminal, inactiveBrowser, parts),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `TER-003` in bold and `· name` in the secondary colour; a deactivated terminal is entirely grey. */
@Composable
internal fun terminalTitleText(terminal: TerminalDto.TerminalSummaryDto): AnnotatedString {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryText = if (terminal.isActive) MaterialTheme.colorScheme.onSurface else secondary
    val name = terminalDisplayName(terminal)
    val unnamed = str(AdminStringKeys.TerminalUnnamed)
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = primaryText)) { append(terminal.terminalId) }
        withStyle(SpanStyle(color = secondary)) { append(" · ${name ?: unnamed}") }
    }
}

/** The grey detail parts joined with " · ", then the coloured flags (open pairing, browser stations off). */
@Composable
internal fun terminalSubtitleText(
    terminal: TerminalDto.TerminalSummaryDto,
    inactiveBrowser: Boolean,
    parts: List<String>,
): AnnotatedString {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val flags = mutableListOf<Pair<String, Color>>()
    if (terminal.isPairingOpen()) flags += str(AdminStringKeys.TerminalFilterPairingOpen) to LocalPosColors.current.posReserved
    if (inactiveBrowser) flags += str(AdminStringKeys.TerminalBrowserStationsOff) to MaterialTheme.colorScheme.error
    return buildAnnotatedString {
        withStyle(SpanStyle(color = secondary)) { append(parts.joinToString(" · ")) }
        flags.forEachIndexed { index, (text, color) ->
            if (index > 0 || parts.isNotEmpty()) withStyle(SpanStyle(color = secondary)) { append(" · ") }
            withStyle(SpanStyle(color = color)) { append(text) }
        }
    }
}

/** Role, plus the Browser tag for browser-paired terminals. */
@Composable
internal fun roleText(terminal: TerminalDto.TerminalSummaryDto): String {
    val role = str(roleKey(terminal.role))
    return if (terminal.clientKind == BROWSER_CLIENT_KIND) "$role · ${str(AdminStringKeys.TerminalClientKindBrowser)}" else role
}

internal fun deviceLabelOrNull(terminal: TerminalDto.TerminalSummaryDto): String? =
    deviceLabel(terminal).takeIf { terminal.terminalBrand?.isNotBlank() == true || terminal.terminalModel?.isNotBlank() == true }
