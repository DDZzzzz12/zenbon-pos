package org.example.project.admin.features.dashboard.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatDashboardDateTime
import org.example.project.admin.core.util.formatDashboardShortDateTime
import org.example.project.admin.features.dashboard.presentation.sections.RecentCashEntriesViewModel
import org.example.project.admin.features.dashboard.presentation.sections.SectionLoadingPlaceholder
import org.example.project.admin.features.dashboard.presentation.sections.SectionUnavailable
import org.example.project.admin.features.dashboard.presentation.sections.latestEntryPerBook
import org.example.project.admin.features.dashboard.presentation.sections.rememberSectionSettled
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.shownBookingText
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param onEntryClick `null` leaves every row unclickable — what a cashier gets, since the cash book
 *   the row would open is MANAGER-only. The summary itself stays readable; only the way in is gone.
 * @param onOpenKassenbuch `null` hides the section's link to the full cash book, for the same reason.
 * @param refreshTick incremented by the screen's refresh button; the card reloads on every change.
 */
@Composable
fun KassenbuchEntriesCard(
    refreshTick: Int,
    onEntryClick: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenKassenbuch: (() -> Unit)? = null,
    viewModel: RecentCashEntriesViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    val title = str(AdminStringKeys.DashboardLatestEntries)

    val settled = rememberSectionSettled(state.isLoading, state.failed)
    if (state.isLoading && !settled) {
        SectionLoadingPlaceholder(modifier)
        return
    }
    if (state.failed) {
        SectionUnavailable(title = title, modifier = modifier)
        return
    }
    if (state.isEmpty) return

    val entries = state.entries
    val language = LocalAppLanguage.current
    val compact = LocalPosLayout.current.isCompact
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.gridGap)) {
        DashboardSectionHeader(
            title = if (compact) str(AdminStringKeys.DashboardKassenbuchTitleCompact) else title,
            linkText = str(if (compact) AdminStringKeys.DashboardShowAll else AdminStringKeys.DashboardToKassenbuch),
            onLinkClick = onOpenKassenbuch,
        )
        val balances = latestEntryPerBook(entries)
        Box(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            if (compact) {
                CompactEntryList(entries, balances, state.bookNames, language, onEntryClick)
            } else {
                EntryTable(entries, balances, state.bookNames, language, onEntryClick)
            }
        }
    }
}

/** ≥ 600 dp: the table, in a bordered box. */
@Composable
private fun EntryTable(
    entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    balances: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    bookNames: Map<Long, String>,
    language: Language,
    onEntryClick: ((Long) -> Unit)?,
) {
    // NEW-06: weights follow the minimum widths; in a narrow pane (the dashboard's half
    // column) the type, then the booking date give way before the table turns into cards.
    // The first column carries the Saldo rows' label, so it is never hidden.
    val columns =
        listOf(
            PosAdaptiveColumn(
                PosTableColumn(
                    str(AdminStringKeys.DashboardKassenbuchCreatedAt),
                    PosTableAlign.Start,
                    weight = 1.36f,
                    minWidth = PosTableMin.DateTime,
                    nowrap = true,
                ),
                PosCardRole.Meta,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(AdminStringKeys.DashboardKassenbuchDescription), PosTableAlign.Start, weight = 2.0f, minWidth = 160.dp),
                PosCardRole.Title,
            ),
            PosAdaptiveColumn(
                // 128dp: "Kassenüberschuss" broke mid-word at 112dp.
                PosTableColumn(str(AdminStringKeys.DashboardKassenbuchType), PosTableAlign.Start, weight = 1.36f, minWidth = 128.dp),
                PosCardRole.Meta,
                hideInTableBelow = 600.dp,
            ),
            PosAdaptiveColumn(
                PosTableColumn(
                    str(AdminStringKeys.DashboardKassenbuchBookingDate),
                    PosTableAlign.End,
                    weight = 1.04f,
                    minWidth = PosTableMin.Date,
                    nowrap = true,
                ),
                PosCardRole.Meta,
                hideInTableBelow = 700.dp,
            ),
            PosAdaptiveColumn(
                PosTableColumn(
                    str(AdminStringKeys.DashboardKassenbuchAmount),
                    PosTableAlign.End,
                    weight = 1.12f,
                    minWidth = PosTableMin.Money,
                    nowrap = true,
                ),
                PosCardRole.Trailing,
            ),
        )
    val rows =
        entries.map { e ->
            PosTableRow(
                cells =
                    listOf(
                        formatDashboardDateTime(e.createdAt),
                        e.belegtext.shownBookingText(),
                        e.entryType.getName(language),
                        e.belegDatum,
                        formatMoneyForDisplay(e.totalAmount, e.currency, forceSign = true),
                    ),
                onClick = onEntryClick?.let { open -> { open(e.headerId) } },
            )
        } +
            balances.map { latest ->
                val bookName = bookNames[latest.cashBookId]
                PosTableRow(
                    cells =
                        listOf(
                            if (bookName == null) {
                                str(AdminStringKeys.DashboardKassenbuchBalance)
                            } else {
                                str(AdminStringKeys.DashboardKassenbuchBalanceOfBook, bookName)
                            },
                            "",
                            "",
                            "",
                            formatMoneyForDisplay(latest.currentBalance, latest.currency),
                        ),
                    emphasized = true,
                )
            }
    PosAdaptiveDataTable(columns, rows, headerContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
}

/**
 * Phone form: description + "21.10. 14:05 · Einlage" left, signed amount right; every row at least
 * 48 dp and clickable when [onEntryClick] is set, and a "Kassenbestand" footer per book.
 */
@Composable
private fun CompactEntryList(
    entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    balances: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    bookNames: Map<Long, String>,
    language: Language,
    onEntryClick: ((Long) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        entries.forEach { e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .then(if (onEntryClick != null) Modifier.clickable { onEntryClick(e.headerId) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.belegtext.shownBookingText(), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = formatDashboardShortDateTime(e.createdAt) + " · " + e.entryType.getName(language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatMoneyForDisplay(e.totalAmount, e.currency, forceSign = true),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        balances.forEach { latest ->
            val bookName = bookNames[latest.cashBookId]
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(AdminStringKeys.DashboardKassenbuchBalanceShort) + if (bookName == null) "" else ": $bookName",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMoneyForDisplay(latest.currentBalance, latest.currency),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
