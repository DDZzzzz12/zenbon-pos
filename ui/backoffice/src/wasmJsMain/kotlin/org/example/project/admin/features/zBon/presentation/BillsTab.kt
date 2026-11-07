package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosSearchBar
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.listSummaryText

/**
 * Bills tab: a search bar + [PosAdaptiveDataTable] filtered client-side by [searchQuery].
 * Columns: bill number, Table, Employee, Time, Total.
 */
@Composable
fun BillsTab(
    bills: List<BillDto.BillResponseDto>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onBillClick: (BillDto.BillResponseDto) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        PosSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChanged,
            placeholder = str(AdminStringKeys.ZBonBillsSearchPlaceholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(12.dp))

        val filtered =
            if (searchQuery.isBlank()) {
                bills
            } else {
                bills.filter { bill ->
                    bill.billNumber.contains(searchQuery, ignoreCase = true) ||
                        bill.tableNumber.contains(searchQuery, ignoreCase = true) ||
                        bill.employeeNumber.contains(searchQuery, ignoreCase = true)
                }
            }

        // Columns: bill number, relation, table, employee, time, total.
        // NEW-06: weights follow the minimum widths, so the table needs only their sum (680dp) instead
        // of the ~805dp the old weights demanded; below that the employee, then the table number give
        // way, and the card form packs the short facts onto one meta line.
        val columns =
            listOf(
                PosAdaptiveColumn(
                    PosTableColumn(title = str(ReportsKeys.billNumber), weight = 1.36f, minWidth = PosTableMin.Id, nowrap = true),
                    PosCardRole.Title,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(BillReceiptKeys.relationSectionTitle), weight = 1.28f, minWidth = 120.dp),
                    PosCardRole.Meta,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(AdminStringKeys.FloorPlanTableNumber), weight = 0.8f, minWidth = 72.dp, nowrap = true),
                    PosCardRole.Meta,
                    hideInTableBelow = 600.dp,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(AdminStringKeys.StaffFieldNumber), weight = 0.88f, minWidth = 80.dp, nowrap = true),
                    PosCardRole.Meta,
                    hideInTableBelow = 680.dp,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(CommonKeys.time), weight = 1.36f, minWidth = PosTableMin.DateTime, nowrap = true),
                    PosCardRole.Meta,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(CommonKeys.total), weight = 1.12f, align = PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                    PosCardRole.Trailing,
                ),
            )
        val rows =
            filtered.map { bill ->
                PosTableRow(
                    cells =
                        listOf(
                            bill.billNumber,
                            // "Training", "Zahlartwechsel zu RE-…", "Storno zu RE-…", "Storniert", …
                            bill.listSummaryText(),
                            bill.tableNumber,
                            bill.employeeNumber,
                            bill.endTime.formatShortDateTime(language),
                            formatMoneyForDisplay(bill.paidAmount, currencyCode),
                        ),
                    onClick = { onBillClick(bill) },
                )
            }

        if (filtered.isEmpty() && bills.isNotEmpty()) {
            Text(
                text = "${str(CommonKeys.noData)} — \"$searchQuery\"",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PosAdaptiveDataTable(columns = columns, rows = rows, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
