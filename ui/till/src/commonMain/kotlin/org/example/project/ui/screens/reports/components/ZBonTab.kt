package org.example.project.ui.screens.reports.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.FilterBar
import org.example.project.core.presentation.components.docUtil.DocumentViewerPanel
import org.example.project.core.presentation.components.picker.CustomDateRangePickerDialog
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.reports.presentation.components.zBon.ZBonDetailPanel
import org.example.project.features.reports.presentation.components.zBon.ZBonHistoryList
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel

@Composable
fun ZBonTab(
    viewModel: ZBonViewModel,
    onLoadStatistics: (startTime: String, endTime: String) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
    /** Null when the operator may not pay out tips (no `CASHBOOK_WRITE`); hides the action. */
    onPayOutTip: ((zBonId: Long, ZBonDto.EmployeeTipPayoutStatusDto) -> Unit)? = null,
    /** Null when the operator may not storno tip payouts; hides "Stornieren". */
    onReverseTipPayout: ((zBonId: Long, ZBonDto.EmployeeTipPayoutStatusDto, ZBonDto.TipPayoutRecordDto) -> Unit)? = null,
) {
    val windowSize = LocalWindowSizeClass.current
    val zBonState by viewModel.zBonState.collectAsState()
    val isCompact = windowSize.isCompact()

    var showCustomDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadZBonHistory()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isCompact) {
            // Compact: full-width list with slide-in detail
            Column(modifier = Modifier.fillMaxSize()) {
                FilterBar(
                    selectedDateRange = zBonState.selectedDateRange,
                    customDateRange = zBonState.customDateRange,
                    onDateRangeSelected = { viewModel.selectDateRange(it) },
                    onCustomRangeClick = { showCustomDatePicker = true },
                    language = language,
                )

                ZBonHistoryList(
                    zbonHistory = zBonState.zbonHistory,
                    isLoading = zBonState.isLoading,
                    selectedZBonId = zBonState.selectedZBon?.id,
                    onZBonSelected = { viewModel.onZBonSelected(it) },
                    onLoadStatistics = onLoadStatistics,
                    language = language,
                    modifier = Modifier.weight(1f),
                )
            }

            // Slide-in detail panel for compact
            AnimatedVisibility(
                visible = zBonState.selectedZBon != null,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
            ) {
                zBonState.selectedZBon?.let { zBonData ->
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ZBonDetailPanel(
                            zBonData = zBonData,
                            onClose = { viewModel.clearSelectedZBon() },
                            onPrint = { viewModel.printZBon(zBonData.id) },
                            onViewPdf = {
                                viewModel.viewZBonPdf(zBonData.id)
                                viewModel.clearSelectedZBon()
                            },
                            language = language,
                            modifier = Modifier.fillMaxSize(),
                            onPayOutTip = onPayOutTip?.let { pay -> { status -> pay(zBonData.id, status) } },
                            onReverseTipPayout =
                                onReverseTipPayout?.let { reverse -> { status, record -> reverse(zBonData.id, status, record) } },
                        )
                    }
                }
            }
        } else {
            // Desktop: master-detail side by side
            Row(modifier = Modifier.fillMaxSize()) {
                // Left: list panel
                Column(
                    modifier =
                        Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                ) {
                    FilterBar(
                        selectedDateRange = zBonState.selectedDateRange,
                        customDateRange = zBonState.customDateRange,
                        onDateRangeSelected = { viewModel.selectDateRange(it) },
                        onCustomRangeClick = { showCustomDatePicker = true },
                        language = language,
                    )

                    ZBonHistoryList(
                        zbonHistory = zBonState.zbonHistory,
                        isLoading = zBonState.isLoading,
                        selectedZBonId = zBonState.selectedZBon?.id,
                        onZBonSelected = { viewModel.onZBonSelected(it) },
                        onLoadStatistics = onLoadStatistics,
                        language = language,
                        modifier = Modifier.weight(1f),
                    )
                }

                VerticalDivider()

                // Right: detail panel
                Box(
                    modifier =
                        Modifier
                            .weight(0.6f)
                            .fillMaxHeight(),
                ) {
                    val selectedZBon = zBonState.selectedZBon
                    if (selectedZBon != null) {
                        ZBonDetailPanel(
                            zBonData = selectedZBon,
                            onClose = { viewModel.clearSelectedZBon() },
                            onPrint = { viewModel.printZBon(selectedZBon.id) },
                            onViewPdf = {
                                viewModel.viewZBonPdf(selectedZBon.id)
                                viewModel.clearSelectedZBon()
                            },
                            language = language,
                            modifier = Modifier.fillMaxSize(),
                            onPayOutTip = onPayOutTip?.let { pay -> { status -> pay(selectedZBon.id, status) } },
                            onReverseTipPayout =
                                onReverseTipPayout?.let { reverse -> { status, record -> reverse(selectedZBon.id, status, record) } },
                        )
                    } else {
                        // Empty state placeholder
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = str(ReportsKeys.selectZBonHint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

        // PDF viewer overlay
        zBonState.pdfBytes?.let { pdfBytes ->
            DocumentViewerPanel(
                modifier = Modifier.fillMaxWidth(if (isCompact) 1f else 0.6f),
                isVisible = true,
                documentBytes = pdfBytes,
                fileName = zBonState.pdfFileName ?: "ZBon.pdf",
                onClose = { viewModel.clearPdfBytes() },
            )
        }
    }

    // Custom date range picker dialog
    if (showCustomDatePicker) {
        CustomDateRangePickerDialog(
            onDismiss = { showCustomDatePicker = false },
            onDateRangeSelected = { startDate, endDate ->
                viewModel.setCustomDateRange(startDate.toString(), endDate.toString())
                showCustomDatePicker = false
            },
            language = language,
        )
    }
}
