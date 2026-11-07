package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dashboard.presentation.components.DashboardSectionHeader
import org.example.project.admin.features.dashboard.presentation.components.DashboardTile
import org.example.project.admin.features.dashboard.presentation.components.DashboardTileGrid
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * The work waiting in the document flow, as up to four clickable counts in an adaptive [DashboardTileGrid].
 *
 * A tile whose count is zero is not rendered, and when every count is zero the section is not
 * rendered at all: an operator with nothing outstanding should see nothing, not four zeros.
 *
 * @param onOpenInvoices receives the `InvoiceListDto.Bucket` name to open.
 * @param refreshTick incremented by the screen's refresh button; the section reloads on every change.
 */
@Composable
fun OpenWorkTiles(
    refreshTick: Int,
    onOpenInvoices: (String) -> Unit,
    onOpenAllocations: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OpenWorkViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    val title = str(AdminStringKeys.DashboardOpenWorkTitle)

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

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.gridGap)) {
        DashboardSectionHeader(title = title)
        DashboardTileGrid(items = tiles(state, onOpenInvoices, onOpenAllocations))
    }
}

/**
 * The tiles themselves, as lambdas taking the modifier [DashboardTileGrid] wants (weight + equal height). A zero count produces no tile.
 */
@Composable
private fun tiles(
    state: OpenWorkState,
    onOpenInvoices: (String) -> Unit,
    onOpenAllocations: () -> Unit,
): List<@Composable (Modifier) -> Unit> {
    val toCaptureLabel = str(AdminStringKeys.DashboardOpenWorkToCapture)
    val toReviewLabel = str(AdminStringKeys.DashboardOpenWorkToReview)
    val overdueLabel = str(AdminStringKeys.DashboardOpenWorkOverdue)
    val unassignedLabel = str(AdminStringKeys.DashboardOpenWorkUnassigned)
    return buildList {
        if (state.toCapture > 0) {
            add { m ->
                DashboardTile(
                    label = toCaptureLabel,
                    value = state.toCapture.toString(),
                    onClick = { onOpenInvoices(InvoiceListDto.Bucket.ZU_ERFASSEN.name) },
                    modifier = m,
                )
            }
        }
        if (state.toReview > 0) {
            add { m ->
                DashboardTile(
                    label = toReviewLabel,
                    value = state.toReview.toString(),
                    onClick = { onOpenInvoices(InvoiceListDto.Bucket.ZU_PRUEFEN.name) },
                    modifier = m,
                )
            }
        }
        if (state.overdue > 0) {
            add { m ->
                DashboardTile(
                    label = overdueLabel,
                    value = state.overdue.toString(),
                    onClick = { onOpenInvoices(InvoiceListDto.Bucket.UEBERFAELLIG.name) },
                    critical = true,
                    modifier = m,
                )
            }
        }
        if (state.unassignedPayments > 0) {
            add { m ->
                DashboardTile(
                    label = unassignedLabel,
                    value = state.unassignedPayments.toString(),
                    onClick = onOpenAllocations,
                    modifier = m,
                )
            }
        }
    }
}
