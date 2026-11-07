package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.state.DashboardViewMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionList(
    viewMode: DashboardViewMode,
    bills: List<BillDto.BillSummaryDto>,
    tableCards: List<StatisticsDto.TableCardDto>,
    expandedTableSessions: Map<Long, List<StatisticsDto.TableSessionSummaryDto>>,
    loadingTableSessions: Set<Long>,
    onToggleTableExpand: (Long) -> Unit,
    employeeGroups: List<StatisticsDto.EmployeeGroupDto>,
    isCompact: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onBillClick: (BillDto.BillSummaryDto) -> Unit,
    modifier: Modifier = Modifier,
    scrollToTopRequest: Int = 0,
    header: (@Composable () -> Unit)? = null,
    contentInset: Dp? = null,
) {
    val listState = rememberLazyListState()

    // A reload after a bill change prepends new Belege above the row the list is anchored on; the
    // view model bumps the request in the same update that lands the reloaded first page, so this
    // runs after the new rows are present. The first composition's value is not a request.
    var handledScrollRequest by remember { mutableIntStateOf(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest == handledScrollRequest) return@LaunchedEffect
        handledScrollRequest = scrollToTopRequest
        listState.scrollToItem(0)
    }

    // Derive ONLY from the (snapshot-backed) list state. isLoadingMore/hasMore must NOT be read
    // inside a keyless remember{} — the closure would capture their first-composition values and
    // freeze them, so the trigger would stop reflecting later loads. Gate on the live flags in the
    // LaunchedEffect below instead.
    val reachedBottom =
        remember {
            derivedStateOf {
                val lastVisibleItem =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                totalItems > 0 && lastVisibleItem >= totalItems - 3
            }
        }

    // Keyed on all three live inputs: when a page finishes (isLoadingMore flips back to false) and
    // we're still at the bottom with more to load, this re-runs and pulls the next page.
    LaunchedEffect(reachedBottom.value, isLoadingMore, hasMore, isLoading) {
        if (reachedBottom.value && !isLoadingMore && hasMore && !isLoading) onLoadMore()
    }

    val isEmpty =
        when (viewMode) {
            DashboardViewMode.BILLS -> bills.isEmpty()
            DashboardViewMode.TABLES -> tableCards.isEmpty()
            DashboardViewMode.EMPLOYEES -> employeeGroups.isEmpty()
        }
    val showsState = isLoading || isEmpty

    // Without a header the loading / empty state simply fills the pane.
    if (showsState && header == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator() else EmptyTransactions()
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        header?.let { leading -> item(key = "list_header") { leading() } }
        if (showsState) {
            // Same LazyColumn as the content, so the header keeps its tree position (focus and open
            // dropdowns survive a reload) and scrolls away on a short window.
            item(key = "list_state") {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading) CircularProgressIndicator() else EmptyTransactions()
                }
            }
            return@LazyColumn
        }
        when (viewMode) {
            DashboardViewMode.BILLS -> {
                if (isCompact) {
                    items(bills, key = { it.billId }) { bill ->
                        BillTransactionRow(
                            bill = bill,
                            isCompact = true,
                            onClick = { onBillClick(bill) },
                            showDivider = false,
                            horizontalInset = contentInset,
                        )
                    }
                } else {
                    stickyHeader(key = "bills_header") {
                        BillsHeaderRow(contentInset ?: 16.dp)
                    }
                    items(bills) { bill ->
                        BillTransactionRow(
                            bill = bill,
                            isCompact = false,
                            onClick = { onBillClick(bill) },
                            showDivider = true,
                            horizontalInset = contentInset,
                        )
                    }
                }
            }

            DashboardViewMode.TABLES -> {
                items(tableCards, key = { it.tableId }) { card ->
                    val sessions = expandedTableSessions[card.tableId]
                    val isExpanded = expandedTableSessions.containsKey(card.tableId)
                    val isSessionLoading = loadingTableSessions.contains(card.tableId)
                    TableGroupCard(
                        card = card,
                        sessions = sessions,
                        isExpanded = isExpanded || isSessionLoading,
                        isLoading = isSessionLoading,
                        isCompact = isCompact,
                        onToggleExpand = { onToggleTableExpand(card.tableId) },
                        onBillClick = onBillClick,
                    )
                }
            }

            DashboardViewMode.EMPLOYEES -> {
                items(employeeGroups, key = { it.employeeNumber }) { group ->
                    EmployeeGroupCard(
                        group = group,
                        isCompact = isCompact,
                        onBillClick = onBillClick,
                    )
                }
            }
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTransactions() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(
            text = str(StatisticsDashboardKeys.noTransactions),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = str(StatisticsDashboardKeys.noTransactionsDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BillsHeaderRow(horizontalInset: Dp) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(StatisticsDashboardKeys.logBillNumber),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(BILL_COL_NUMBER_WEIGHT),
            )
            Text(
                text = str(StatisticsDashboardKeys.table),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(BILL_COL_TABLE_WEIGHT),
            )
            Text(
                text = "${str(StatisticsDashboardKeys.logEmployee)} · ${str(StatisticsDashboardKeys.logTime)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(BILL_COL_EMPLOYEE_WEIGHT),
            )
            Text(
                text = str(StatisticsDashboardKeys.logPayment),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(BILL_COL_PAYMENT_WEIGHT),
            )
            Text(
                text = str(StatisticsDashboardKeys.logAmount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(BILL_COL_AMOUNT_WEIGHT),
            )
            Text(
                text = str(StatisticsDashboardKeys.logTips),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(BILL_COL_TIPS_WEIGHT),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
