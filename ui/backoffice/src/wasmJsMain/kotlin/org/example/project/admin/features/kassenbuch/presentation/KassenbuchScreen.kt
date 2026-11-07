package org.example.project.admin.features.kassenbuch.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.kassenbuch.presentation.components.BookManagementCallbacks
import org.example.project.admin.features.kassenbuch.presentation.components.CashBookCloseAction
import org.example.project.admin.features.kassenbuch.presentation.components.CashBookManagementDialog
import org.example.project.admin.features.kassenbuch.presentation.components.CashBookSwitch
import org.example.project.admin.features.kassenbuch.presentation.components.CashBookTabs
import org.example.project.admin.features.kassenbuch.presentation.components.CreateEntryCallbacks
import org.example.project.admin.features.kassenbuch.presentation.components.CreateEntryDialog
import org.example.project.admin.features.kassenbuch.presentation.components.KassenbuchDetailPanel
import org.example.project.admin.features.kassenbuch.presentation.components.KassensturzDialog
import org.example.project.admin.features.kassenbuch.presentation.components.NewEntryAction
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.shownBookingText
import org.example.project.features.reports.presentation.formatAnchor
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.layout.DrillVisibility
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Only used before anything at all has loaded. Both the cash books and the entries report their
 * own currency, so this is reached only in the first frames, or if the shop has neither.
 */
private const val FALLBACK_CURRENCY = "EUR"

/** Width of the detail pane when it sits beside the table. Bounded on purpose — see [EntryPanes]. */
private val DetailPanelWidth = 420.dp

/** Gap between the two panes, subtracted from the table's width so the pair fits exactly. */
private val PaneGap = 16.dp

/**
 * Below this the panes stack instead of sitting side by side: at 1000 dp the table still keeps
 * ~564 dp beside a 420 dp panel, which is where its five columns stop crowding.
 */
private val SideBySideMinWidth = 1000.dp

/** Shown in the Zeitraum column when a row's timestamp cannot be read. Not a label, so not a key. */
private const val UNKNOWN_PERIOD = "\u2014"

/**
 * The Kassenbuch screen: a period-stepped table of the selected cash book's bookings, with the
 * selected booking's detail beside it.
 *
 * Controls follow the Zenbon filter pattern for a period-filtered screen ([KassenbuchFilterBar]):
 * the period stepper is primary, search moves into the filter panel on a phone, and the cash-book
 * switch is the view switch under the bar (chips wide, a dropdown on a phone). [PosCard] and
 * `PeriodStepper` both force
 * `fillMaxWidth` internally, so nothing here may sit beside a `weight()`ed sibling: the weighted one
 * measures to 0 px and renders nothing at all, silently and with no error. The two panes therefore
 * take explicit widths — see [EntryPanes].
 *
 * @param preselectedHeaderId Entry to open on arrival. This is the dashboard's deep link: its
 *   "latest entries" card used to push a standalone detail route, and now lands here with the row
 *   already selected.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun KassenbuchScreen(
    preselectedHeaderId: Long? = null,
    openBookManagement: Boolean = false,
    viewModel: KassenbuchViewModel = koinViewModel(),
    onOpenDocument: ((Long) -> Unit)? = null,
) {
    val state = viewModel.state
    LaunchedEffect(Unit) {
        viewModel.loadBooks()
        viewModel.load(state.granularity, state.anchor)
    }
    LaunchedEffect(preselectedHeaderId) {
        // Runs independently of the period load: the deep-linked entry may well be outside the
        // period the screen opens on, and the panel must show it either way.
        if (preselectedHeaderId != null) viewModel.selectEntry(preselectedHeaderId)
    }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.NavKassenbuch),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(state.granularity, state.anchor) },
    )

    PosSuccessWatcher(
        message = if (state.lateBookingAwaitingApproval) str(KassenbuchKeys.lateBookingAwaitingApproval) else null,
        title = str(AdminStringKeys.NavKassenbuch),
        onClear = viewModel::clearLateBookingAwaitingApproval,
    )

    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    // The selected book's own code first: it is the one the dialogs are about, and it is correct
    // even for a period with no entries at all.
    val currencyCode =
        state.selectedBook?.currency
            ?: state.books.firstOrNull()?.currency
            ?: state.entries.firstOrNull()?.currency
            ?: FALLBACK_CURRENCY

    // Book management mutates the shop's ledgers; creating and updating a book is CASHBOOK_WRITE on the
    // server (CashBookRules), the same capability posting an entry needs.
    val canManage = CashbookAccess.of(LocalAccessPolicy.current).mayPost
    var showBookManagement by remember { mutableStateOf(openBookManagement && canManage) }
    val screenPadding = LocalPosLayout.current.screenPadding
    val windowCompact = LocalPosLayout.current.isCompact
    // Above every layout branch below (header composed or not, search in the bar or the panel), so
    // a resize that switches one keeps an open filter panel and its draft.
    val filterPanel = rememberPosFilterPanelState(KassenbuchPanelFilter.of(state), FilterPanelKeys.KASSENBUCH)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Judged on the content width (the gutters are not content) so it agrees with the width
        // EntryPanes later measures for its own drill decision. A short window is compact too:
        // table and detail side by side would leave the header no room above them.
        val compact =
            maxWidth - screenPadding * 2 < SideBySideMinWidth || PaneBreakpoints.isShortWindow(maxHeight)
        val barWidth = maxWidth - screenPadding * 2
        val searchInPanel = KassenbuchBarLayout.searchInPanel(compact = windowCompact, barWidth = barWidth)
        // While compact the opened entry takes the whole screen: the header block is not composed.
        val showHeader = !(compact && state.selectedEntry != null)
        Column(
            modifier =
                if (compact && showHeader) {
                    // The list page scrolls as a whole. An opened entry does not: KassenbuchDetailPanel
                    // scrolls its own body, and a second verticalScroll around it measures to infinite
                    // height (the body then renders at zero height).
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = screenPadding)
                } else {
                    Modifier.fillMaxSize().padding(vertical = screenPadding)
                },
            verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        ) {
            if (showHeader) {
                KassenbuchHeader(
                    state = state,
                    viewModel = viewModel,
                    today = today,
                    barWidth = barWidth,
                    searchInPanel = searchInPanel,
                    filterPanel = filterPanel,
                    onManageBooks = if (canManage) ({ showBookManagement = true }) else null,
                )
            }

            Box(Modifier.padding(horizontal = screenPadding)) {
                EntryPanes(
                    state = state,
                    compact = compact,
                    onEntryClick = viewModel::selectEntry,
                    onCloseDetail = viewModel::clearSelectedEntry,
                    onSearchAllPeriods = viewModel::searchAllPeriods,
                    onJumpToEntry = viewModel::jumpToEntry,
                    onLeaveGlobal = viewModel::clearGlobalSearch,
                    onOpenDocument = onOpenDocument,
                )
            }
        }

        KassenbuchFilterPanel(state, viewModel, today, searchInPanel, filterPanel)

        state.entryForm?.let { form ->
            val closeEntryForm =
                rememberGuardedDismiss(dirty = viewModel.isEntryFormDirty, onClose = viewModel::dismissEntryForm)
            CreateEntryDialog(
                form = form,
                currencyCode = currencyCode,
                callbacks =
                    CreateEntryCallbacks(
                        onEntryType = viewModel::setEntryType,
                        onBelegDatum = viewModel::setEntryBelegDatum,
                        onLateBookingReason = viewModel::setEntryLateBookingReason,
                        onBelegtext = viewModel::setEntryBelegtext,
                        onNote = viewModel::setEntryNote,
                        onLine = viewModel::setEntryLine,
                        onAddLine = viewModel::addEntryLine,
                        onRemoveLine = viewModel::removeEntryLine,
                        onDocumentUploaded = viewModel::setEntryDocument,
                        onConfirm = viewModel::submitEntry,
                        onDismiss = closeEntryForm,
                    ),
            )
        }

        if (showBookManagement) {
            BookManagementHost(
                onDismiss = {
                    showBookManagement = false
                    // The dialog can rename, reorder or deactivate a book, all of which the tabs
                    // show — so they are refetched rather than left describing the old ledgers.
                    viewModel.loadBooks()
                },
            )
        }

        state.kassensturz?.let { sturz ->
            val closeKassensturz =
                rememberGuardedDismiss(dirty = sturz.isDirty, onClose = viewModel::dismissKassensturz)
            KassensturzDialog(
                state = sturz,
                currencyCode = currencyCode,
                onCountedBalance = viewModel::setCountedBalance,
                onNotes = viewModel::setKassensturzNotes,
                onConfirm = viewModel::confirmKassensturz,
                onDismiss = closeKassensturz,
            )
        }

        if (state.isLoading) {
            PosLoadingOverlay(title = str(AdminStringKeys.NavKassenbuch))
        }
    }
}

/**
 * Title, filter bar, cash-book switch and the two booking actions. On a phone this block is not
 * composed while an entry is open, so the detail has the whole screen.
 *
 * The cash-book switch is a view switch (each book is its own ledger, with its own columns), so it
 * sits on its own line under the filter bar: chips on wider windows, a dropdown on a phone.
 */
@Composable
private fun KassenbuchHeader(
    state: KassenbuchState,
    viewModel: KassenbuchViewModel,
    today: LocalDate,
    barWidth: Dp,
    searchInPanel: Boolean,
    filterPanel: PosFilterPanelState<KassenbuchPanelFilter>,
    onManageBooks: (() -> Unit)?,
) {
    val screenPadding = LocalPosLayout.current.screenPadding
    Column {
        AdminPageTitle(str(AdminStringKeys.NavKassenbuch), Modifier.padding(horizontal = screenPadding))

        KassenbuchFilterBar(
            state = state,
            viewModel = viewModel,
            today = today,
            barWidth = barWidth,
            searchInPanel = searchInPanel,
            filterPanel = filterPanel,
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = screenPadding).padding(top = PosSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        ) {
            if (LocalPosLayout.current.isCompact) {
                CashBookSwitch(
                    books = state.books,
                    selectedBookId = state.selectedBookId,
                    onSelect = viewModel::selectBook,
                    onManageBooks = onManageBooks,
                )
            } else {
                CashBookTabs(
                    books = state.books,
                    selectedBookId = state.selectedBookId,
                    onSelect = viewModel::selectBook,
                    modifier = Modifier.fillMaxWidth(),
                    onManageBooks = onManageBooks,
                )
            }

            // Both controls own their own fiscal branch, and both draw nothing on the "Alle Kassen"
            // tab (no book selected), so the row is not composed there at all. On the fiscal tab a
            // Kassensturz is a Z-Bon and a manual entry needs a signing terminal: see
            // CashBookCloseAction and NewEntryAction. Wraps rather than overflows on a phone.
            if (state.selectedBook != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val access = CashbookAccess.of(LocalAccessPolicy.current)
                    // CASHBOOK_WRITE posts an entry, CASHBOOK_CLOSE closes the book; each control follows its own (FO-05).
                    if (access.mayPost) NewEntryAction(book = state.selectedBook, onNewEntry = viewModel::openEntryForm)
                    CashBookCloseAction(
                        book = state.selectedBook,
                        latestZBonEndTime = state.latestZBonEndTime,
                        onKassensturz = viewModel::openKassensturz,
                        canClose = access.mayClose,
                    )
                }
            }
        }
    }
}

/**
 * Hosts the book-management dialog and its own ViewModel.
 *
 * The ViewModel is obtained here, inside the `if` that shows the dialog, so it is only created when
 * the operator opens it — and it loads on entry rather than in `init`, matching the rest of
 * adminApp: the whole app shares one `ViewModelStoreOwner`, so an `init` load would show a previous
 * session's books after a logout and re-login.
 */
@Composable
private fun BookManagementHost(
    onDismiss: () -> Unit,
    viewModel: BookManagementViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = viewModel.state.error,
        title = str(AdminStringKeys.KassenbuchManageBooks),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    PosSuccessWatcher(
        message = viewModel.state.successMessage?.let { str(it) },
        title = str(AdminStringKeys.KassenbuchManageBooks),
        onClear = viewModel::clearSuccessMessage,
    )

    CashBookManagementDialog(
        state = viewModel.state,
        callbacks =
            BookManagementCallbacks(
                onCreate = viewModel::openCreateForm,
                onEdit = viewModel::openEditForm,
                onName = viewModel::setBookName,
                onDatevAccount = viewModel::setBookDatevAccount,
                onKost1 = viewModel::setBookKost1,
                onKost2 = viewModel::setBookKost2,
                onCloseInterval = viewModel::setBookCloseInterval,
                onSortOrder = viewModel::setBookSortOrder,
                onActive = viewModel::setBookActive,
                onSubmitBook = viewModel::submitBook,
                onDismissForm = viewModel::dismissForm,
                onTransferTarget = viewModel::setTransferTarget,
                onSaveTransferTarget = viewModel::saveTransferTarget,
                onBelegAge = viewModel::setBelegAgeInput,
                onSaveBelegAge = viewModel::saveBelegAge,
                onSigningTerminal = viewModel::selectSigningTerminal,
                onSaveSigningTerminal = viewModel::saveSigningTerminal,
                onDismiss = onDismiss,
            ),
    )
}

/**
 * The table and the detail panel, laid out side by side when there is room and stacked when there
 * is not.
 *
 * **Both panes get an explicit `.width(...)` and neither uses `weight()`.** That is not a style
 * choice: [PosCard] applies `fillMaxWidth()` on top of the modifier it is handed, so in a [Row] it
 * claims the whole incoming max width during the unweighted measure pass and leaves 0 px for any
 * weighted sibling — which then draws nothing, with no error and no warning. Fixing the widths
 * first means `fillMaxWidth` resolves against constraints that are already exact, so each card
 * fills its own pane and nothing is left to share out.
 *
 * The widths come from [BoxWithConstraints] rather than constants so the pair still tracks the
 * window: the table takes whatever the 420 dp panel and the gap leave behind.
 */
@Composable
private fun EntryPanes(
    state: KassenbuchState,
    compact: Boolean,
    onEntryClick: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    onSearchAllPeriods: () -> Unit,
    onJumpToEntry: (KassenbuchDto.KassenbuchGroupedSummaryDto) -> Unit,
    onLeaveGlobal: () -> Unit,
    onOpenDocument: ((Long) -> Unit)?,
) {
    val selectedEntry = state.selectedEntry

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        // While compact the page scrolls as a whole, so the table card must not scroll itself.
        val selfScroll = !compact
        // Compact drills across the whole stacked band (and in a short but wide window, where the
        // split threshold is lifted out of reach); the split only exists when not compact.
        val splitMin = if (compact) Dp.Infinity else SideBySideMinWidth
        when (PaneBreakpoints.drillVisibility(totalWidth, selectedEntry != null, splitMin = splitMin)) {
            DrillVisibility.ListOnly ->
                EntryTableCard(
                    state = state,
                    onEntryClick = onEntryClick,
                    onSearchAllPeriods = onSearchAllPeriods,
                    onJumpToEntry = onJumpToEntry,
                    onLeaveGlobal = onLeaveGlobal,
                    selfScroll = selfScroll,
                    modifier = Modifier.width(totalWidth),
                )
            DrillVisibility.DetailOnly ->
                if (selectedEntry != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onCloseDetail) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(str(CommonKeys.back))
                        }
                        KassenbuchDetailPanel(
                            onOpenLinkedDocument = onOpenDocument,
                            entry = selectedEntry,
                            onClose = onCloseDetail,
                            onOpenCounterEntry = onEntryClick,
                            modifier = Modifier.width(totalWidth),
                        )
                    }
                }
            DrillVisibility.Both ->
                if (selectedEntry != null && totalWidth >= SideBySideMinWidth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PaneGap)) {
                        EntryTableCard(
                            state = state,
                            onEntryClick = onEntryClick,
                            onSearchAllPeriods = onSearchAllPeriods,
                            onJumpToEntry = onJumpToEntry,
                            onLeaveGlobal = onLeaveGlobal,
                            selfScroll = selfScroll,
                            modifier = Modifier.width(totalWidth - DetailPanelWidth - PaneGap),
                        )
                        KassenbuchDetailPanel(
                            onOpenLinkedDocument = onOpenDocument,
                            entry = selectedEntry,
                            onClose = onCloseDetail,
                            onOpenCounterEntry = onEntryClick,
                            modifier = Modifier.width(DetailPanelWidth),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PaneGap)) {
                        EntryTableCard(
                            state = state,
                            onEntryClick = onEntryClick,
                            onSearchAllPeriods = onSearchAllPeriods,
                            onJumpToEntry = onJumpToEntry,
                            onLeaveGlobal = onLeaveGlobal,
                            selfScroll = selfScroll,
                            modifier = Modifier.width(totalWidth),
                        )
                        if (selectedEntry != null) {
                            KassenbuchDetailPanel(
                                onOpenLinkedDocument = onOpenDocument,
                                entry = selectedEntry,
                                onClose = onCloseDetail,
                                onOpenCounterEntry = onEntryClick,
                                modifier = Modifier.width(totalWidth),
                            )
                        }
                    }
                }
        }
    }
}

/**
 * The table pane: either the global-search hits, or the period's own bookings.
 *
 * With [selfScroll] the card scrolls itself because the table is a plain [Column], not a lazy list,
 * so a long period needs the scroll here rather than inside the table. On a phone the whole screen
 * scrolls instead, and a second `verticalScroll` inside it would crash the wasm measure.
 */
@Composable
private fun EntryTableCard(
    state: KassenbuchState,
    onEntryClick: (Long) -> Unit,
    onSearchAllPeriods: () -> Unit,
    onJumpToEntry: (KassenbuchDto.KassenbuchGroupedSummaryDto) -> Unit,
    onLeaveGlobal: () -> Unit,
    selfScroll: Boolean,
    modifier: Modifier = Modifier,
) {
    PosCard(modifier = if (selfScroll) modifier.verticalScroll(rememberScrollState()) else modifier) {
        body {
            val globalResults = state.globalResults
            if (globalResults != null) {
                GlobalResults(state = state, results = globalResults, onJumpToEntry = onJumpToEntry, onLeaveGlobal = onLeaveGlobal)
            } else {
                PeriodEntries(state = state, onEntryClick = onEntryClick, onSearchAllPeriods = onSearchAllPeriods)
            }
        }
    }
}

/**
 * The loaded period, filtered by the search box.
 *
 * The empty state is where the global search is *offered*. Note the two sentences it can show are
 * different claims: with no filter term the period is simply empty, while with a term the period
 * holds nothing matching — only the latter has anywhere else to look, so only it gets the escape.
 */
@Composable
private fun PeriodEntries(
    state: KassenbuchState,
    onEntryClick: (Long) -> Unit,
    onSearchAllPeriods: () -> Unit,
) {
    val entries = state.visibleEntries
    if (entries.isEmpty()) {
        // Nothing to say until the period has been fetched (FO-68); the loading bar covers the wait.
        if (!state.hasLoaded) return
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = str(AdminStringKeys.KassenbuchNoEntries),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.filterText.isNotBlank()) {
                TextButton(onClick = onSearchAllPeriods) {
                    Text(str(AdminStringKeys.KassenbuchSearchAllPeriods))
                }
            }
        }
        return
    }

    val singleBook = state.selectedBookId != null
    val bookLabels = rememberBookLabeller(state)
    val rows =
        entries.map { entry ->
            PosTableRow(
                cells = entryCells(entry = entry, singleBook = singleBook, bookLabel = { bookLabels(entry) }),
                onClick = { onEntryClick(entry.headerId) },
            )
        }
    PosAdaptiveDataTable(kassenbuchColumns(singleBook), rows)
}

/**
 * Global-search hits, each carrying the period it will take you to.
 *
 * Two things are deliberate here. The empty list says "found nothing **anywhere**", which is a
 * different statement from the period view's "nothing in this period" — that distinction is the
 * whole reason [KassenbuchState.globalResults] is nullable rather than merely empty. And the
 * truncation notice is shown whenever the server's cap was reached: there is no continuation token,
 * so a silently-cut list would read as "that is everything".
 *
 * The **Zeitraum** column is the period the row will actually open, derived from `createdAt` by
 * [attestedDateOf] — the same field the server filters on. It sits next to the Belegdatum on
 * purpose: for a backdated booking the two disagree, and the operator is better served seeing that
 * than being quietly moved somewhere the Belegdatum did not suggest.
 */
@Composable
private fun GlobalResults(
    state: KassenbuchState,
    results: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    onJumpToEntry: (KassenbuchDto.KassenbuchGroupedSummaryDto) -> Unit,
    onLeaveGlobal: () -> Unit,
) {
    // The way back out of global mode, at the head of the results it replaces. The hit count is the
    // filter bar's result line.
    val leaveGlobal: @Composable () -> Unit = {
        TextButton(onClick = onLeaveGlobal) { Text(str(AdminStringKeys.KassenbuchBackToPeriod)) }
    }
    if (results.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            Text(
                text = str(AdminStringKeys.KassenbuchGlobalNoResults),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            leaveGlobal()
        }
        return
    }

    val singleBook = state.selectedBookId != null
    val bookLabels = rememberBookLabeller(state)
    val language = LocalAppLanguage.current

    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        leaveGlobal()
        if (state.globalResultsTruncated) {
            Text(
                text =
                    str(
                        AdminStringKeys.KassenbuchGlobalTruncated,
                        KassenbuchApiClient.SEARCH_RESULT_LIMIT.toString(),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val rows =
            results.map { entry ->
                val period =
                    attestedDateOf(entry.createdAt)
                        ?.let { state.granularity.formatAnchor(it, language) }
                        // An unreadable timestamp means the row cannot say where it will land.
                        // A dash is honest about that; a guessed period would not be, and the
                        // ViewModel correspondingly leaves the stepper where it is.
                        ?: UNKNOWN_PERIOD
                PosTableRow(
                    cells =
                        globalCells(
                            entry = entry,
                            period = period,
                            singleBook = singleBook,
                            bookLabel = { bookLabels(entry) },
                        ),
                    onClick = { onJumpToEntry(entry) },
                )
            }
        PosAdaptiveDataTable(globalColumns(singleBook), rows)
    }
}

/**
 * Resolves a row's cash-book name from the loaded books, falling back to the id.
 *
 * The fallback matters: the book list omits deactivated books, so a historical booking in one would
 * otherwise render a blank cell rather than saying which ledger it belongs to.
 */
@Composable
private fun rememberBookLabeller(state: KassenbuchState): (KassenbuchDto.KassenbuchGroupedSummaryDto) -> String {
    val bookNames = remember(state.books) { state.books.associate { it.id to it.name } }
    // Resolved once rather than per row: the vararg `str` overload is @Composable and would put a
    // composable call inside the row loop for no gain.
    val unknownTemplate = str(AdminStringKeys.KassenbuchUnknownBook)
    return { entry ->
        bookNames[entry.cashBookId] ?: unknownTemplate.replace("{0}", entry.cashBookId.toString())
    }
}

/**
 * The column set depends on the tab, and that is the point of the design rather than cosmetics.
 *
 * [KassenbuchDto.KassenbuchGroupedSummaryDto.currentBalance] is a **per-book** running series: each
 * value is the balance of that entry's own book after that entry. On "Alle Kassen" the rows are
 * interleaved from several books, so stacking those values into one Saldo column would print a
 * sequence that jumps between ledgers and describes none of them. There is no cross-book running
 * balance to show, so the column is dropped and the book itself is named instead.
 */
@Composable
private fun kassenbuchColumns(singleBook: Boolean): List<PosAdaptiveColumn> {
    // NEW-06: weights follow the minimum widths (the text column takes a little more of the slack),
    // so the table needs ~660dp instead of the ~830dp the old weights demanded; in card form Beleg,
    // date, book and balance share one meta line under the text.
    val beleg = adaptive(str(AdminStringKeys.KassenbuchColBeleg), PosTableAlign.Start, 1.36f, PosCardRole.Meta, PosTableMin.Id, nowrap = true)
    val date = adaptive(str(AdminStringKeys.KassenbuchColDate), PosTableAlign.Start, 1.04f, PosCardRole.Meta, PosTableMin.Date, nowrap = true)
    val text = adaptive(str(AdminStringKeys.KassenbuchColText), PosTableAlign.Start, 2.0f, PosCardRole.Title, 160.dp)
    val amount = adaptive(str(AdminStringKeys.KassenbuchColAmount), PosTableAlign.End, 1.12f, PosCardRole.Trailing, PosTableMin.Money, nowrap = true)
    return if (singleBook) {
        listOf(
            beleg,
            date,
            text,
            amount,
            adaptive(str(AdminStringKeys.KassenbuchColBalance), PosTableAlign.End, 1.12f, PosCardRole.Meta, PosTableMin.Money, nowrap = true),
        )
    } else {
        listOf(
            beleg,
            date,
            adaptive(str(AdminStringKeys.KassenbuchColBook), PosTableAlign.Start, 1.04f, PosCardRole.Meta),
            text,
            amount,
        )
    }
}

private fun adaptive(
    title: String,
    align: PosTableAlign,
    weight: Float,
    role: PosCardRole,
    minWidth: Dp = 96.dp,
    nowrap: Boolean = false,
): PosAdaptiveColumn = PosAdaptiveColumn(PosTableColumn(title, align, weight = weight, minWidth = minWidth, nowrap = nowrap), role)

/**
 * The cells for one row, in the order [kassenbuchColumns] declares for the same [singleBook] flag.
 *
 * [bookLabel] is a lambda so the "Alle Kassen" name lookup is skipped entirely on a single-book
 * tab, where the column does not exist.
 */
private fun entryCells(
    entry: KassenbuchDto.KassenbuchGroupedSummaryDto,
    singleBook: Boolean,
    bookLabel: () -> String,
): List<String> {
    val amount = formatMoneyForDisplay(entry.totalAmount, entry.currency, forceSign = true)
    return if (singleBook) {
        listOf(
            entry.belegNr,
            entry.belegDatum,
            entry.belegtext.shownBookingText(),
            amount,
            formatMoneyForDisplay(entry.currentBalance, entry.currency),
        )
    } else {
        listOf(entry.belegNr, entry.belegDatum, bookLabel(), entry.belegtext.shownBookingText(), amount)
    }
}

/**
 * Columns for the global-search table.
 *
 * Carries **both** Belegdatum and Zeitraum. They usually agree; when they do not — a backdated or
 * imported booking — the Zeitraum is the one that says where clicking the row will land, because it
 * comes from the same `createdAt` the server filters periods by. Showing only one of the two would
 * either hide the jump target or imply the Belegdatum decides it.
 *
 * There is no Saldo column: these rows come from many periods and, on the "Alle Kassen" tab, many
 * books, so no running series connects them.
 */
@Composable
private fun globalColumns(singleBook: Boolean): List<PosAdaptiveColumn> {
    val beleg = adaptive(str(AdminStringKeys.KassenbuchColBeleg), PosTableAlign.Start, 1.0f, PosCardRole.Detail, PosTableMin.Id, nowrap = true)
    val date = adaptive(str(AdminStringKeys.KassenbuchColDate), PosTableAlign.Start, 0.9f, PosCardRole.Detail, PosTableMin.Date, nowrap = true)
    val period = adaptive(str(AdminStringKeys.KassenbuchColPeriod), PosTableAlign.Start, 1.0f, PosCardRole.Detail)
    val text = adaptive(str(AdminStringKeys.KassenbuchColText), PosTableAlign.Start, 2.0f, PosCardRole.Title)
    val amount = adaptive(str(AdminStringKeys.KassenbuchColAmount), PosTableAlign.End, 1.0f, PosCardRole.Trailing, PosTableMin.Money, nowrap = true)
    return if (singleBook) {
        listOf(beleg, date, period, text, amount)
    } else {
        listOf(
            beleg,
            date,
            period,
            adaptive(str(AdminStringKeys.KassenbuchColBook), PosTableAlign.Start, 1.2f, PosCardRole.Detail),
            text,
            amount,
        )
    }
}

/** The cells for one global-search row, in the order [globalColumns] declares for the same flag. */
private fun globalCells(
    entry: KassenbuchDto.KassenbuchGroupedSummaryDto,
    period: String,
    singleBook: Boolean,
    bookLabel: () -> String,
): List<String> {
    val amount = formatMoneyForDisplay(entry.totalAmount, entry.currency, forceSign = true)
    return if (singleBook) {
        listOf(entry.belegNr, entry.belegDatum, period, entry.belegtext.shownBookingText(), amount)
    } else {
        listOf(entry.belegNr, entry.belegDatum, period, bookLabel(), entry.belegtext.shownBookingText(), amount)
    }
}
