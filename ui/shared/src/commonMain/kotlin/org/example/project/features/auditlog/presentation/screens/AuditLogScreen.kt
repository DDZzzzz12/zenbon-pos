/**
 * Audit Log screen composable.
 *
 * Displays paginated entry logs and transaction audit logs in a two-tab layout
 * with collapsible filter panels and pagination controls.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auditlog.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.AuditLogKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatInstantShortDateTime
import org.example.project.core.utils.isBlankOrIsoDate
import org.example.project.features.auditlog.presentation.auditRowHeadline
import org.example.project.features.auditlog.presentation.auditToneColor
import org.example.project.features.auditlog.presentation.entityTypeLabel
import org.example.project.features.auditlog.presentation.entryOperationLabel
import org.example.project.features.auditlog.presentation.entryOperationTone
import org.example.project.features.auditlog.presentation.state.AuditLogTab
import org.example.project.features.auditlog.presentation.state.AuditLogUiState
import org.example.project.features.auditlog.presentation.state.EntryLogFilterState
import org.example.project.features.auditlog.presentation.state.TransactionAuditFilterState
import org.example.project.features.auditlog.presentation.transactionOperationLabel
import org.example.project.features.auditlog.presentation.viewmodel.AuditLogViewModel
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSearchBar
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.layout.PaneBreakpoints
import org.koin.compose.viewmodel.koinViewModel

/**
 * Audit Log content without Scaffold wrapper.
 *
 * Embeddable content composable for use inside other screens (e.g., Settings tab).
 * Contains tab row, filter panels, and paginated log lists.
 *
 * @param modifier Modifier for customization
 * @param language Current language for localization
 * @param viewModel Audit log ViewModel (injected via Koin)
 */
@Composable
fun AuditLogContent(
    modifier: Modifier = Modifier,
    language: Language,
    viewModel: AuditLogViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    // Handle errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { err ->
            // The TYPED overload: the string one drops the UiError, so the operator would get a
            // generic popup with no traceId, no server detail and no ClientErrorReporter record.
            overlay.showError(
                error = err,
                title = getString(AuditLogKeys.screenTitle, language),
                message = getString(err.messageKey, language, *err.messageArgs.toTypedArray()),
                displayMode = DisplayMode.DIALOG,
            )
            viewModel.clearError()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAuditPhone provides (maxWidth < PaneBreakpoints.PHONE_MAX || PaneBreakpoints.isShortWindow(maxHeight))) {
            AuditLogBody(uiState = uiState, language = language, viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditLogBody(
    uiState: AuditLogUiState,
    language: Language,
    viewModel: AuditLogViewModel,
) {
    val phone = LocalAuditPhone.current
    val filterPanel: @Composable () -> Unit = {
        when (uiState.selectedTab) {
            AuditLogTab.ENTRY_LOG ->
                EntryLogFilterPanel(
                    filter = uiState.entryLogFilter,
                    language = language,
                    onApply = { viewModel.updateEntryLogFilter(it) },
                    onClear = { viewModel.clearEntryLogFilter() },
                )
            AuditLogTab.TRANSACTION_AUDIT ->
                TransactionAuditFilterPanel(
                    filter = uiState.transactionAuditFilter,
                    language = language,
                    onApply = { viewModel.updateTransactionAuditFilter(it) },
                    onClear = { viewModel.clearTransactionAuditFilter() },
                )
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Tab row with filter toggle. The tab row's own divider would stop at the filter button
        // (UI-FG-15), so it is drawn once under the whole row instead.
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryTabRow(
                selectedTabIndex = uiState.selectedTab.ordinal,
                modifier = Modifier.weight(1f),
                divider = {},
            ) {
                Tab(
                    selected = uiState.selectedTab == AuditLogTab.ENTRY_LOG,
                    onClick = { viewModel.onTabChanged(AuditLogTab.ENTRY_LOG) },
                    text = { Text(str(AuditLogKeys.entryLogTab)) },
                )
                Tab(
                    selected = uiState.selectedTab == AuditLogTab.TRANSACTION_AUDIT,
                    onClick = { viewModel.onTabChanged(AuditLogTab.TRANSACTION_AUDIT) },
                    text = { Text(str(AuditLogKeys.transactionAuditTab)) },
                )
            }
            // A labelled button, not a bare icon (UI-FG-15); primary while the panel is open.
            PosTextButton(
                text = str(AuditLogKeys.filterTitle),
                icon = Icons.Default.FilterList,
                onClick = { viewModel.toggleFilterPanel() },
                height = auditIconSize(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (uiState.isFilterPanelVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    ),
            )
        }
        HorizontalDivider()

        // Always-visible global search
        when (uiState.selectedTab) {
            AuditLogTab.ENTRY_LOG ->
                PosSearchBar(
                    query = uiState.entryLogFilter.search ?: "",
                    onQueryChange = {
                        viewModel.updateEntryLogFilter(
                            uiState.entryLogFilter.copy(search = it.ifBlank { null }),
                        )
                    },
                    placeholder = str(AuditLogKeys.globalSearch),
                    // Same 16dp edges as the cards below (contentPadding 16dp), not 12dp (UI-FG-15).
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            AuditLogTab.TRANSACTION_AUDIT ->
                PosSearchBar(
                    query = uiState.transactionAuditFilter.search ?: "",
                    onQueryChange = {
                        viewModel.updateTransactionAuditFilter(
                            uiState.transactionAuditFilter.copy(search = it.ifBlank { null }),
                        )
                    },
                    placeholder = str(AuditLogKeys.globalSearch),
                    // Same 16dp edges as the cards below (contentPadding 16dp), not 12dp (UI-FG-15).
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
        }

        // Filter panel. On phone it is the leading item of the scrolling list instead (a fixed block
        // would eat a short window), see `phoneFilterPanel` below.
        if (!phone) {
            AnimatedVisibility(
                visible = uiState.isFilterPanelVisible,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                filterPanel()
            }
        }
        val phoneFilterPanel: (@Composable () -> Unit)? =
            if (phone && uiState.isFilterPanelVisible) filterPanel else null

        // Content
        when (uiState.selectedTab) {
            AuditLogTab.ENTRY_LOG ->
                EntryLogContent(
                    uiState = uiState,
                    language = language,
                    onPreviousPage = { viewModel.loadPreviousPage() },
                    onNextPage = { viewModel.loadNextPage() },
                    onPageSizeChange = { viewModel.setEntryLogPageSize(it) },
                    header = phoneFilterPanel,
                )
            AuditLogTab.TRANSACTION_AUDIT ->
                TransactionAuditContent(
                    uiState = uiState,
                    language = language,
                    onPreviousPage = { viewModel.loadPreviousPage() },
                    onNextPage = { viewModel.loadNextPage() },
                    onPageSizeChange = { viewModel.setTransactionAuditPageSize(it) },
                    header = phoneFilterPanel,
                )
        }
    }
}

/**
 * Loading / empty body of an audit list. With a [header] it is one LazyColumn whose first item is the
 * header, so the header keeps its tree position (focus, open dropdowns) and scrolls on short windows;
 * without one it simply fills the pane.
 */
@Composable
private fun ColumnScope.AuditStateBody(
    header: (@Composable () -> Unit)?,
    state: @Composable () -> Unit,
) {
    if (header == null) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { state() }
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(16.dp),
    ) {
        item(key = "filter-panel") { header() }
        item(key = "list-state") {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { state() }
        }
    }
}

@Composable
private fun AuditEmptyState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = str(AuditLogKeys.emptyState),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = str(AuditLogKeys.emptyFilterHint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// ─── Entry Log Content ───

@Composable
private fun EntryLogContent(
    uiState: AuditLogUiState,
    language: Language,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSizeChange: (Int) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.isEntryLogLoading && uiState.entryLogs.isEmpty()) {
            AuditStateBody(header) { CircularProgressIndicator() }
        } else if (uiState.entryLogs.isEmpty()) {
            AuditStateBody(header) { AuditEmptyState() }
        } else {
            // The phone filter panel is inserted ahead of keyed rows; a LazyColumn keeps the first
            // visible row anchored, so without this the panel opens off-screen above the list.
            val listState = rememberLazyListState()
            LaunchedEffect(header != null) { if (header != null) listState.scrollToItem(0) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(16.dp),
            ) {
                header?.let { leading -> item(key = "filter-panel") { leading() } }
                items(uiState.entryLogs, key = { it.id }) { log ->
                    EntryLogCard(log = log, language = language)
                }
            }

            // Pagination controls
            uiState.entryLogPagination?.let { pagination ->
                AuditLogPaginationControls(
                    pagination = pagination,
                    isLoading = uiState.isEntryLogLoading,
                    pageSize = uiState.entryLogPageSize,
                    onPageSizeChange = onPageSizeChange,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    language = language,
                )
            }
        }
    }
}

/** Localized label with the stored raw value as a quiet second line (hidden when both read the same). */
@Composable
private fun AuditLabelWithRaw(
    label: String,
    raw: String,
    color: Color,
    bold: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
        if (raw != label) {
            // NEW-19: one line; a long stored key wrapped mid-token ("UPDATE_BROWSER_STATION / S").
            Text(
                text = raw,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EntryLogCard(
    log: EntryLogDto.EntryLogResponseDto,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            AuditSplitRow(
                verticalAlignment = Alignment.CenterVertically,
                start = {
                    AuditLabelWithRaw(
                        label = entryOperationLabel(log.operation, log.message),
                        raw = log.operation,
                        color = auditToneColor(entryOperationTone(log.operation, log.message)),
                    )
                },
                end = {
                    AuditLabelWithRaw(
                        label = entityTypeLabel(log.type),
                        raw = log.type,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        bold = false,
                        horizontalAlignment = Alignment.End,
                    )
                },
            )

            PosAutoSizeText(
                text = log.entityName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            val message = log.message
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            AuditSplitRow(
                start = {
                    Text(
                        text = "${str(AuditLogKeys.employee)}: ${log.userId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                end = {
                    Text(
                        text = formatTimestamp(log.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            if (log.entityId.isNotBlank()) {
                Text(
                    text = "${str(AuditLogKeys.entityId)}: ${log.entityId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Transaction Audit Content ───

@Composable
private fun TransactionAuditContent(
    uiState: AuditLogUiState,
    language: Language,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSizeChange: (Int) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.isTransactionAuditLoading && uiState.transactionAuditLogs.isEmpty()) {
            AuditStateBody(header) { CircularProgressIndicator() }
        } else if (uiState.transactionAuditLogs.isEmpty()) {
            AuditStateBody(header) { AuditEmptyState() }
        } else {
            // The phone filter panel is inserted ahead of keyed rows; a LazyColumn keeps the first
            // visible row anchored, so without this the panel opens off-screen above the list.
            val listState = rememberLazyListState()
            LaunchedEffect(header != null) { if (header != null) listState.scrollToItem(0) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(16.dp),
            ) {
                header?.let { leading -> item(key = "filter-panel") { leading() } }
                items(uiState.transactionAuditLogs, key = { it.id }) { log ->
                    TransactionAuditCard(log = log, language = language)
                }
            }

            // Pagination controls
            uiState.transactionAuditPagination?.let { pagination ->
                AuditLogPaginationControls(
                    pagination = pagination,
                    isLoading = uiState.isTransactionAuditLoading,
                    pageSize = uiState.transactionAuditPageSize,
                    onPageSizeChange = onPageSizeChange,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    language = language,
                )
            }
        }
    }
}

@Composable
private fun TransactionAuditCard(
    log: TransactionAuditDto.TransactionAuditLogDto,
    language: Language,
) {
    var expanded by remember { mutableStateOf(false) }

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        variant = PosCardVariant.Plain,
    ) {
        body {
            // Header: operation + entity type
            AuditSplitRow(
                verticalAlignment = Alignment.CenterVertically,
                start = {
                    AuditLabelWithRaw(
                        label = transactionOperationLabel(log.operation),
                        raw = log.operation,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                end = {
                    AuditLabelWithRaw(
                        label = entityTypeLabel(log.entityType),
                        raw = log.entityType,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        bold = false,
                        horizontalAlignment = Alignment.End,
                    )
                },
            )

            // A failed row's entityName is a constant — see auditRowHeadline.
            val headline =
                auditRowHeadline(
                    entityId = log.entityId,
                    entityName = log.entityName,
                    reason = log.reason,
                )
            headline.text?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (headline.isFailure) MaterialTheme.colorScheme.error else Color.Unspecified,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Employee + timestamp
            AuditSplitRow(
                start = {
                    Text(
                        text = "${str(AuditLogKeys.employee)}: ${log.employeeNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                end = {
                    Text(
                        text = formatTimestamp(log.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            // Financial impact
            log.amountImpact?.let { amount ->
                val parsed = Decimal.parseOrNull(amount)
                Text(
                    text = "${str(AuditLogKeys.amount)}: ${formatMoneyForDisplay(amount, log.currencyCode, forceSign = true)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (parsed == null || !parsed.isNegative) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            // Expanded details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AuditDetailRow(str(AuditLogKeys.entityId), log.entityId)
                    AuditDetailRow(str(AuditLogKeys.transactionId), log.transactionId)

                    log.changesSummary?.let { AuditDetailRow(str(AuditLogKeys.changes), it) }
                    log.reason?.let {
                        AuditDetailRow(
                            label = str(AuditLogKeys.reason),
                            value = it,
                            valueColor = MaterialTheme.colorScheme.error,
                        )
                    }
                    log.authorizingManagerName?.let { AuditDetailRow(str(AuditLogKeys.authorizingManager), it) }
                    log.deviceId?.let { AuditDetailRow(str(AuditLogKeys.device), it) }
                    log.fiscalRecordId?.let { AuditDetailRow(str(AuditLogKeys.fiscalRecord), it) }

                    log.beforeState?.let {
                        Text(
                            text = str(AuditLogKeys.beforeState) + ":",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    log.afterState?.let {
                        Text(
                            text = str(AuditLogKeys.afterState) + ":",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    log.metadata?.let {
                        Text(
                            text = str(AuditLogKeys.metadata) + ":",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditDetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val labelText: @Composable () -> Unit = {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
    if (LocalAuditPhone.current) {
        // A phone has no room for a label beside a UUID or a reason: label above, value full width,
        // and no two-line cut-off that would hide the end of an id.
        Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            labelText()
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labelText()
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Filter Panels ───

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryLogFilterPanel(
    filter: EntryLogFilterState,
    language: Language,
    onApply: (EntryLogFilterState) -> Unit,
    onClear: () -> Unit,
) {
    val allTypesText = str(AuditLogKeys.allTypes)
    val allOperationsText = str(AuditLogKeys.allOperations)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChipDropdown(
                label = filter.type?.let { entityTypeLabel(it) } ?: allTypesText,
                selected = filter.type != null,
                options = listOf(null) + ChangeEntityType.entries.map { it.name },
                displayText = { it?.let { type -> entityTypeLabel(type) } ?: allTypesText },
                currentValue = filter.type,
                onSelected = { onApply(filter.copy(type = it)) },
            )

            ChipDropdown(
                label = filter.operation?.let { entryOperationLabel(it, null) } ?: allOperationsText,
                selected = filter.operation != null,
                options = listOf(null) + LogOperation.entries.map { it.name },
                displayText = { it?.let { op -> entryOperationLabel(op, null) } ?: allOperationsText },
                currentValue = filter.operation,
                onSelected = { onApply(filter.copy(operation = it)) },
            )

            PosTextField(
                value = filter.entityName ?: "",
                onValueChange = { onApply(filter.copy(entityName = it.ifBlank { null })) },
                label = str(AuditLogKeys.entityName),
                leadingIcon = Icons.Default.Search,
                imeAction = ImeAction.Done,
                modifier = Modifier.width(200.dp),
            )

            PosTextField(
                value = filter.entityId ?: "",
                onValueChange = { onApply(filter.copy(entityId = it.ifBlank { null })) },
                label = str(AuditLogKeys.entityId),
                imeAction = ImeAction.Done,
                modifier = Modifier.width(160.dp),
            )

            PosTextField(
                value = filter.employeeNumber ?: "",
                onValueChange = { onApply(filter.copy(employeeNumber = it.ifBlank { null })) },
                label = str(AuditLogKeys.employeeNumber),
                imeAction = ImeAction.Done,
                modifier = Modifier.width(160.dp),
            )
        }

        DateRangeFilterRow(
            fromDate = filter.fromDate,
            toDate = filter.toDate,
            language = language,
            onRangeChange = { from, to -> onApply(filter.copy(fromDate = from, toDate = to)) },
        )

        if (filter != EntryLogFilterState()) {
            PosTextButton(text = str(AuditLogKeys.clearAll), onClick = onClear)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransactionAuditFilterPanel(
    filter: TransactionAuditFilterState,
    language: Language,
    onApply: (TransactionAuditFilterState) -> Unit,
    onClear: () -> Unit,
) {
    val allTypesText = str(AuditLogKeys.allTypes)
    val allOperationsText = str(AuditLogKeys.allOperations)
    val allAuthText = str(AuditLogKeys.allAuthTypes)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ChipDropdown(
                label = filter.entityType?.let { entityTypeLabel(it) } ?: allTypesText,
                selected = filter.entityType != null,
                options = listOf(null) + TransactionAuditDto.EntityType.entries.map { it.name },
                displayText = { it?.let { type -> entityTypeLabel(type) } ?: allTypesText },
                currentValue = filter.entityType,
                onSelected = { onApply(filter.copy(entityType = it)) },
            )

            ChipDropdown(
                label = filter.operation?.let { transactionOperationLabel(it) } ?: allOperationsText,
                selected = filter.operation != null,
                options = listOf(null) + TransactionAuditDto.TransactionOperation.entries.map { it.name },
                displayText = { it?.let { op -> transactionOperationLabel(op) } ?: allOperationsText },
                currentValue = filter.operation,
                onSelected = { onApply(filter.copy(operation = it)) },
            )

            ChipDropdown(
                label = filter.authorizationType?.replace("_", " ") ?: allAuthText,
                selected = filter.authorizationType != null,
                options = listOf(null) + TransactionAuditDto.AuthorizationType.entries.map { it.name },
                displayText = { it?.replace("_", " ") ?: allAuthText },
                currentValue = filter.authorizationType,
                onSelected = { onApply(filter.copy(authorizationType = it)) },
            )

            PosTextField(
                value = filter.entityName ?: "",
                onValueChange = { onApply(filter.copy(entityName = it.ifBlank { null })) },
                label = str(AuditLogKeys.entityName),
                leadingIcon = Icons.Default.Search,
                imeAction = ImeAction.Done,
                modifier = Modifier.width(200.dp),
            )

            PosTextField(
                value = filter.employeeNumber ?: "",
                onValueChange = { onApply(filter.copy(employeeNumber = it.ifBlank { null })) },
                label = str(AuditLogKeys.employeeNumber),
                imeAction = ImeAction.Done,
                modifier = Modifier.width(160.dp),
            )

            AmountFilterField(
                value = filter.amountMin,
                label = str(AuditLogKeys.amountMin),
                onValueChange = { onApply(filter.copy(amountMin = it)) },
                modifier = Modifier.width(130.dp),
            )

            AmountFilterField(
                value = filter.amountMax,
                label = str(AuditLogKeys.amountMax),
                onValueChange = { onApply(filter.copy(amountMax = it)) },
                modifier = Modifier.width(130.dp),
            )
        }

        DateRangeFilterRow(
            fromDate = filter.fromDate,
            toDate = filter.toDate,
            language = language,
            onRangeChange = { from, to -> onApply(filter.copy(fromDate = from, toDate = to)) },
        )

        if (filter != TransactionAuditFilterState()) {
            PosTextButton(text = str(AuditLogKeys.clearAll), onClick = onClear)
        }
    }
}

// ─── Reusable Components ───

/** True when the audit log has less than [PaneBreakpoints.PHONE_MAX] of width. Set once in [AuditLogContent]. */
private val LocalAuditPhone = compositionLocalOf { false }

/** 48dp touch target on a phone, the unchanged 40dp [PosIconButton] default otherwise. */
@Composable
private fun auditIconSize(): Dp = if (LocalAuditPhone.current) 48.dp else 40.dp

/**
 * A header line with one text at each end. Each side may shrink and wrap, but neither takes more than
 * half the width. When both fit in their half the result looks like the old `SpaceBetween` Row.
 */
@Composable
private fun AuditSplitRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = verticalAlignment,
    ) {
        Box(Modifier.weight(1f, fill = false)) { start() }
        Box(Modifier.weight(1f, fill = false).padding(start = 8.dp)) { end() }
    }
}

/**
 * Date-range filter row: quick preset buttons (Today / This week / This month) plus two
 * date pickers. Presets fill both dates via [DateRange.toDatePair]; the pickers override
 * either bound; the ✕ clears the range. Emits (fromDate, toDate) — nulls clear the range.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeFilterRow(
    fromDate: String?,
    toDate: String?,
    language: Language,
    onRangeChange: (String?, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosTextButton(
                text = str(AuditLogKeys.presetToday),
                onClick = {
                    val (from, to) = DateRange.TODAY.toDatePair()
                    onRangeChange(from, to)
                },
            )
            PosTextButton(
                text = str(AuditLogKeys.presetThisWeek),
                onClick = {
                    val (from, to) = DateRange.THIS_WEEK.toDatePair()
                    onRangeChange(from, to)
                },
            )
            PosTextButton(
                text = str(AuditLogKeys.presetThisMonth),
                onClick = {
                    val (from, to) = DateRange.THIS_MONTH.toDatePair()
                    onRangeChange(from, to)
                },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // The one date field: typed or picked, shown in the UI language. Only a date (or blank)
            // reaches the filter; half-typed text stays in the field, flagged once it loses focus.
            PosDateField(
                value = fromDate ?: "",
                onValueChange = { value -> if (isBlankOrIsoDate(value)) onRangeChange(value.ifBlank { null }, toDate) },
                label = str(AuditLogKeys.fromDate),
                modifier = Modifier.width(180.dp),
            )
            PosDateField(
                value = toDate ?: "",
                onValueChange = { value -> if (isBlankOrIsoDate(value)) onRangeChange(fromDate, value.ifBlank { null }) },
                label = str(AuditLogKeys.toDate),
                modifier = Modifier.width(180.dp),
            )
            if (fromDate != null || toDate != null) {
                PosIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = str(AuditLogKeys.clearFilter),
                    onClick = { onRangeChange(null, null) },
                    tint = MaterialTheme.colorScheme.error,
                    size = auditIconSize(),
                )
            }
        }
    }
}

/**
 * Numeric filter field backed by local text state, so partial input (e.g. "10.") is not
 * reformatted while typing. The parsed [Double] (or null) is propagated on every change; the
 * local text re-syncs only when the external [value] diverges from what is typed (e.g. on Clear).
 */
@Composable
private fun AmountFilterField(
    value: String?,
    label: String,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value ?: "") }
    LaunchedEffect(value) {
        if (value != text.takeIf { it.isNotBlank() }) {
            text = value ?: ""
        }
    }
    PosTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it.takeIf { s -> s.isNotBlank() })
        },
        label = label,
        imeAction = ImeAction.Done,
        modifier = modifier,
    )
}

@Composable
private fun ChipDropdown(
    label: String,
    selected: Boolean,
    options: List<String?>,
    displayText: @Composable (String?) -> String,
    currentValue: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        FilterChip(
            selected = selected,
            onClick = { expanded = !expanded },
            modifier = if (LocalAuditPhone.current) Modifier.heightIn(min = 48.dp) else Modifier,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                PosDropdownItem(
                    text = displayText(option),
                    selected = currentValue == option,
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AuditLogPaginationControls(
    pagination: ApiResponseDto.PaginationInfo,
    isLoading: Boolean,
    pageSize: Int,
    onPageSizeChange: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    language: Language,
) {
    val phone = LocalAuditPhone.current
    val arrowSize = auditIconSize()
    val pageSizeChip: @Composable () -> Unit = {
        ChipDropdown(
            label = "${str(AuditLogKeys.pageSize)}: $pageSize",
            selected = false,
            options = listOf("20", "50", "100"),
            displayText = { it ?: "20" },
            currentValue = pageSize.toString(),
            onSelected = { it?.toIntOrNull()?.let(onPageSizeChange) },
        )
    }
    val totalItems: @Composable () -> Unit = {
        Text(
            text = str(AuditLogKeys.totalItems, pagination.totalItems),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val previous: @Composable () -> Unit = {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = str(AuditLogKeys.previous),
            onClick = onPreviousPage,
            enabled = pagination.hasPrevious && !isLoading,
            tint = MaterialTheme.colorScheme.primary,
            size = arrowSize,
        )
    }
    val pageIndicator: @Composable () -> Unit = {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Text(
                text = str(AuditLogKeys.pageLabel, pagination.currentPage + 1, pagination.totalPages),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val next: @Composable () -> Unit = {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = str(AuditLogKeys.next),
            onClick = onNextPage,
            enabled = pagination.hasNext && !isLoading,
            tint = MaterialTheme.colorScheme.primary,
            size = arrowSize,
        )
    }

    if (phone) {
        // Two lines: what is shown, then where you are. One line cannot hold six children at 358dp.
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pageSizeChip()
                totalItems()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                previous()
                pageIndicator()
                next()
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pageSizeChip()
        Spacer(Modifier.width(8.dp))
        totalItems()
        Spacer(Modifier.weight(1f))
        previous()
        pageIndicator()
        next()
    }
}

// ─── Utilities ───

/**
 * A server timestamp as the short local date-time every other screen shows (`03.10.2026 15:31`).
 * admin20 NOTE-D: this printed the ISO form (`2026-10-03 15:31`). An instant is converted to local
 * time; anything unparseable comes back unchanged.
 */
@Composable
private fun formatTimestamp(timestamp: String): String = timestamp.formatInstantShortDateTime(LocalAppLanguage.current)
