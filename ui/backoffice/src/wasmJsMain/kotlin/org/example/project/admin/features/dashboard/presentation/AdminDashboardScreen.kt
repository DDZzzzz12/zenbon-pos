package org.example.project.admin.features.dashboard.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.LocalAdminTopBarActions
import org.example.project.admin.core.presentation.components.RegisterTopBarAction
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dashboard.presentation.checklist.SetupChecklistCard
import org.example.project.admin.features.dashboard.presentation.checklist.SetupChecklistItem
import org.example.project.admin.features.dashboard.presentation.components.KassenbuchEntriesCard
import org.example.project.admin.features.dashboard.presentation.sections.DayCloseCard
import org.example.project.admin.features.dashboard.presentation.sections.DayFiguresCard
import org.example.project.admin.features.dashboard.presentation.sections.OpenWorkTiles
import org.example.project.admin.features.dashboard.presentation.sections.PendingAuthorizationsCard
import org.example.project.admin.features.dashboard.presentation.sections.RestoredPathsCard
import org.example.project.admin.features.dashboard.presentation.sections.TseHealthCard
import org.example.project.admin.features.zBon.closing.ZBonClosingAccess
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosLayout

/**
 * The landing screen: what needs doing, and the way to go and do it.
 *
 * It holds no data of its own. Every section owns a ViewModel, loads itself and fails alone — which
 * is what the sections' four different capability gates require: one shared load would turn a
 * manager's missing `TSE_ADMIN` into an erased dashboard.
 *
 * The refresh button increments [refreshTick]; each card observes it and reloads. One button, seven
 * independent loads, no shared state and no coordinator.
 *
 * Layout (docs/design/admin-dashboard-responsive): the sections keep one order on every width —
 * Handlungsbedarf, Status, Kennzahlen, Offene Vorgänge, Kassenbuch. From [DashboardTwoPaneMinWidth] of content
 * width the status cards move into a fixed right column; below it they stack, and in a row of two
 * from [DashboardStatusRowMinWidth]. Spacing comes from `LocalPosLayout`, never from literals.
 *
 * What used to be here — bills, table sessions, employee revenue, promotions, cancellations and
 * transfers — is the Tagesbericht's job, and it does it properly. The `Heute` card links there.
 *
 * @param showAuthorizations whether the operator may read pending till authorizations
 *   (`SESSION_READ`). Hoisted, like every gate here: a card the principal cannot read is not
 *   composed at all, so its request is never issued and no 403 can reach the screen.
 * @param showFiscalDay `ZBON_READ` + `REPORT_READ` — the *Heute* and *Tagesabschluss* cards, see
 *   [showsFiscalDayCards].
 * @param showOpenWork `ACCOUNTING_READ` + `INVOICE_READ` — the document-flow tiles.
 * @param showTseHealth `TSE_ADMIN` + `TSE_QUEUE_ADMIN`.
 * @param showRestoredPaths `BACKUP_RESTORE` — the post-restore storage-path review, see [RestoredPathsCard].
 * @param onOpenInvoices receives the `InvoiceListDto.Bucket` name the tile stands for.
 * @param onOpenDailyReport `null` when the operator may not open the Tagesbericht (`REPORT_ALL_READ`).
 * @param showsShopFigures false when the server scopes the day figures to the caller's own sales.
 * @param showRecentCashEntries `CASHBOOK_READ` — the latest cash-book entries, see [showRecentCashEntries].
 *   A card the principal cannot read is not composed, so no request is issued and no 403 can occur.
 * @param onEntryClick `null` when the operator may not open the cash book.
 * @param showChecklist the setup checklist — the caller's MANAGER gate; it lives in the status column.
 * @param canAdministerTse a manager sees the checklist's TSE row but is not offered its click.
 * @param onChecklistNavigate where a tapped checklist row goes.
 * @param onOpenKassenbuch `null` hides the "Zum Kassenbuch" link (the operator may not open the cash book).
 */
@Composable
fun AdminDashboardScreen(
    showAuthorizations: Boolean,
    showFiscalDay: Boolean,
    showOpenWork: Boolean,
    showTseHealth: Boolean,
    showRestoredPaths: Boolean,
    zBonClosingAccess: ZBonClosingAccess,
    onOpenZBon: (Long) -> Unit,
    onOpenDailyReport: (() -> Unit)?,
    showsShopFigures: Boolean,
    showRecentCashEntries: Boolean,
    onOpenInvoices: (String) -> Unit,
    onOpenAllocations: () -> Unit,
    onEntryClick: ((Long) -> Unit)? = null,
    onOpenKassenbuch: (() -> Unit)? = null,
    onOpenSigningSettings: (() -> Unit)? = null,
    showChecklist: Boolean = false,
    canAdministerTse: Boolean = false,
    onChecklistNavigate: (SetupChecklistItem) -> Unit = {},
) {
    var refreshTick by remember { mutableStateOf(0) }
    val layout = LocalPosLayout.current
    val stand = remember(refreshTick) { currentDashboardStand() }

    val dayClose: @Composable (Modifier) -> Unit = { m ->
        if (showFiscalDay) {
            DayCloseCard(
                refreshTick = refreshTick,
                access = zBonClosingAccess,
                onOpenZBon = onOpenZBon,
                onOpenSigningSettings = onOpenSigningSettings,
                modifier = m,
            )
        }
    }
    val tse: @Composable (Modifier) -> Unit = { m -> if (showTseHealth) TseHealthCard(refreshTick = refreshTick, modifier = m) }
    val checklist: @Composable () -> Unit = {
        if (showChecklist) {
            SetupChecklistCard(canAdministerTse = canAdministerTse, onNavigate = onChecklistNavigate)
        }
    }
    val figures: @Composable () -> Unit = {
        if (showFiscalDay) {
            DayFiguresCard(
                refreshTick = refreshTick,
                onOpenDailyReport = onOpenDailyReport,
                showsShopFigures = showsShopFigures,
            )
        }
        if (showOpenWork) {
            OpenWorkTiles(
                refreshTick = refreshTick,
                onOpenInvoices = onOpenInvoices,
                onOpenAllocations = onOpenAllocations,
            )
        }
        if (showRecentCashEntries) {
            KassenbuchEntriesCard(
                refreshTick = refreshTick,
                onEntryClick = onEntryClick,
                onOpenKassenbuch = onOpenKassenbuch,
            )
        }
    }
    // The slots below call whatever these hold now, so a new refreshTick or callback reaches a moved card.
    val currentDayClose by rememberUpdatedState(dayClose)
    val currentTse by rememberUpdatedState(tse)
    val currentChecklist by rememberUpdatedState(checklist)
    val currentFigures by rememberUpdatedState(figures)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val arrangement = dashboardLayoutFor(maxWidth, statusPairShown = showFiscalDay && showTseHealth)

        // Each card loads in a LaunchedEffect(refreshTick) and keeps local state (open dialogs, the
        // "settled" flag that hides the placeholder). Composed afresh in another branch, every card would
        // reload and flash its placeholder on each breakpoint crossing; as movable slots they are moved.
        // Remembered inside BoxWithConstraints, whose content is its own composition: the slots move only
        // between the branches below, and each is called at most once per composition.
        val dayCloseSlot = remember { movableContentOf { m: Modifier -> currentDayClose(m) } }
        val tseSlot = remember { movableContentOf { m: Modifier -> currentTse(m) } }
        val checklistSlot = remember { movableContentOf { currentChecklist() } }
        val figuresSlot = remember { movableContentOf { currentFigures() } }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(layout.screenPadding),
            verticalArrangement = Arrangement.spacedBy(layout.paneGap),
        ) {
            DashboardHeader(stand = stand, onRefresh = { refreshTick++ })

            // Handlungsbedarf. First: while the restored-paths card is shown, exports and backups
            // into the listed folders are paused.
            if (showRestoredPaths) {
                RestoredPathsCard(refreshTick = refreshTick)
            }
            if (showAuthorizations) {
                PendingAuthorizationsCard(refreshTick = refreshTick)
            }

            when (arrangement) {
                DashboardLayout.TwoPane ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.paneGap)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(layout.paneGap)) { figuresSlot() }
                        Column(Modifier.width(StatusColumnWidth), verticalArrangement = Arrangement.spacedBy(layout.paneGap)) {
                            dayCloseSlot(Modifier)
                            tseSlot(Modifier)
                            checklistSlot()
                        }
                    }
                // Status: a row of two on a small tablet.
                DashboardLayout.StatusRow -> {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(layout.gridGap),
                    ) {
                        dayCloseSlot(Modifier.weight(1f).fillMaxHeight())
                        tseSlot(Modifier.weight(1f).fillMaxHeight())
                    }
                    checklistSlot()
                    figuresSlot()
                }
                // Status stacked on a phone.
                DashboardLayout.Stacked -> {
                    dayCloseSlot(Modifier)
                    tseSlot(Modifier)
                    checklistSlot()
                    figuresSlot()
                }
            }
        }
    }
}

/**
 * Title and "Stand" line left, refresh right. A phone's top bar already names the screen, so there
 * the title is dropped and refresh moves into that bar (inline only where no bar is provided).
 */
@Composable
private fun DashboardHeader(
    stand: DashboardStand,
    onRefresh: () -> Unit,
) {
    val compact = LocalWindowSizeClass.current.isCompact()
    val refreshInTopBar = compact && LocalAdminTopBarActions.current != null
    val refreshLabel = str(AdminStringKeys.DashboardRefresh)
    if (refreshInTopBar) {
        RegisterTopBarAction {
            PosIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = refreshLabel,
                onClick = onRefresh,
                size = 48.dp,
                iconSize = 24.dp,
            )
        }
    }
    val standLine =
        dashboardDateText(stand.date, LocalAppLanguage.current, compact) + " · " +
            str(AdminStringKeys.DashboardUpdatedAt, stand.time)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (!compact) {
                Text(str(AdminStringKeys.DashboardTitle), style = MaterialTheme.typography.displaySmall)
            }
            Text(
                text = standLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            refreshInTopBar -> Unit
            compact ->
                PosIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = refreshLabel,
                    onClick = onRefresh,
                    size = 48.dp,
                )
            else -> PosSecondaryButton(text = refreshLabel, icon = Icons.Default.Refresh, onClick = onRefresh)
        }
    }
}

private val StatusColumnWidth = 360.dp
