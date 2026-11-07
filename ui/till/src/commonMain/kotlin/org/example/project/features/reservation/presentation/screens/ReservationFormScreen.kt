package org.example.project.features.reservation.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
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
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationFormScreen(
    initialDate: String,
    editReservation: ReservationDto.ResponseDto? = null,
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

    // Success → close dialog
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
        footer = {
            PosTextButton(
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
        // No verticalScroll here: PosDialog's body already scrolls, and a nested scroll container
        // is measured with infinite height and throws.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

            // Time picker — dropdown for TIME_SLOTS, otherwise time picker dialog
            val activeRule = uiState.activeRule
            val activeRuleSlots = activeRule?.timeSlots
            if (activeRule?.ruleType == ReservationRuleType.TIME_SLOTS && !activeRuleSlots.isNullOrEmpty()) {
                // Dropdown of available time slots
                var slotsExpanded by remember { mutableStateOf(false) }
                Box {
                    PosPickerField(
                        value = uiState.reservationTime.take(5),
                        onClick = { slotsExpanded = true },
                        label = str(ReservationKeys.timeSlotRequired),
                        modifier = Modifier.fillMaxWidth(),
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
                // Manual time picker
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

            // Duration display (read-only, from rule)
            uiState.durationMinutes?.let { duration ->
                PosTextField(
                    value = str(ReservationKeys.durationMinutes, duration),
                    onValueChange = {},
                    label = str(ReservationKeys.duration),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    enabled = false,
                )
            }
            // Also show rule-based duration
            if (uiState.durationMinutes == null && activeRule?.durationMinutes != null) {
                val ruleDuration = activeRule.durationMinutes!!
                PosTextField(
                    value = str(ReservationKeys.durationFromRule, ruleDuration),
                    onValueChange = {},
                    label = str(ReservationKeys.duration),
                    modifier = Modifier.fillMaxWidth(),
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
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column {
                                Text(str(ReservationKeys.available), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${avail.availableTables}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Column {
                                Text(str(ReservationKeys.reserved), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${avail.totalTables - avail.availableTables}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Column {
                                Text(str(CommonKeys.total), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "${avail.totalTables}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
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
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val initialDate =
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
            initialDate = initialDate,
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
                PosTextButton(
                    text = str(CommonKeys.ok),
                    onClick = {
                        val timeStr = formatHourMinute(timePickerState.hour, timePickerState.minute)
                        viewModel.onEvent(ReservationFormEvent.TimeChanged(timeStr))
                        showTimePicker = false
                    },
                )
            },
            dismissButton = {
                PosTextButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showTimePicker = false },
                )
            },
        )
    }
}
