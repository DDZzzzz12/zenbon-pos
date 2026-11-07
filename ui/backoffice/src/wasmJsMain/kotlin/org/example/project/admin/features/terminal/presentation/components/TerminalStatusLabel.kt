package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalTseState
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.LocalPosColors

/** A status is always a coloured dot AND a word (never colour alone). */
@Composable
internal fun StatusDotLabel(
    text: String,
    dot: Color,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Aktiv (green) or Inaktiv (grey). */
@Composable
internal fun TerminalActiveStatus(
    terminal: TerminalDto.TerminalSummaryDto,
    modifier: Modifier = Modifier,
) {
    if (terminal.isActive) {
        StatusDotLabel(str(AdminStringKeys.TerminalStatusActive), LocalPosColors.current.posAvailable, modifier)
    } else {
        StatusDotLabel(
            text = str(AdminStringKeys.TerminalStatusInactive),
            dot = MaterialTheme.colorScheme.outline,
            modifier = modifier,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Registriert (green), Nicht registriert (amber, fiscal terminals only) or Nicht fiskalisch (grey). */
@Composable
internal fun TerminalTseStatus(
    terminal: TerminalDto.TerminalSummaryDto,
    modifier: Modifier = Modifier,
) {
    val state = TerminalTseState.of(terminal)
    val text = str(terminalTseValueKey(state))
    when (state) {
        TerminalTseState.REGISTERED -> StatusDotLabel(text, LocalPosColors.current.posAvailable, modifier)
        TerminalTseState.MISSING ->
            StatusDotLabel(text, LocalPosColors.current.posOccupied, modifier, textColor = MaterialTheme.colorScheme.tertiary)
        TerminalTseState.NOT_FISCAL ->
            StatusDotLabel(text, MaterialTheme.colorScheme.outline, modifier, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The word for a TSE state, shared by the table column, the compact row and the panel's TSE row. */
internal fun terminalTseValueKey(state: TerminalTseState): StringKey =
    when (state) {
        TerminalTseState.REGISTERED -> AdminStringKeys.TerminalTseValueRegistered
        TerminalTseState.MISSING -> AdminStringKeys.TerminalTseValueNotRegistered
        TerminalTseState.NOT_FISCAL -> AdminStringKeys.TerminalFiscalNo
    }
