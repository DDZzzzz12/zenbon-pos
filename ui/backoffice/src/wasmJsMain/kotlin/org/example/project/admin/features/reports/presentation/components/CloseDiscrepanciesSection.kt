package org.example.project.admin.features.reports.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.LocalPosColors

/**
 * The checklist filtered to what is actually wrong, each row expandable to the server's detail
 * text. When nothing is wrong it says so — the "everything reconciles" state is the answer the
 * owner came for, not an empty list.
 */
@Composable
fun CloseDiscrepanciesSection(
    items: List<MonthlyCloseDto.ChecklistItemDto>,
    modifier: Modifier = Modifier,
    currencyCode: String = "EUR",
) {
    val issues =
        items.filter {
            it.status == MonthlyCloseDto.ChecklistStatus.WARNING ||
                it.status == MonthlyCloseDto.ChecklistStatus.FAILED
        }

    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(MonthlyCloseKeys.sectionDiscrepancies),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (issues.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column {
                        Text(
                            text = str(MonthlyCloseKeys.noDiscrepanciesTitle),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = str(MonthlyCloseKeys.noDiscrepanciesBody),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                issues.forEach { item -> DiscrepancyRow(item, currencyCode) }
            }
        }
    }
}

@Composable
private fun DiscrepancyRow(
    item: MonthlyCloseDto.ChecklistItemDto,
    currencyCode: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val detail = checklistDetailText(item, LocalAppLanguage.current, currencyCode)
    val failed = item.status == MonthlyCloseDto.ChecklistStatus.FAILED

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        status = if (failed) PosCardStatus.Error else PosCardStatus.Warning,
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (failed) Icons.Filled.Error else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else LocalPosColors.current.posOccupied,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = str(checkLabelKey(item.id)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (item.affectedCount > 0) {
                        Text(
                            text = str(MonthlyCloseKeys.affectedCountLabel, item.affectedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded && detail.isNotBlank()) {
                Column {
                    HorizontalDivider()
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
