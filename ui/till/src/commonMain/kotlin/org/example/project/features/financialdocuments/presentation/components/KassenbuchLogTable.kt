package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.navigation.LoadingState
import org.example.project.core.presentation.strings.keys.FinancialDocumentsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.shownBookingText
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.PosSpacing

@Composable
fun KassenbuchLogTable(
    entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    language: Language,
    isLoading: Boolean,
    searchTerm: String,
    onSearchTermChanged: (String) -> Unit,
    onEntryClick: (KassenbuchDto.KassenbuchGroupedSummaryDto) -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
) {
    // Filter entries by search term
    val filteredEntries =
        remember(entries, searchTerm) {
            if (searchTerm.isBlank()) {
                entries
            } else {
                val term = searchTerm.lowercase()
                entries.filter { entry ->
                    (
                        entry.belegtext.lowercase().contains(term) ||
                            entry.belegNr.lowercase().contains(term) ||
                            formatMoneyForDisplay(entry.totalAmount, "").contains(term)
                    )
                }
            }
        }

    Column(modifier = modifier) {
        // Table header
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isCompact) 8.dp else 16.dp, vertical = PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isCompact) "" else str(FinancialDocumentsKeys.logType),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(if (isCompact) 36.dp else 100.dp),
                )
                Text(
                    text = str(FinancialDocumentsKeys.logDescription),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!isCompact) {
                    Text(
                        text = str(FinancialDocumentsKeys.logTime),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.End,
                    )
                }
                Text(
                    text = str(FinancialDocumentsKeys.logAmount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(if (isCompact) 90.dp else 120.dp),
                    textAlign = TextAlign.End,
                )
            }
        }

        HorizontalDivider()

        // Table content
        when {
            isLoading -> {
                LoadingState()
            }

            filteredEntries.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = str(FinancialDocumentsKeys.noKassenbuchEntries),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(
                        items = filteredEntries,
                        // Keyed by the header's DB id, never by belegNr: every cash book numbers
                        // its Belege from YYYY-MM-0001, so the fiscal Ladenkasse and a Tresor
                        // share "2026-09-0001" and a belegNr key crashes the LazyColumn.
                        key = { it.headerId },
                    ) { entry ->
                        LogTableRow(
                            entry = entry,
                            language = language,
                            isCompact = isCompact,
                            onClick = { onEntryClick(entry) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTableRow(
    entry: KassenbuchDto.KassenbuchGroupedSummaryDto,
    language: Language,
    isCompact: Boolean,
    onClick: () -> Unit,
) {
    val isIncoming = entry.entryType.isIncoming

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = if (isCompact) 8.dp else 16.dp, vertical = PosSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Type column: compact = icon only, expanded = icon + label
        if (isCompact) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color =
                    if (isIncoming) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                modifier = Modifier.width(36.dp),
            ) {
                Icon(
                    imageVector = if (isIncoming) Icons.Default.SouthWest else Icons.Default.NorthEast,
                    contentDescription = null,
                    tint =
                        if (isIncoming) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    modifier = Modifier.size(16.dp).padding(2.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.width(100.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color =
                        if (isIncoming) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = PosSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = if (isIncoming) Icons.Default.SouthWest else Icons.Default.NorthEast,
                            contentDescription = null,
                            tint =
                                if (isIncoming) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text =
                                if (isIncoming) {
                                    str(FinancialDocumentsKeys.income)
                                } else {
                                    str(FinancialDocumentsKeys.expense)
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isIncoming) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                        )
                    }
                }
            }
        }

        // Description column
        PosAutoSizeText(
            text = entry.belegtext.shownBookingText(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )

        if (!isCompact) {
            Text(
                text = entry.belegDatum,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
                textAlign = TextAlign.End,
            )
        }

        // Amount column
        Text(
            text = formatMoneyForDisplay(entry.totalAmount, "EUR", forceSign = true),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color =
                if (isIncoming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            modifier = Modifier.width(if (isCompact) 90.dp else 120.dp),
            textAlign = TextAlign.End,
        )
    }
}
