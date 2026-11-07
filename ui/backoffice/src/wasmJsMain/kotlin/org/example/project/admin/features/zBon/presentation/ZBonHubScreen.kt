package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminReceiptPane
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.PosBillDetailPanel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.zBon.ZBonDetailPanel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ZBonHubScreen(
    zbonId: Long,
    onBack: () -> Unit,
    onSessionClick: (TableSessionDto.TableSessionResponseDto) -> Unit = {},
    onBillClick: (BillDto.BillResponseDto) -> Unit = {},
    viewModel: ZBonHubViewModel = koinViewModel { parametersOf(zbonId) },
) {
    LaunchedEffect(zbonId) { viewModel.load() }

    val state = viewModel.state
    val language = LocalAppLanguage.current
    var selectedSessionId by remember { mutableStateOf<Long?>(null) }

    if (selectedSessionId != null) {
        val sessionId = selectedSessionId!!
        val currencyCode =
            state.zBon
                ?.paymentSummary
                ?.firstOrNull()
                ?.currency ?: "EUR"
        SessionDetailScreen(
            sessionId = sessionId,
            onBack = { selectedSessionId = null },
            currencyCode = currencyCode,
        )
        return
    }

    PosErrorWatcher(
        error = state.zBonError,
        title = str(AdminStringKeys.ReportsFinancialTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load() },
    )

    // Separate watcher: a failed print or download must not offer "retry" meaning "reload the page".
    PosErrorWatcher(
        error = state.actionError,
        title = str(AdminStringKeys.ReportsFinancialTitle),
        onClear = viewModel::clearActionError,
    )

    val anyLoading = state.zBonLoading

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = str(AdminStringKeys.NavBack),
                onClick = onBack,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                state.zBon?.let { zBon ->
                    Text(
                        text = str(AdminStringKeys.ZBonTitleNumber, zBon.zbonNummer.toString()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${zBon.startTime.formatShortDateTime(language)} – ${zBon.endTime.formatShortDateTime(language)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.zBon?.let {
                PosIconButton(
                    icon = Icons.Default.Print,
                    contentDescription = str(AdminStringKeys.SettingsSectionPrinter),
                    onClick = viewModel::printZBon,
                    enabled = !state.printing,
                )
                PosIconButton(
                    icon = Icons.Default.PictureAsPdf,
                    contentDescription = "PDF",
                    onClick = viewModel::downloadPdf,
                    enabled = !state.pdfDownloading,
                )
            }
        }

        if (anyLoading && state.zBon == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            state.zBon?.let { zBon ->
                val currencyCode = zBon.paymentSummary?.firstOrNull()?.currency ?: "EUR"

                // Tab row
                val overviewLabel = str(AdminStringKeys.ZBonOverview)
                val billsLabel = "${str(AdminStringKeys.ZBonBills)} (${state.bills.size})"
                val sessionsLabel = "${str(AdminStringKeys.ZBonSessions)} (${state.sessions.size})"

                PrimaryTabRow(selectedTabIndex = state.activeTab.ordinal) {
                    Tab(
                        selected = state.activeTab == ZBonHubTab.OVERVIEW,
                        onClick = { viewModel.selectTab(ZBonHubTab.OVERVIEW) },
                        text = { Text(overviewLabel) },
                    )
                    Tab(
                        selected = state.activeTab == ZBonHubTab.BILLS,
                        onClick = { viewModel.selectTab(ZBonHubTab.BILLS) },
                        text = { Text(billsLabel) },
                    )
                    Tab(
                        selected = state.activeTab == ZBonHubTab.SESSIONS,
                        onClick = { viewModel.selectTab(ZBonHubTab.SESSIONS) },
                        text = { Text(sessionsLabel) },
                    )
                }

                // Tab content
                when (state.activeTab) {
                    ZBonHubTab.OVERVIEW -> {
                        ZBonDetailPanel(
                            zBonData = zBon,
                            onClose = onBack,
                            onPrint = viewModel::printZBon,
                            onViewPdf = viewModel::downloadPdf,
                            language = language,
                            currencyCode = currencyCode,
                        )
                    }
                    ZBonHubTab.BILLS -> {
                        if (state.billsLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            val selectedBill = state.selectedBill
                            AdminReceiptPane(
                                panelOpen = selectedBill != null,
                                list = {
                                    BillsTab(
                                        bills = state.bills,
                                        searchQuery = state.billSearchQuery,
                                        onSearchQueryChanged = viewModel::onBillSearchQueryChanged,
                                        onBillClick = { bill ->
                                            viewModel.selectBill(bill)
                                            onBillClick(bill)
                                        },
                                        currencyCode = currencyCode,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                },
                                panel = {
                                    if (selectedBill != null) {
                                        // Same receipt panel as the Tagesbericht; related-bill links open in place.
                                        PosBillDetailPanel(
                                            bill = selectedBill,
                                            tableNumber = selectedBill.tableNumber,
                                            currency = currencyCode,
                                            onClose = { viewModel.selectBill(null) },
                                            closeInHeader = true,
                                            onOpenLinkedBill = viewModel::openLinkedBill,
                                            backTo = state.billTrail.backTo,
                                            onBack = viewModel::backToPreviousBill,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    ZBonHubTab.SESSIONS -> {
                        if (state.sessionsLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            SessionsTab(
                                sessions = state.sessions,
                                onSessionClick = { session ->
                                    selectedSessionId = session.id
                                    onSessionClick(session)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
