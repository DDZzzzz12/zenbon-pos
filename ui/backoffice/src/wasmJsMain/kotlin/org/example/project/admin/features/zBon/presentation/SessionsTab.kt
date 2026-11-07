package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.formatDashboardDateTime
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow

/**
 * The status cell of a session row: "Training" first for a training-mode session (its receipts are
 * Schulungsbelege, badged the same way in the Rechnungen tab), then "Beendet" once it is completed.
 */
internal fun sessionStatusKeys(session: TableSessionDto.TableSessionResponseDto): List<StringKey> =
    listOfNotNull(
        BillReceiptKeys.trainingBadge.takeIf { session.isTraining },
        AdminStringKeys.SessionStatusCompleted.takeIf { session.completed },
    )

/**
 * Sessions tab: [PosAdaptiveDataTable] showing table sessions.
 * Columns: Table, Employee, Start, End, Status.
 */
@Composable
fun SessionsTab(
    sessions: List<TableSessionDto.TableSessionResponseDto>,
    onSessionClick: (TableSessionDto.TableSessionResponseDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns =
        listOf(
            PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.FloorPlanTableNumber), weight = 1f, nowrap = true), PosCardRole.Title),
            PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.EntryDetailEmployee), weight = 1.3f), PosCardRole.Detail),
            PosAdaptiveColumn(
                PosTableColumn(title = str(AdminStringKeys.DashboardZbonStart), weight = 1.3f, minWidth = PosTableMin.DateTime, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(
                PosTableColumn(title = str(AdminStringKeys.DashboardZbonEnd), weight = 1.3f, minWidth = PosTableMin.DateTime, nowrap = true),
                PosCardRole.Detail,
            ),
            PosAdaptiveColumn(
                PosTableColumn(title = str(CommonKeys.status), weight = 1.2f, align = PosTableAlign.End, minWidth = PosTableMin.Pill),
                PosCardRole.Trailing,
            ),
        )

    val rows =
        sessions.map { session ->
            PosTableRow(
                cells =
                    listOf(
                        session.tableNumber,
                        session.employeeNumber,
                        session.startTime?.let { formatDashboardDateTime(it.toString()) } ?: "",
                        session.endTime?.let { formatDashboardDateTime(it.toString()) } ?: "-",
                        // A training session leads with "Training", like its receipts in the
                        // Rechnungen tab (admin21 FAIL-A21).
                        sessionStatusKeys(session).map { str(it) }.joinToString(" · ").ifEmpty { "-" },
                    ),
                onClick = { onSessionClick(session) },
            )
        }

    // 58 sessions on one closing is normal: without its own scroll state the table clips at the
    // viewport and every row below the fold is unreachable (QA admin18 FAIL-8). Same pattern as BillsTab.
    PosAdaptiveDataTable(
        columns = columns,
        rows = rows,
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    )
}
