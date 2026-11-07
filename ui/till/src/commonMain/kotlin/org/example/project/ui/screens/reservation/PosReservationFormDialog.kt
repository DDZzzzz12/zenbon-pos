package org.example.project.ui.screens.reservation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationRuleType
import org.example.project.core.presentation.components.customer.CustomerSearchField
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.components.picker.CustomDatePickerDialog
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.formatHourMinute
import org.example.project.features.reservation.presentation.state.ReservationFormEvent
import org.example.project.features.reservation.presentation.viewmodels.ReservationFormViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosReservationFormDialog(
    initialDate: String,
    editReservation: ReservationDto.ResponseDto?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ReservationFormViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current
    val language = LocalLanguageManager.current.currentLanguage.value

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val reservationTitle = str(ReservationKeys.title)
    val retryLabel: String? = null

    // Initialize form
    LaunchedEffect(editReservation) {
        if (editReservation != null) {
            viewModel.onEvent(ReservationFormEvent.LoadForEdit(editReservation))
        } else {
            viewModel.onEvent(ReservationFormEvent.ClearForm)
            viewModel.onEvent(ReservationFormEvent.DateChanged(initialDate))
        }
    }

    PosErrorWatcher(
        error = uiState.error,
        title = reservationTitle,
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
    )

    // Success -> close
    PosSuccessWatcher(
        message = uiState.successMessage,
        title = reservationTitle,
        onClear = {
            viewModel.clearSuccessMessage()
            onSaved()
        },
    )

    PosDialog(
        onDismiss = onDismiss,
        title = if (uiState.isEditing) str(ReservationKeys.editReservation) else str(ReservationKeys.newReservation),
        maxWidth = 480.dp,
        footer = {
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosButton(
                text = str(CommonKeys.save),
                onClick = { viewModel.onEvent(ReservationFormEvent.Save) },
                isLoading = uiState.isSaving,
                enabled =
                    uiState.guestName.isNotBlank() &&
                        uiState.reservationDate.isNotBlank() &&
                        uiState.reservationTime.isNotBlank(),
            )
        },
    ) {
        // Guest name with customer search
        CustomerSearchField(
            value = uiState.guestName,
            onValueChange = { viewModel.onEvent(ReservationFormEvent.GuestNameChanged(it)) },
            onCustomerSelected = { customer ->
                viewModel.onEvent(ReservationFormEvent.GuestNameChanged(customer.fullName))
                viewModel.onEvent(ReservationFormEvent.GuestPhoneChanged(customer.phone ?: ""))
                viewModel.onEvent(ReservationFormEvent.CustomerSelected(customer.id, customer.fullName))
            },
            label = str(ReservationKeys.guestName),
            modifier = Modifier.fillMaxWidth(),
        )

        // Guest phone with customer search
        CustomerSearchField(
            value = uiState.guestPhone,
            onValueChange = { viewModel.onEvent(ReservationFormEvent.GuestPhoneChanged(it)) },
            onCustomerSelected = { customer ->
                viewModel.onEvent(ReservationFormEvent.GuestNameChanged(customer.fullName))
                viewModel.onEvent(ReservationFormEvent.GuestPhoneChanged(customer.phone ?: ""))
                viewModel.onEvent(ReservationFormEvent.CustomerSelected(customer.id, customer.fullName))
            },
            label = str(ReservationKeys.phone),
            leadingIcon = Icons.Default.Phone,
            modifier = Modifier.fillMaxWidth(),
        )

        // Customer found indicator
        uiState.customerName?.let { name ->
            Text(
                text = str(ReservationKeys.customerFound, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Party size selector
        Text(
            text = str(ReservationKeys.partySizeLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosFilledIconButton(
                icon = Icons.Default.Remove,
                contentDescription = str(ReservationKeys.decrease),
                onClick = {
                    if (uiState.partySize > 1) {
                        viewModel.onEvent(ReservationFormEvent.PartySizeChanged(uiState.partySize - 1))
                    }
                },
                enabled = uiState.partySize > 1,
            )
            Text(
                text = "${uiState.partySize}",
                style = MaterialTheme.typography.titleLarge,
            )
            PosFilledIconButton(
                icon = Icons.Default.Add,
                contentDescription = str(ReservationKeys.increase),
                onClick = {
                    viewModel.onEvent(ReservationFormEvent.PartySizeChanged(uiState.partySize + 1))
                },
            )
        }

        // Date picker
        Box(modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }) {
            PosTextField(
                value = uiState.reservationDate,
                onValueChange = {},
                label = str(ReservationKeys.dateRequired),
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                enabled = false,
                trailingIcon = {
                    Icon(Icons.Default.CalendarToday, contentDescription = null)
                },
            )
        }

        // Time picker
        val activeRule = uiState.activeRule
        val activeRuleSlots = activeRule?.timeSlots
        if (activeRule?.ruleType == ReservationRuleType.TIME_SLOTS && !activeRuleSlots.isNullOrEmpty()) {
            var slotsExpanded by remember { mutableStateOf(false) }
            Box {
                PosPickerField(
                    value = uiState.reservationTime.take(5),
                    onClick = { slotsExpanded = true },
                    label = str(ReservationKeys.timeSlotRequired),
                    modifier =
                        Modifier
                            .fillMaxWidth(),
                )
                PosDropdown(
                    expanded = slotsExpanded,
                    onDismissRequest = { slotsExpanded = false },
                ) {
                    activeRuleSlots.forEach { slot ->
                        PosDropdownItem(
                            text = slot,
                            selected = slot == uiState.reservationTime.take(5),
                            onClick = {
                                viewModel.onEvent(ReservationFormEvent.TimeChanged(slot))
                                slotsExpanded = false
                            },
                        )
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }) {
                PosTextField(
                    value = uiState.reservationTime.take(5),
                    onValueChange = {},
                    label = str(ReservationKeys.timeRequired),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    enabled = false,
                    trailingIcon = {
                        Icon(Icons.Default.AccessTime, contentDescription = null)
                    },
                )
            }
        }

        // Duration display
        uiState.durationMinutes?.let { duration ->
            PosTextField(
                value = str(ReservationKeys.durationMinutes, duration),
                onValueChange = {},
                label = str(ReservationKeys.duration),
                readOnly = true,
                enabled = false,
            )
        }
        if (uiState.durationMinutes == null && activeRule?.durationMinutes != null) {
            PosTextField(
                value = str(ReservationKeys.durationFromRule, activeRule.durationMinutes!!),
                onValueChange = {},
                label = str(ReservationKeys.duration),
                readOnly = true,
                enabled = false,
            )
        }

        // Capacity info
        uiState.availability?.let { avail ->
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                body {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CapacityColumn(
                            label = str(ReservationKeys.available),
                            value = "${avail.availableTables}",
                        )
                        CapacityColumn(
                            label = str(ReservationKeys.reserved),
                            value = "${avail.totalTables - avail.availableTables}",
                        )
                        CapacityColumn(
                            label = str(CommonKeys.total),
                            value = "${avail.totalTables}",
                        )
                    }
                }
            }
            avail.warningMessage?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Notes
        PosTextField(
            value = uiState.notes,
            onValueChange = { viewModel.onEvent(ReservationFormEvent.NotesChanged(it)) },
            label = str(CommonKeys.notes),
            singleLine = false,
            maxLines = 4,
        )
    }

    // Date picker dialog
    if (showDatePicker) {
        val initialDateParsed =
            try {
                LocalDate.parse(uiState.reservationDate)
            } catch (_: Exception) {
                null
            }
        CustomDatePickerDialog(
            onDismiss = { showDatePicker = false },
            onDateSelected = { date ->
                viewModel.onEvent(ReservationFormEvent.DateChanged(date.toString()))
                showDatePicker = false
            },
            language = language,
            initialDate = initialDateParsed,
        )
    }

    // Time picker dialog
    if (showTimePicker) {
        val currentHour =
            try {
                uiState.reservationTime.take(2).toInt()
            } catch (_: Exception) {
                18
            }
        val currentMinute =
            try {
                uiState.reservationTime.substring(3, 5).toInt()
            } catch (_: Exception) {
                0
            }
        val timePickerState =
            rememberTimePickerState(
                initialHour = currentHour,
                initialMinute = currentMinute,
                is24Hour = true,
            )
        AlertDialog( // raw Material 3 by design: contains TimePicker widget which requires AlertDialog layout contract
            onDismissRequest = { showTimePicker = false },
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
                PosButton(
                    text = str(CommonKeys.ok),
                    onClick = {
                        val timeStr = formatHourMinute(timePickerState.hour, timePickerState.minute)
                        viewModel.onEvent(ReservationFormEvent.TimeChanged(timeStr))
                        showTimePicker = false
                    },
                )
            },
            dismissButton = {
                PosSecondaryButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showTimePicker = false },
                )
            },
        )
    }
}

@Composable
private fun CapacityColumn(
    label: String,
    value: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
