package org.example.project.admin.features.documents.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlow
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStage
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStrip
import org.example.project.admin.core.presentation.belegfluss.InvoiceNextStepBar
import org.example.project.admin.core.presentation.belegfluss.rememberInvoiceFlowCounts
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.RegisterTopBarAction
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.components.DocumentFilterPanelFields
import org.example.project.admin.features.documents.presentation.components.DocumentImportCard
import org.example.project.admin.features.documents.presentation.components.documentAppliedFilterChips
import org.example.project.admin.features.documents.presentation.components.documentTableRequiredWidth
import org.example.project.admin.features.documents.presentation.components.documentTypeLabel
import org.example.project.admin.features.documents.presentation.components.documentsResultTable
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.components.PeriodStepper
import org.example.project.features.reports.presentation.isCurrentPeriod
import org.example.project.features.reports.presentation.stepped
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.pluralResultKey
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** How close to the end of the loaded list a page-fetch is triggered. */
private const val LOAD_MORE_THRESHOLD = 5

/** Placeholder rows shown while a search runs. Enough to fill a viewport, not to fake a full page. */
private const val SKELETON_ROWS = 8

private val SKELETON_ROW_HEIGHT = 28.dp

/**
 * Belege — the document archive (DMS) search destination.
 *
 * The whole screen is a single [LazyColumn]: the header (page title, upload action and the filter
 * bar) is one item, and the document rows are spliced in by [documentsResultTable]. A `LazyColumn`
 * nested inside a `verticalScroll` (or inside another `LazyColumn`) measures to infinite height and
 * crashes on wasm, so this screen must not wrap itself in `verticalScroll` and must not give the
 * table its own scrolling container — see `DocumentsResultTable.kt`'s file doc comment.
 *
 * Filters follow the Zenbon pattern for a search-first screen: search + Filter button in the bar at
 * every width; the period stepper (with its fifth Zeitraum segment) joins them where the row has
 * room, the document types are quick chips on wider windows, and everything else — the seven
 * advanced fields, the statuses, include-inactive, and on a phone the period and type too — is in
 * the [PosFilterPanel], edited as a draft applied only by its button.
 *
 * The Belegfluss strip under the header carries the invoice pipeline counts and links out to the
 * matching Rechnungen bucket ([onOpenInvoices], by bucket name) or the Zuordnung queue
 * ([onOpenAllocations]); the next-step bar ends the list once every page is loaded.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun DocumentsScreen(
    onOpenDocument: (Long) -> Unit,
    onOpenInvoices: (String) -> Unit,
    onOpenAllocations: () -> Unit,
) {
    val viewModel: DocumentsViewModel = koinViewModel()
    val state = viewModel.state
    val today =
        remember {
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        }

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive
    // logout, so init would show the previous session's documents.
    LaunchedEffect(Unit) {
        viewModel.loadTypes()
        viewModel.search()
    }

    val flowCounts = rememberInvoiceFlowCounts()
    val mayAssign = LocalAccessPolicy.current.allows(Capability.ACCOUNTING_READ)

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.DocumentsTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::search,
    )

    // The header's upload action opens the upload dialog directly; its outcome appears under the header.
    var showImport by remember { mutableStateOf(false) }
    // Upload is DOCUMENT_WRITE on the server; a read-only group gets the archive without the button (FO-03).
    val access = DocumentsAccess.of(LocalAccessPolicy.current)
    val screenPadding = LocalPosLayout.current.screenPadding
    val compact = LocalPosLayout.current.isCompact
    if (compact && access.mayWrite) {
        RegisterTopBarAction {
            PosIconButton(
                icon = Icons.Default.Upload,
                contentDescription = str(AdminStringKeys.DocumentImportAction),
                onClick = { showImport = true },
            )
        }
    }
    // Above the sticky-vs-scrolling header branch below (and outside the LazyColumn), so a resize
    // that switches it keeps an open filter panel and its draft.
    val filterPanel = rememberPosFilterPanelState(state.applied, FilterPanelKeys.DOCUMENTS)

    val listState = rememberLazyListState()
    val hasMore = state.hasMore
    val isLoadingMore = state.isLoadingMore
    val items = state.items
    // Keyed on item id, not raw LazyColumn index: the screen is one LazyColumn holding the header
    // before the document rows, so a visible item's *global* index does not line up with its
    // position within `items` — comparing it against `items.size` would fire the load either far
    // too early or never, offset by however many non-row items precede the table.
    // `hasMore`/`isLoadingMore` still gate through the LaunchedEffect key, not a keyless
    // derivedStateOf — see DocumentsViewModel's paging notes.
    LaunchedEffect(listState, hasMore, isLoadingMore, items) {
        val triggerIds = items.takeLast(LOAD_MORE_THRESHOLD).map { it.id }.toSet()
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.key
        }.collect { lastVisibleKey ->
            if (hasMore && !isLoadingMore && lastVisibleKey in triggerIds) {
                viewModel.loadMore()
            }
        }
    }

    // Phone is decided by the width this screen actually gets (sidebar included), not the window.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val short = PaneBreakpoints.isShortWindow(maxHeight)
        val phone = maxWidth < PaneBreakpoints.PHONE_MAX || short
        // The result list falls back to cards as soon as its columns no longer fit (UI-D-01), and on
        // a short window, where the phone layout is used regardless of width. The table gets the
        // content width minus the screen padding on both sides.
        val tableWidth = maxWidth - screenPadding * 2
        val cardList = phone || tableWidth < maxOf(PaneBreakpoints.PHONE_MAX, documentTableRequiredWidth())
        val barWidth = maxWidth - screenPadding * 2
        val sides = Modifier.padding(horizontal = screenPadding)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PosSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.xl),
        ) {
            // Title, upload action and the filter bar are ONE sticky header: on page three of a result
            // set the operator could no longer see what they had filtered by, and a filter you cannot
            // see is a filter you forget you set. The bar keeps that header to one row on a phone.
            val header: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    // On a phone the top bar names the screen and carries the upload action, so no row.
                    if (!compact) {
                        Row(
                            modifier = Modifier.fillMaxWidth().then(sides).padding(top = PosSpacing.xl),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AdminPageTitle(str(AdminStringKeys.DocumentsTitle))
                            if (access.mayWrite) {
                                PosSecondaryButton(
                                    text = str(AdminStringKeys.DocumentImportAction),
                                    onClick = { showImport = true },
                                )
                            }
                        }
                    }
                    DocumentFilters(state, viewModel, today, barWidth, filterPanel)
                }
            }
            // On a short window (landscape phone) the header scrolls away with the list instead of
            // eating the viewport; otherwise it stays pinned.
            if (short) {
                item { header() }
            } else {
                stickyHeader { header() }
            }

            // The strip scrolls with the list (not part of the pinned header: it would spend viewport
            // on every page of a long result). Absent while the counts load or without INVOICE_READ.
            if (flowCounts != null) {
                item(key = "belegflow-strip") {
                    Box(sides) {
                        InvoiceFlowStrip(
                            counts = flowCounts,
                            selected = null,
                            onSelect = { stage ->
                                if (stage == InvoiceFlowStage.ASSIGN && mayAssign) onOpenAllocations() else onOpenInvoices(stage.bucket.name)
                            },
                        )
                    }
                }
            }

            // A scrolling item rather than part of the sticky header: a permanent upload card would
            // spend a third of the viewport on a control used once per document.
            if (access.mayWrite) {
                item {
                    Box(sides) {
                        DocumentImportCard(
                            dialogOpen = showImport,
                            onDialogDismiss = { showImport = false },
                            onUploaded = { viewModel.search() },
                            onOpenDocument = onOpenDocument,
                        )
                    }
                }
            }

            if (state.isLoading) {
                // In place of the rows, not over the page: a filter click used to blank the very
                // toolbar the operator had just used. Not the shared AdminListSkeleton — this screen
                // cannot use AdminListScaffold at all, because a LazyColumn nested in a verticalScroll
                // measures to infinite height and crashes on wasm.
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().then(sides),
                        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
                    ) {
                        repeat(SKELETON_ROWS) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(SKELETON_ROW_HEIGHT)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                    }
                }
            } else if (state.items.isEmpty()) {
                item {
                    val resettable = state.applied.hasAny
                    Box(sides) {
                        AdminListEmptyState(
                            message = str(AdminStringKeys.DocumentsEmpty),
                            icon = Icons.Default.FolderOpen,
                            hint = if (resettable) str(AdminStringKeys.DocumentsEmptyHint) else null,
                            actionText = if (resettable) str(AdminStringKeys.CommonResetFilters) else null,
                            onAction =
                                if (resettable) {
                                    { viewModel.applyPanelFilter(DocumentFilterSummary.cleared(state.applied, clearType = true)) }
                                } else {
                                    null
                                },
                        )
                    }
                }
            } else {
                documentsResultTable(
                    items = state.items,
                    isLoadingMore = state.isLoadingMore,
                    onOpen = onOpenDocument,
                    phone = cardList,
                    horizontalPadding = screenPadding,
                )
            }

            // Only once every page is loaded: as the last item it would otherwise become the "last
            // visible" key that the load-more trigger above compares against document ids.
            if (flowCounts != null && !state.hasMore && InvoiceFlow.nextStep(flowCounts) != null) {
                item(key = "belegflow-next-step") {
                    Box(sides) {
                        InvoiceNextStepBar(
                            counts = flowCounts,
                            onOpen = { stage ->
                                if (stage == InvoiceFlowStage.ASSIGN) onOpenAllocations() else onOpenInvoices(stage.bucket.name)
                            },
                            canOpen = { stage -> stage != InvoiceFlowStage.ASSIGN || mayAssign },
                        )
                    }
                }
            }
        }

        DocumentFilterPanel(state, viewModel, today, barWidth, filterPanel)
    }
}

/**
 * The filter bar. Search and the type quick chips apply at once, the bar's period stepper too; the
 * Filter button opens [filterPanel], which the screen composes ([DocumentFilterPanel]).
 */
@Composable
private fun DocumentFilters(
    state: DocumentsState,
    viewModel: DocumentsViewModel,
    today: LocalDate,
    barWidth: Dp,
    filterPanel: PosFilterPanelState<DocumentSearchFilter>,
) {
    val compact = LocalPosLayout.current.isCompact
    val periodInPanel = DocumentBarLayout.periodInPanel(compact, barWidth)
    // The panel holds the type on a phone; wider, the types are the bar's quick chips.
    val typeInPanel = compact
    val applied = state.applied

    val count = str(pluralResultKey(state.total, AdminStringKeys.DocumentsCountOne, AdminStringKeys.DocumentsCount), state.total)
    PosFilterBar(
        search =
            PosFilterSearch(
                query = applied.searchTerm,
                onQueryChange = viewModel::updateSearchTerm,
                placeholder = str(AdminStringKeys.DocumentsSearchPlaceholder),
            ),
        // Where the stepper is in the panel, the result line still says which period is on screen.
        resultText = if (periodInPanel) "$count · ${applied.periodLabel(LocalAppLanguage.current)}" else count,
        period =
            if (periodInPanel) {
                null
            } else {
                { DocumentPeriodStepper(applied, viewModel, today, DocumentBarLayout.periodMaxWidth(barWidth)) }
            },
        quickFilters = if (typeInPanel) emptyList() else typeQuickChips(applied.fileType, state.availableTypes, viewModel::selectType),
        appliedFilters =
            documentAppliedFilterChips(applied) { filter ->
                when (filter) {
                    is DocumentAppliedFilter.Advanced -> viewModel.clearAdvancedField(filter.field)
                    is DocumentAppliedFilter.Status -> viewModel.clearStatus(filter.status)
                    DocumentAppliedFilter.IncludeInactive -> viewModel.toggleIncludeInactive()
                }
            },
        activeFilterCount = DocumentFilterSummary.activeCount(applied, typeInPanel),
        onOpenPanel = filterPanel::open,
        // Quick + panel filters; never the search text, which is the bar's (PosFilterBar contract).
        onClearAll = { viewModel.applyPanelFilter(DocumentFilterSummary.cleared(applied, clearType = true)) },
    )
}

/**
 * The panel, editing [filterPanel]'s draft that reaches the view model only through its apply
 * button ([applyPanelFilter]); closing it drops the draft. A period or type the draft changed stays
 * shown when a resize moves that control back into the bar ([DocumentFilterSummary.showsPeriod]).
 */
@Composable
private fun DocumentFilterPanel(
    state: DocumentsState,
    viewModel: DocumentsViewModel,
    today: LocalDate,
    barWidth: Dp,
    filterPanel: PosFilterPanelState<DocumentSearchFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = state.applied
    val draft = filterPanel.draft
    val showType = DocumentFilterSummary.showsType(typeInPanel = compact, applied = applied, draft = draft.value)
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = { filterPanel.applyDraft(viewModel::applyPanelFilter) },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { DocumentFilterSummary.cleared(it, clearType = showType) },
                applyReset = { viewModel.applyPanelFilter(DocumentFilterSummary.cleared(applied, clearType = compact)) },
            )
        },
    ) {
        DocumentFilterPanelFields(
            draft = draft,
            availableTypes = state.availableTypes,
            today = today,
            showPeriod =
                DocumentFilterSummary.showsPeriod(
                    periodInPanel = DocumentBarLayout.periodInPanel(compact, barWidth),
                    applied = applied,
                    draft = draft.value,
                ),
            showType = showType,
        )
    }
}

/** One quick chip per document type; tapping the selected one again returns to all types. */
@Composable
private fun typeQuickChips(
    selected: String?,
    availableTypes: List<String>,
    onType: (String?) -> Unit,
): List<PosFilterChipItem> {
    val language = LocalAppLanguage.current
    return availableTypes.map { type ->
        PosFilterChipItem(
            label = documentTypeLabel(type, language),
            selected = selected == type,
            onClick = { onType(if (selected == type) null else type) },
        )
    }
}

/**
 * The bar's full period stepper with the fifth Zeitraum segment, applied at once. It takes its own
 * width up to [maxWidth] (what the search field and the Filter button leave); narrower, the
 * `‹ label ›` part wraps under the segment row.
 */
@Composable
private fun DocumentPeriodStepper(
    applied: DocumentSearchFilter,
    viewModel: DocumentsViewModel,
    today: LocalDate,
    maxWidth: Dp,
) {
    Box(Modifier.widthIn(max = maxWidth).width(IntrinsicSize.Max)) {
        PeriodStepper(
            granularity = applied.granularity,
            anchor = applied.anchor,
            label = applied.periodLabel(LocalAppLanguage.current),
            today = today,
            canStepForward = !applied.granularity.isCurrentPeriod(applied.anchor, today),
            onGranularity = { g -> viewModel.selectPeriod(g, minOf(applied.anchor, today)) },
            onStepBack = { viewModel.selectPeriod(applied.granularity, applied.granularity.stepped(applied.anchor, -1)) },
            onStepForward = { viewModel.selectPeriod(applied.granularity, applied.granularity.stepped(applied.anchor, 1)) },
            onPickPeriod = { picked -> viewModel.selectPeriod(applied.granularity, picked) },
            granularities = ReportsGranularity.entries,
            onPickRange = viewModel::selectCustomRange,
        )
    }
}
