package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatDashboardDateTime
import org.example.project.admin.features.dashboard.presentation.components.DashboardStatusLine
import org.example.project.admin.features.zBon.closing.ZBonClosedSummaryDialog
import org.example.project.admin.features.zBon.closing.ZBonClosingAccess
import org.example.project.admin.features.zBon.closing.ZBonClosingHost
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.LocalPosColors
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param refreshTick incremented by the screen's refresh button; the card reloads on every change.
 */
@Composable
fun DayCloseCard(
    refreshTick: Int,
    access: ZBonClosingAccess,
    onOpenZBon: (Long) -> Unit,
    onOpenSigningSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: DayCloseViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    var openRequested by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<Pair<ZBonDto.ZBonResponseDto, String>?>(null) }
    val title = str(AdminStringKeys.DashboardDayCloseTitle)

    val settled = rememberSectionSettled(state.isLoading, state.failed)
    if (state.isLoading && !settled) return
    if (state.failed) {
        SectionUnavailable(title = title, modifier = modifier)
        return
    }
    // An empty day still offers the button: the server answers if there is nothing to close.
    if (state.isEmpty && !access.mayClose) return

    // hasZBon, not a null check: the server answers "never closed" with a 2020-01-01 sentinel
    // rather than null, and "Letzter Z-Bon: 01.01.2020" reads as a close that never happened.
    val zBonLine =
        if (state.hasZBon) {
            str(AdminStringKeys.DashboardDayCloseLastZBon, formatDashboardDateTime(state.lastZBonEndTime.orEmpty()))
        } else {
            str(AdminStringKeys.DashboardDayCloseNoZBon)
        }
    // Singular through its own key, as elsewhere (`*One`): "1 Tische noch offen" read wrong.
    val openLine =
        when (state.openSessionCount) {
            0 -> null
            1 -> str(AdminStringKeys.DashboardDayCloseOpenTablesOne)
            else -> str(AdminStringKeys.DashboardDayCloseOpenTables, state.openSessionCount.toString())
        }

    PosCard(modifier = modifier.fillMaxWidth(), variant = PosCardVariant.Plain) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                // Open tables are waiting, not failing: amber, not error.
                if (openLine != null) {
                    DashboardStatusLine(
                        text = openLine,
                        color = LocalPosColors.current.posOccupied,
                        textColor = LocalPosColors.current.posOccupiedInk,
                    )
                }
                Text(
                    text = zBonLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (access.mayClose) {
                    PosButton(
                        text = str(AdminStringKeys.ZBonCloseButton),
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        onClick = { openRequested = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (access.mayClose) {
        ZBonClosingHost(
            requestOpen = openRequested,
            onOpened = { openRequested = false },
            paysOutTips = access.paysOutTips,
            onOpenSigningSettings = onOpenSigningSettings,
            onCreated = { created, currency ->
                viewModel.refresh()
                if (access.opensZBon) onOpenZBon(created.id) else summary = created to currency
            },
        )
        summary?.let { ZBonClosedSummaryDialog(it.first, it.second, onDismiss = { summary = null }) }
    }
}
