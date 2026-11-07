package org.example.project.admin.features.dataexport.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
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
import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dataexport.presentation.AdHocExportDialogState
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.form.rememberPosFormFocus

/**
 * Confirms an ad-hoc DATEV export: the from/to dates, and — for the AP ledger only — whether
 * invoices already carried by the cash book are left out, so they are not booked twice.
 *
 * [PosDialog] scrolls its own body; nothing here adds a verticalScroll.
 */
@Composable
fun AdHocExportDialog(
    state: AdHocExportDialogState,
    onStartChange: (LocalDate) -> Unit,
    onEndChange: (LocalDate) -> Unit,
    onExcludeKassenbuchChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The one date field (UI-P1-08): typed or picked, shown in the UI language. The view model holds
    // LocalDates, so text that is not a date stays in the field (flagged) and is remembered here, so
    // Export cannot quietly run on the last good date while the field shows something else.
    var startUnreadable by remember { mutableStateOf(false) }
    var endUnreadable by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val hint = str(CommonKeys.dateFormatHint)
    val startError = if (startUnreadable) str(CommonKeys.dateInvalid, hint) else null
    val endError =
        when {
            endUnreadable -> str(CommonKeys.dateInvalid, hint)
            !state.isValid -> str(AdminStringKeys.DateRangeEndBeforeStart)
            else -> null
        }
    val focus = rememberPosFormFocus<PickerTarget>()
    LaunchedEffect(attempt) {
        if (attempt > 0) {
            val errors =
                buildMap {
                    if (startError != null) put(PickerTarget.START, true)
                    if (endError != null) put(PickerTarget.END, true)
                }
            focus.revealFirst(PickerTarget.entries, errors)
        }
    }

    PosDialog(
        onDismiss = { if (!state.isRunning) onDismiss() },
        title = str(MonthlyCloseKeys.adHocDialogTitle),
        dismissOnClickOutside = false,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text =
                    if (state.isRunning) {
                        str(MonthlyCloseKeys.exportInProgress)
                    } else {
                        str(MonthlyCloseKeys.adHocConfirm)
                    },
                // Never greyed out for a bad range (UI-E2-06): pressing it marks the field instead.
                onClick = { if (startError == null && endError == null) onConfirm() else attempt++ },
                enabled = !state.isRunning,
            ),
        // While the export runs the dialog cannot be closed (onDismiss is ignored), so Cancel must look
        // disabled too rather than an enabled button that does nothing; the primary keeps its
        // "export in progress" label.
        secondary = PosDialogAction(str(CommonKeys.cancel), onDismiss, enabled = !state.isRunning),
    ) {
        Column {
            Text(str(MonthlyCloseKeys.adHocDialogBody))
            Spacer(Modifier.height(12.dp))
            PosDateField(
                value = state.startDate.toString(),
                onValueChange = { value ->
                    val date = runCatching { LocalDate.parse(value) }.getOrNull()
                    startUnreadable = date == null
                    date?.let(onStartChange)
                },
                label = str(MonthlyCloseKeys.adHocStartDate),
                enabled = !state.isRunning,
                isError = startError != null,
                supportingText = startError,
                modifier = with(focus) { Modifier.formAnchor(PickerTarget.START) },
            )
            Spacer(Modifier.height(8.dp))
            PosDateField(
                value = state.endDate.toString(),
                onValueChange = { value ->
                    val date = runCatching { LocalDate.parse(value) }.getOrNull()
                    endUnreadable = date == null
                    date?.let(onEndChange)
                },
                label = str(MonthlyCloseKeys.adHocEndDate),
                enabled = !state.isRunning,
                isError = endError != null,
                supportingText = endError,
                modifier = with(focus) { Modifier.formAnchor(PickerTarget.END) },
            )
            if (state.type == "AP_LEDGER" || state.type == "ALL") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(str(MonthlyCloseKeys.adHocExcludeKassenbuch))
                        Text(str(MonthlyCloseKeys.adHocExcludeKassenbuchHint))
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.excludeKassenbuchLinked,
                        onCheckedChange = { if (!state.isRunning) onExcludeKassenbuchChange(it) },
                        enabled = !state.isRunning,
                    )
                }
            }
        }
    }
}

private enum class PickerTarget { START, END }
