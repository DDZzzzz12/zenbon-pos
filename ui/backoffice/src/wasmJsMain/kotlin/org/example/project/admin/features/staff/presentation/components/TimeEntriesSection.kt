package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.time.TimeEntriesState
import org.example.project.admin.features.staff.presentation.time.TimeEntriesViewModel
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.employee.MonthStepper
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/** Month's clock-in/out entries + overview totals; manager can add/edit/delete. Self-contained: owns its VM keyed to [employeeId]. */
@Composable
fun TimeEntriesSection(
    employeeId: String,
    canManage: Boolean,
    modifier: Modifier = Modifier,
    viewModel: TimeEntriesViewModel = koinViewModel(),
) {
    val now = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }
    LaunchedEffect(employeeId) { viewModel.load(employeeId, now.year, now.month.number) }

    // id to date: the date names the entry in the confirm title
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(modifier.fillMaxWidth().padding(PosSpacing.xl), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str(AdminStringKeys.StaffSectionTime), style = MaterialTheme.typography.titleMedium)
            val s = viewModel.state
            if (s is TimeEntriesState.Data) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonthStepper(
                        year = s.year,
                        month = s.month,
                        onPrevMonth = viewModel::prevMonth,
                        onNextMonth = viewModel::nextMonth,
                    )
                }
            }
        }

        when (val s = viewModel.state) {
            is TimeEntriesState.Loading -> CircularProgressIndicator()
            is TimeEntriesState.Error ->
                Text(
                    str(s.error.messageKey, *s.error.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                )
            is TimeEntriesState.Data -> {
                s.overview?.let { o ->
                    Column {
                        Text(str(AdminStringKeys.TimeOverviewWorked, o.actualWorkMinutes / 60), style = MaterialTheme.typography.bodyMedium)
                        o.targetMonthlyMinutes?.let {
                            Text(
                                str(AdminStringKeys.TimeOverviewTarget, it / 60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (o.overtimeMinutes != 0) {
                            Text(
                                str(AdminStringKeys.TimeOverviewOvertime, o.overtimeMinutes / 60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (canManage) {
                    PosSecondaryButton(str(AdminStringKeys.TimeAddEntry), onClick = { viewModel.openCreate(today(now)) })
                }

                if (s.entries.isEmpty()) {
                    Text(str(AdminStringKeys.TimeNoEntries), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    s.entries.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.date, style = MaterialTheme.typography.bodyMedium)
                                    if (e.manualEntry) {
                                        PosStatusChip(
                                            str(AdminStringKeys.TimeManualBadge),
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                                Text(
                                    "${e.clockIn}–${e.clockOut ?: "…"}  ·  ${e.netWorkMinutes / 60}h",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (canManage) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PosTextButton(str(AdminStringKeys.CommonEdit), onClick = { viewModel.openEdit(e) })
                                    PosTextButton(str(AdminStringKeys.TimeDeleteEntry), onClick = { pendingDelete = e.id to e.date })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewModel.entryEditor?.let { ed ->
        EntryDialog(editor = ed, onField = viewModel::onEntryField, onSave = viewModel::saveEntry, onDismiss = viewModel::closeEntry)
    }

    pendingDelete?.let { (id, date) ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.TimeEntityEntry),
            entityName = date,
            onConfirm = {
                viewModel.deleteEntry(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            consequence = str(AdminStringKeys.TimeDeleteConfirm),
        )
    }
}

private fun twoDigits(n: Int) = if (n < 10) "0$n" else "$n"

private fun today(now: LocalDateTime) = "${now.year}-${twoDigits(now.month.number)}-${twoDigits(now.dayOfMonth)}"
