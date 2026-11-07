package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.layout.adminListMaxWidth
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatDashboardDateTime
import org.example.project.admin.features.zBon.closing.ZBonClosingAccess
import org.example.project.admin.features.zBon.closing.ZBonClosingHost
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Z-Bon list screen: period filter bar (`ZBonFilterBar`) + [PosAdaptiveDataTable] of Z-Bon summaries.
 * Click a row to navigate to the Z-Bon hub (detail + bills + sessions).
 * PDF download lives in the hub's header — not duplicated here.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ZBonListScreen(
    onZBonClick: (Long) -> Unit,
    access: ZBonClosingAccess,
    onOpenSigningSettings: (() -> Unit)? = null,
    viewModel: ZBonListViewModel = koinViewModel(),
) {
    val state = viewModel.state
    var openRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load(state.granularity, state.anchor) }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.NavZBons),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(state.granularity, state.anchor) },
    )

    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    val screenPadding = LocalPosLayout.current.screenPadding
    // Above every layout branch, so a resize that widens the window keeps an open panel and its draft.
    val filterPanel = rememberPosFilterPanelState(ZBonPanelFilter.of(state), FilterPanelKeys.ZBON_LIST)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.adminListMaxWidth().fillMaxSize().padding(vertical = screenPadding),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
        ) {
            AdminPageTitle(str(AdminStringKeys.NavZBons), Modifier.padding(horizontal = screenPadding))

            if (access.mayClose) {
                PosButton(
                    text = str(AdminStringKeys.ZBonCloseButton),
                    onClick = { openRequested = true },
                    modifier = Modifier.padding(horizontal = screenPadding),
                )
                ZBonClosingHost(
                    requestOpen = openRequested,
                    onOpened = { openRequested = false },
                    paysOutTips = access.paysOutTips,
                    onOpenSigningSettings = onOpenSigningSettings,
                    // The list route already requires ZBON_MANAGE, so the new Z-Bon can always be opened.
                    onCreated = { created, _ ->
                        viewModel.load(state.granularity, state.anchor)
                        onZBonClick(created.id)
                    },
                )
            }

            ZBonFilterBar(state, viewModel, today, filterPanel)

            PosCard(modifier = Modifier.fillMaxWidth().padding(horizontal = screenPadding).verticalScroll(rememberScrollState())) {
                body {
                    if (state.zBons.isEmpty()) {
                        Text(
                            text = str(AdminStringKeys.DashboardNoZbons),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val columns =
                            listOf(
                                PosAdaptiveColumn(
                                    PosTableColumn(
                                        str(AdminStringKeys.DashboardZbonNumber),
                                        PosTableAlign.Start,
                                        weight = 1.35f,
                                        minWidth = PosTableMin.Id,
                                        nowrap = true,
                                    ),
                                    PosCardRole.Title,
                                ),
                                PosAdaptiveColumn(
                                    PosTableColumn(
                                        str(AdminStringKeys.DashboardZbonStart),
                                        PosTableAlign.Start,
                                        weight = 1.35f,
                                        minWidth = PosTableMin.DateTime,
                                        nowrap = true,
                                    ),
                                    PosCardRole.Detail,
                                ),
                                PosAdaptiveColumn(
                                    PosTableColumn(
                                        str(AdminStringKeys.DashboardZbonRevenue),
                                        PosTableAlign.End,
                                        weight = 1.1f,
                                        minWidth = PosTableMin.Money,
                                        nowrap = true,
                                    ),
                                    PosCardRole.Trailing,
                                ),
                            )
                        val rows =
                            state.zBons.map { zbon ->
                                PosTableRow(
                                    cells =
                                        listOf(
                                            "#${zbon.zbonNummer}",
                                            formatDashboardDateTime(zbon.startTime),
                                            formatMoneyForDisplay(zbon.totalRevenue, zbon.currency),
                                        ),
                                    onClick = { onZBonClick(zbon.id) },
                                )
                            }
                        PosAdaptiveDataTable(columns, rows)
                    }
                }
            }
        }

        ZBonFilterPanel(state, viewModel, today, filterPanel)

        if (state.isLoading) {
            PosLoadingOverlay(title = str(AdminStringKeys.NavZBons))
        }
    }
}
