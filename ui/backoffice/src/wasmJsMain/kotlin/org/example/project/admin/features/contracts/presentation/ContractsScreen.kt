package org.example.project.admin.features.contracts.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.ListToggleFilterBar
import org.example.project.admin.core.presentation.components.ListTogglePanel
import org.example.project.admin.core.presentation.components.ListTogglePanelFilter
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.orDash
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveTable
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.pluralResultKey
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Lower bound for the card switch; the table form also needs `requiredTableWidth(columns)` (weights and minWidths), whichever is larger wins. */
private val CONTRACT_CARD_BELOW = 760.dp

/**
 * Verträge — recurring obligations, grouped by when they next fall due.
 *
 * The one question this screen exists to answer is *what falls due, and when*, so the rows are filed
 * under Überfällig / Diese Woche / Diesen Monat / Später / Inaktiv rather than left in the order the
 * server happened to return. The grouping is client-side because it re-reads rows already fetched
 * rather than narrowing them — nothing is hidden, so no page boundary can misreport the list. See
 * [groupByDueDate].
 *
 * The alert count is a **red dot on the row**, not a column of numbers: the old Hinweise column was
 * empty for almost every contract, and a column that is nearly always blank stops being read.
 *
 * The *inaktive einblenden* toggle (a quick chip, in the filter panel on a phone) drives the
 * ViewModel's server-side flag; the server owns that predicate.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ContractsScreen(onOpen: (Long?) -> Unit) {
    val viewModel: ContractsViewModel = koinViewModel()
    val state = viewModel.state
    val mayWrite = ContractsAccess.of(LocalAccessPolicy.current).mayWrite

    val today =
        remember {
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        }

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout,
    // so init would show the previous session's contracts.
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.ContractsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    val sections = remember(state.items, today) { groupByDueDate(state.items, today) }

    // Above the scaffold, so the panel and its draft survive a resize across the phone width.
    val filterPanel = rememberPosFilterPanelState(ListTogglePanelFilter(state.includeInactive), FilterPanelKeys.CONTRACTS)
    val toggleLabel = str(AdminStringKeys.ContractsShowInactive)
    ListTogglePanel(
        toggleLabel = toggleLabel,
        applied = state.includeInactive,
        onToggle = viewModel::setIncludeInactive,
        filterPanel = filterPanel,
    )

    AdminListScaffold(
        title = str(AdminStringKeys.ContractsTitle),
        actions = {
            if (mayWrite) PosButton(text = str(AdminStringKeys.ContractsNew), onClick = { onOpen(null) })
        },
        toolbar = {
            ListToggleFilterBar(
                search =
                    PosFilterSearch(
                        query = state.search,
                        onQueryChange = viewModel::updateSearch,
                        placeholder = str(AdminStringKeys.ContractsSearchPlaceholder),
                    ),
                resultText = str(pluralResultKey(state.total, AdminStringKeys.ContractsCountOne, AdminStringKeys.ContractsCount), state.total),
                toggleLabel = toggleLabel,
                applied = state.includeInactive,
                onToggle = viewModel::setIncludeInactive,
                filterPanel = filterPanel,
            )
        },
        isLoading = state.isLoading,
        isEmpty = state.items.isEmpty() && !state.isLoading,
        empty = {
            AdminListEmptyState(
                message =
                    if (state.hasActiveFilter) {
                        str(AdminStringKeys.ContractsEmptyFiltered)
                    } else {
                        str(AdminStringKeys.ContractsEmpty)
                    },
                icon = Icons.Default.EventRepeat,
                actionText =
                    when {
                        state.hasActiveFilter -> str(AdminStringKeys.CommonResetFilters)
                        mayWrite -> str(AdminStringKeys.ContractsNew)
                        else -> null
                    },
                onAction =
                    when {
                        state.hasActiveFilter -> viewModel::resetFilters
                        mayWrite -> ({ onOpen(null) })
                        else -> null
                    },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.paneGap)) {
            sections.forEach { section ->
                DueSection(section = section, today = today, onOpen = onOpen)
            }
        }
    }
}

/**
 * One due-date group: its header, then its rows.
 *
 * The overdue group's header carries the error colour, which is what the old red summary sentence
 * above the table was for — except it now sits on the rows it is about.
 */
@Composable
private fun DueSection(
    section: ContractDueSection,
    today: LocalDate,
    onOpen: (Long?) -> Unit,
) {
    val overdue = section.group == ContractDueGroup.OVERDUE
    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = str(section.group.labelKey),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = section.contracts.size.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PosAdaptiveTable(
            columns = contractColumns(),
            rows = section.contracts.map { contractRow(it, today, onOpen) },
            cardBelow = CONTRACT_CARD_BELOW,
        )
    }
}

@Composable
private fun contractColumns(): List<PosAdaptiveColumn> =
    listOf(
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.ContractsColumnName), weight = 2.4f), PosCardRole.Title),
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.ContractsColumnSupplier), weight = 2.4f)),
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.ContractsColumnInterval), weight = 2f)),
        PosAdaptiveColumn(
            PosTableColumn(
                title = str(AdminStringKeys.ContractsColumnExpected),
                align = PosTableAlign.End,
                weight = 2f,
                minWidth = PosTableMin.Money,
                nowrap = true,
            ),
            PosCardRole.Trailing,
        ),
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.ContractsColumnNextDue), weight = 2f, minWidth = PosTableMin.Date, nowrap = true)),
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.ContractsColumnLastPayment), weight = 2f, minWidth = PosTableMin.Date, nowrap = true)),
    )

@Composable
private fun contractRow(
    contract: ContractDto.ResponseDto,
    today: LocalDate,
    onOpen: (Long?) -> Unit,
): PosContentRow =
    PosContentRow(
        cells =
            listOf(
                { ContractNameCell(contract) },
                {
                    Text(
                        text = contract.supplierName.orDash(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                { Text(str(intervalKey(contract.interval)), style = MaterialTheme.typography.bodyMedium) },
                { ExpectedCell(contract.expectedAmount, contract.currency) },
                { DueDateCell(contract.nextDueDate, today) },
                {
                    Text(
                        text = contract.lastPaymentDate.asDisplayDay(LocalAppLanguage.current).orDash(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ),
        onClick = { onOpen(contract.id) },
    )

/**
 * The name, with the alert count beside it when there is one.
 *
 * The old *Hinweise* column showed an empty cell for every healthy contract precisely so a column of
 * noughts would not train the eye to skip it — but a column that is nearly always blank gets skipped
 * anyway. Attached to the name, an alert is where the reader is already looking.
 */
@Composable
private fun ContractNameCell(contract: ContractDto.ResponseDto) {
    Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text = contract.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        if (showsAlertBadge(contract.alertCount)) {
            PosStatusChip(
                text = contract.alertCount.toString(),
                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                textColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** A due date in the past carries the error colour, so lateness is visible inside its own group too. */
@Composable
private fun DueDateCell(
    nextDueDate: String?,
    today: LocalDate,
) {
    val day = nextDueDate.asDay()
    val late = day.isNotEmpty() && day < today.toString()
    Text(
        text = nextDueDate.asDisplayDay(LocalAppLanguage.current).orDash(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ExpectedCell(
    amount: String?,
    currency: String,
) {
    if (amount.isNullOrBlank()) return
    PosMoneyText(
        amount = amount,
        currencyCode = currency,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Whether a contract shows its alert badge.
 *
 * A contract with nothing wrong shows **nothing** — not a zero. The old Hinweise column made the
 * same promise by rendering an empty cell; a column of noughts reads as noise and the eye stops
 * checking it, which is the one thing the alert count exists to prevent. Separate and pure so the
 * rule stays asserted without a composition.
 */
internal fun showsAlertBadge(alertCount: Int): Boolean = alertCount > 0

/**
 * `2026-03-01T00:00` &rarr; `2026-03-01`, for comparing against today (ISO sorts as it reads); a null
 * date is blank. Display goes through `asDisplayDay` instead.
 */
internal fun String?.asDay(): String = this?.substringBefore('T').orEmpty()

internal fun intervalKey(interval: String): StringKey =
    when (interval) {
        "ONE_TIME" -> AdminStringKeys.ContractIntervalOneTime
        "WEEKLY" -> AdminStringKeys.ContractIntervalWeekly
        "QUARTERLY" -> AdminStringKeys.ContractIntervalQuarterly
        "SEMIANNUAL" -> AdminStringKeys.ContractIntervalSemiannual
        "ANNUAL" -> AdminStringKeys.ContractIntervalAnnual
        else -> AdminStringKeys.ContractIntervalMonthly
    }
