package org.example.project.ui.screens.financialdocs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.FinancialDocumentsKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDrawerController
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact
import org.example.project.features.financialdocuments.presentation.components.FinancialDocumentsDialogs
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.screens.financialdocs.components.CompactCashBookPage
import org.example.project.ui.screens.financialdocs.components.ExpandedCashBookColumn
import org.example.project.ui.screens.financialdocs.components.FinancialDocumentsPdfPreview
import org.koin.compose.viewmodel.koinViewModel

/**
 * The approved operations whose result lands in the cash book this screen shows.
 *
 * Only `BACKDATE_CASH_BOOK_ENTRY` defers today — a receipt older than the shop's limit, booked by
 * the requester once a manager approves (`7d9eed53c`). Kept as a set so the next deferred cash-book
 * operation is one entry here rather than another collector.
 */
private val KASSENBUCH_APPROVAL_OPERATIONS = setOf("BACKDATE_CASH_BOOK_ENTRY")

/**
 * Fiscal cash book (Kassenbuch) screen with PosTheme styling and adaptive layout.
 *
 * The till owns the shop's cash book: entry creation, corrections, log and summary.
 * The document archive (browse, search, invoice metadata, bank statements, linking)
 * lives in adminApp; the till only views the Beleg attached to a cash-book entry.
 *
 * The screen is the fiscal cash book itself — one page at every size, compact and expanded differing
 * only in chrome and layout. Monthly close used to sit beside it in a tab; it now lives in adminApp
 * (closing figures and roll-ups on Berichte, the DATEV exports on Datenexport), so there is nothing
 * left to switch between.
 *
 * Shared overlays (dialogs, document preview) are rendered once on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosFinancialDocsScreen(
    language: Language,
    modifier: Modifier = Modifier,
    kassenbuchViewModel: KassenbuchViewModel = koinViewModel(),
) {
    val windowSize = LocalWindowSizeClass.current
    val kassenbuchState by kassenbuchViewModel.kassenbuchState.collectAsState()
    var showCustomDatePicker by remember { mutableStateOf(false) }

    // Loaded here, above the compact/expanded switch below, and the search held here too: inside
    // either layout they re-ran and reset on every 840 dp crossing (a tablet rotation, a browser
    // resize), refetching books, balance and entries and emptying the search (browser pass 2, R10).
    var searchTerm by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        kassenbuchViewModel.loadKassenbuchData()
    }
    val overlay = LocalGlobalOverlayViewModel.current
    val drawerController = LocalDrawerController.current

    // A manager approved a backdated entry this device asked for: the server booked it as us, so
    // the list and the balance on screen are now behind. Without this the entry only appeared
    // after the refresh icon, even on re-entering the screen (E2E-2026-09-22 R3-3).
    val notificationViewModel = LocalNotificationViewModel.current
    LaunchedEffect(Unit) {
        notificationViewModel.notificationEvents.collect { event ->
            if (event is NotificationUiEvent.ApprovalCompleted && event.operation in KASSENBUCH_APPROVAL_OPERATIONS) {
                kassenbuchViewModel.loadKassenbuchData()
            }
        }
    }

    // Loading notification for kassenbuch entry creation
    var creatingLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(kassenbuchState.isCreating) {
        if (kassenbuchState.isCreating) {
            creatingLoadingId =
                overlay.showLoading(
                    title = getString(KassenbuchKeys.cashBookHistory, language),
                    message = getString(KassenbuchKeys.creatingEntry, language),
                )
        } else {
            creatingLoadingId?.let {
                overlay.dismissById(it)
                creatingLoadingId = null
            }
        }
    }

    PosErrorWatcher(
        error = kassenbuchState.error,
        title = getString(KassenbuchKeys.cashBookHistory, language),
        onClear = { kassenbuchViewModel.clearError() },
        displayMode = DisplayMode.TOAST,
    )

    PosSuccessWatcher(
        message = if (kassenbuchState.lateBookingAwaitingApproval) str(KassenbuchKeys.lateBookingAwaitingApproval) else null,
        title = getString(KassenbuchKeys.cashBookHistory, language),
        onClear = { kassenbuchViewModel.clearLateBookingAwaitingApproval() },
    )

    // Back handler for drawer toggle
    BackHandler(enabled = true, onBack = {
        if (drawerController?.isDrawerAvailable == true) {
            drawerController.toggleDrawer()
        }
    })

    // Main layout with shared overlays
    Box(modifier = modifier.fillMaxSize()) {
        if (!windowSize.isAtLeastExpanded()) {
            PosCompactContent(
                language = language,
                kassenbuchViewModel = kassenbuchViewModel,
                onCustomRangeClick = { showCustomDatePicker = true },
                searchTerm = searchTerm,
                onSearchTermChanged = { searchTerm = it },
            )
        } else {
            PosExpandedContent(
                language = language,
                kassenbuchViewModel = kassenbuchViewModel,
                onCustomRangeClick = { showCustomDatePicker = true },
                searchTerm = searchTerm,
                onSearchTermChanged = { searchTerm = it },
            )
        }

        // Shared overlays rendered once
        FinancialDocumentsDialogs(
            kassenbuchState = kassenbuchState,
            kassenbuchViewModel = kassenbuchViewModel,
            language = language,
            showCustomDatePicker = showCustomDatePicker,
            onDismissCustomDatePicker = { showCustomDatePicker = false },
        )

        FinancialDocumentsPdfPreview(
            kassenbuchState = kassenbuchState,
            kassenbuchViewModel = kassenbuchViewModel,
            modifier =
                if (windowSize.isCompact()) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth(0.7f).align(Alignment.CenterEnd)
                },
        )
    }
}

// ============================================================================
// Compact Layout (Phone / Portrait)
// ============================================================================

/**
 * Compact layout: the cash book, full screen.
 * PosTheme styled top bar with primaryContainer colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PosCompactContent(
    language: Language,
    kassenbuchViewModel: KassenbuchViewModel,
    onCustomRangeClick: () -> Unit,
    searchTerm: String,
    onSearchTermChanged: (String) -> Unit,
) {
    val drawerController = LocalDrawerController.current
    val kassenbuchState by kassenbuchViewModel.kassenbuchState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (drawerController?.isDrawerAvailable == true) {
                        PosNavIconButton(
                            icon = Icons.Default.Menu,
                            contentDescription = str(AccessibilityKeys.menu),
                            onClick = { drawerController.toggleDrawer() },
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Receipt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            getString(KassenbuchKeys.cashBookHistory, language),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                actions = {
                    PosIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = getString(CommonKeys.refresh, language),
                        onClick = { kassenbuchViewModel.loadKassenbuchData() },
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        CompactCashBookPage(
            kassenbuchState = kassenbuchState,
            kassenbuchViewModel = kassenbuchViewModel,
            language = language,
            onCustomRangeClick = onCustomRangeClick,
            searchTerm = searchTerm,
            onSearchTermChanged = onSearchTermChanged,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        )
    }
}

// ============================================================================
// Expanded Layout (Tablet / Desktop)
// ============================================================================

/**
 * Expanded layout: a full-width Cash Book column.
 * PosTheme styled top bar with primaryContainer colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PosExpandedContent(
    language: Language,
    kassenbuchViewModel: KassenbuchViewModel,
    onCustomRangeClick: () -> Unit,
    searchTerm: String,
    onSearchTermChanged: (String) -> Unit,
) {
    val kassenbuchState by kassenbuchViewModel.kassenbuchState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        PosMarqueeText(
                            text = str(FinancialDocumentsKeys.screenTitle),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = str(FinancialDocumentsKeys.screenDescription),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                },
                actions = {
                    PosIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = getString(CommonKeys.refresh, language),
                        onClick = { kassenbuchViewModel.loadKassenbuchData() },
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            ExpandedCashBookColumn(
                kassenbuchState = kassenbuchState,
                kassenbuchViewModel = kassenbuchViewModel,
                language = language,
                onCustomRangeClick = onCustomRangeClick,
                searchTerm = searchTerm,
                onSearchTermChanged = onSearchTermChanged,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp),
            )
        }
    }
}
