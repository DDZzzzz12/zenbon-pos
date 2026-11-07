package org.example.project.core.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosTextButton

/**
 * Reusable time picker dialog using Material 3 TimePicker.
 * 24-hour format, centered in an AlertDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerAlertDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(ReservationKeys.selectTime)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            PosTextButton(
                text = str(CommonKeys.ok),
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            )
        },
        dismissButton = {
            PosTextButton(text = str(CommonKeys.cancel), onClick = onDismiss)
        },
    )
}
