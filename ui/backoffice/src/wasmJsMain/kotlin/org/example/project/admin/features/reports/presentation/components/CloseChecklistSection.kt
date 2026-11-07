package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.theme.LocalPosColors

/**
 * The seven readiness checks the server computes over the period. There is no persisted close
 * state — every status is derived on request — so this is a report, not a workflow: the rows are
 * not clickable and nothing here can be ticked off.
 *
 * HIDDEN items are dropped: the server uses that status for a check that does not apply to the
 * shop's configuration.
 */
@Composable
fun CloseChecklistSection(
    items: List<MonthlyCloseDto.ChecklistItemDto>,
    modifier: Modifier = Modifier,
    currencyCode: String = "EUR",
) {
    val visible = items.filter { it.status != MonthlyCloseDto.ChecklistStatus.HIDDEN }
    if (visible.isEmpty()) return
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(MonthlyCloseKeys.sectionOverview),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Column(Modifier.fillMaxWidth()) {
                visible.forEach { item -> ChecklistRow(item, currencyCode) }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    item: MonthlyCloseDto.ChecklistItemDto,
    currencyCode: String,
) {
    val detail = keepAmountWithCurrency(checklistDetailText(item, LocalAppLanguage.current, currencyCode))
    val icon =
        when (item.status) {
            MonthlyCloseDto.ChecklistStatus.PASSED -> Icons.Filled.CheckCircle
            MonthlyCloseDto.ChecklistStatus.WARNING -> Icons.Filled.Warning
            else -> Icons.Filled.Error
        }
    val tint =
        when (item.status) {
            MonthlyCloseDto.ChecklistStatus.PASSED -> MaterialTheme.colorScheme.primary
            MonthlyCloseDto.ChecklistStatus.WARNING -> LocalPosColors.current.posOccupied
            else -> MaterialTheme.colorScheme.error
        }
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(str(checkLabelKey(item.id))) },
        supportingContent = if (detail.isNotBlank()) ({ Text(detail) }) else null,
    )
}

/**
 * Joins every "<amount> €" with a non-breaking space so a wrapping check text never leaves the euro
 * sign alone on the next line. Applied at display time: the text builder (and its tests) keep the
 * formatter's plain space.
 */
private fun keepAmountWithCurrency(text: String): String = text.replace(" €", "\u00A0€")
