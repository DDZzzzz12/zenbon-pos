package org.example.project.ui.screens.reservation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.* // raw Material 3 by design: Scaffold, TopAppBar, ExposedDropdownMenuBox — layout-contract slots
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.reservation.ReservationRuleType
import org.example.project.core.presentation.components.TimePickerAlertDialog
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.components.picker.CustomDatePickerDialog
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.formatHourMinute
import org.example.project.features.reservation.presentation.state.ReservationRulesEvent
import org.example.project.features.reservation.presentation.viewmodels.ReservationRulesViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.*
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationRulesScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReservationRulesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current
    // RESERVATION_DELETE is the manager-level capability in this domain — RESERVATION_READ and
    // RESERVATION_WRITE are till-level, held by every station. It gates the same people the
    // `isManagerOrHigher()` it replaces did.
    val isManager = holds(Capability.RESERVATION_DELETE)

    val rulesTitle = str(ReservationKeys.rulesTitle)

    PosErrorWatcher(
        error = uiState.error,
        title = rulesTitle,
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.onEvent(ReservationRulesEvent.Refresh) },
    )

    // Success messages
    PosSuccessWatcher(
        message = uiState.successMessage,
        title = rulesTitle,
        onClear = { viewModel.clearSuccessMessage() },
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    PosNavIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(CommonKeys.back),
                        onClick = onNavigateBack,
                    )
                },
                title = { PosMarqueeText(text = str(ReservationKeys.rulesTitle)) },
            )
        },
        floatingActionButton = {
            if (isManager) {
                FloatingActionButton(
                    onClick = { viewModel.onEvent(ReservationRulesEvent.ShowCreateDialog) },
                ) {
                    Icon(Icons.Default.Add, contentDescription = str(ReservationKeys.addRule))
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (uiState.rules.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            str(ReservationKeys.noRulesConfigured),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = uiState.rules.sortedBy { it.ruleDate },
                        key = { it.id },
                    ) { rule ->
                        RuleCard(
                            rule = rule,
                            isManager = isManager,
                            onEdit = { viewModel.onEvent(ReservationRulesEvent.EditRule(rule)) },
                            onDelete = { viewModel.onEvent(ReservationRulesEvent.DeleteRule(rule.id)) },
                        )
                    }
                }
            }
        }
    }

    // Create/Edit rule dialog
    if (uiState.showCreateDialog) {
        RuleFormDialog(
            editingRule = uiState.editingRule,
            isSaving = uiState.isSaving,
            onDismiss = { viewModel.onEvent(ReservationRulesEvent.HideCreateDialog) },
            onCreateRule = { request -> viewModel.onEvent(ReservationRulesEvent.CreateRule(request)) },
            onUpdateRule = { id, request -> viewModel.onEvent(ReservationRulesEvent.UpdateRule(id, request)) },
        )
    }
}

@Composable
private fun RuleCard(
    rule: ReservationRuleDto.ResponseDto,
    isManager: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    PosCard {
        body {
            // Date
            Text(
                text = rule.ruleDate,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Rule type badge
            RuleTypeBadge(rule.ruleType)

            // Details
            when (rule.ruleType) {
                ReservationRuleType.FIXED_DURATION -> {
                    val minuteBlocksText = rule.durationMinutes?.let { str(ReservationKeys.minuteBlocks, it) }
                    val details =
                        buildString {
                            minuteBlocksText?.let { append(it) }
                            if (rule.startTime != null && rule.endTime != null) {
                                append(", ${rule.startTime!!.take(5)}-${rule.endTime!!.take(5)}")
                            }
                        }
                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ReservationRuleType.TIME_SLOTS -> {
                    rule.timeSlots?.let { slots ->
                        Text(
                            text = "${str(ReservationKeys.slotsPrefix)} ${slots.joinToString(", ") { it.take(5) }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (isManager) {
            trailing {
                Row {
                    PosIconButton(
                        icon = Icons.Default.Edit,
                        contentDescription = str(CommonKeys.edit),
                        onClick = onEdit,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    PosDangerIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = str(CommonKeys.delete),
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleTypeBadge(ruleType: ReservationRuleType) {
    val label =
        when (ruleType) {
            ReservationRuleType.FIXED_DURATION -> str(ReservationKeys.fixedDuration)
            ReservationRuleType.TIME_SLOTS -> str(ReservationKeys.timeSlots)
        }
    val color =
        when (ruleType) {
            ReservationRuleType.FIXED_DURATION -> MaterialTheme.colorScheme.primary
            ReservationRuleType.TIME_SLOTS -> MaterialTheme.colorScheme.tertiary
        }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleFormDialog(
    editingRule: ReservationRuleDto.ResponseDto?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onCreateRule: (ReservationRuleDto.CreateRequestDto) -> Unit,
    onUpdateRule: (Long, ReservationRuleDto.UpdateRequestDto) -> Unit,
) {
    val language = LocalLanguageManager.current.currentLanguage.value

    var ruleDate by remember { mutableStateOf(editingRule?.ruleDate ?: "") }
    var ruleType by remember { mutableStateOf(editingRule?.ruleType ?: ReservationRuleType.FIXED_DURATION) }
    var durationMinutes by remember { mutableStateOf(editingRule?.durationMinutes?.toString() ?: "120") }
    var startTime by remember { mutableStateOf(editingRule?.startTime ?: "18:00") }
    var endTime by remember { mutableStateOf(editingRule?.endTime ?: "23:00") }
    var timeSlots by remember { mutableStateOf(editingRule?.timeSlots ?: emptyList()) }
    var newSlot by remember { mutableStateOf("") }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showSlotTimePicker by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }

    PosDialog(
        onDismiss = onDismiss,
        title = if (editingRule != null) str(ReservationKeys.editRule) else str(ReservationKeys.newRule),
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosButton(
                text = str(CommonKeys.save),
                onClick = {
                    if (editingRule != null) {
                        val request =
                            ReservationRuleDto.UpdateRequestDto(
                                ruleType = ruleType,
                                durationMinutes =
                                    if (ruleType ==
                                        ReservationRuleType.FIXED_DURATION
                                    ) {
                                        durationMinutes.toIntOrNull()
                                    } else {
                                        null
                                    },
                                startTime = if (ruleType == ReservationRuleType.FIXED_DURATION) startTime else null,
                                endTime = if (ruleType == ReservationRuleType.FIXED_DURATION) endTime else null,
                                timeSlots = if (ruleType == ReservationRuleType.TIME_SLOTS) timeSlots else null,
                            )
                        onUpdateRule(editingRule.id, request)
                    } else {
                        val request =
                            ReservationRuleDto.CreateRequestDto(
                                ruleDate = ruleDate,
                                ruleType = ruleType,
                                durationMinutes =
                                    if (ruleType ==
                                        ReservationRuleType.FIXED_DURATION
                                    ) {
                                        durationMinutes.toIntOrNull()
                                    } else {
                                        null
                                    },
                                startTime = if (ruleType == ReservationRuleType.FIXED_DURATION) startTime else null,
                                endTime = if (ruleType == ReservationRuleType.FIXED_DURATION) endTime else null,
                                timeSlots = if (ruleType == ReservationRuleType.TIME_SLOTS) timeSlots else null,
                            )
                        onCreateRule(request)
                    }
                },
                isLoading = isSaving,
                enabled =
                    ruleDate.isNotBlank() &&
                        when (ruleType) {
                            ReservationRuleType.FIXED_DURATION ->
                                durationMinutes.isNotBlank() &&
                                    startTime.isNotBlank() &&
                                    endTime.isNotBlank()
                            ReservationRuleType.TIME_SLOTS -> timeSlots.isNotEmpty()
                        },
            )
        },
    ) {
        // No verticalScroll here: PosDialog's body already scrolls, and a nested scroll container
        // is measured with infinite height and throws.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Date picker (only for new rules)
            if (editingRule == null) {
                Box(modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }) {
                    PosTextField(
                        value = ruleDate,
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
            } else {
                PosPickerField(
                    value = ruleDate,
                    onClick = { typeExpanded = true },
                    label = str(CommonKeys.date),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                )
            }

            // Rule type selector
            Box {
                PosPickerField(
                    value =
                        when (ruleType) {
                            ReservationRuleType.FIXED_DURATION -> str(ReservationKeys.fixedDuration)
                            ReservationRuleType.TIME_SLOTS -> str(ReservationKeys.timeSlots)
                        },
                    onClick = { typeExpanded = true },
                    label = str(CommonKeys.status),
                    modifier = Modifier.fillMaxWidth(),
                )
                PosDropdown(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    ReservationRuleType.entries.forEach { type ->
                        PosDropdownItem(
                            text =
                                when (type) {
                                    ReservationRuleType.FIXED_DURATION -> str(ReservationKeys.fixedDuration)
                                    ReservationRuleType.TIME_SLOTS -> str(ReservationKeys.timeSlots)
                                },
                            selected = type == ruleType,
                            onClick = {
                                ruleType = type
                                typeExpanded = false
                            },
                        )
                    }
                }
            }

            // Conditional fields based on type
            when (ruleType) {
                ReservationRuleType.FIXED_DURATION -> {
                    // Duration in minutes
                    PosTextField(
                        value = durationMinutes,
                        onValueChange = { if (it.all { c -> c.isDigit() }) durationMinutes = it },
                        label = str(ReservationKeys.durationMinutesLabel),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    // Start time
                    Box(modifier = Modifier.fillMaxWidth().clickable { showStartTimePicker = true }) {
                        PosTextField(
                            value = startTime.take(5),
                            onValueChange = {},
                            label = str(ReservationKeys.startTime),
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            enabled = false,
                            trailingIcon = {
                                Icon(Icons.Default.AccessTime, contentDescription = null)
                            },
                        )
                    }

                    // End time
                    Box(modifier = Modifier.fillMaxWidth().clickable { showEndTimePicker = true }) {
                        PosTextField(
                            value = endTime.take(5),
                            onValueChange = {},
                            label = str(ReservationKeys.endTime),
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            enabled = false,
                            trailingIcon = {
                                Icon(Icons.Default.AccessTime, contentDescription = null)
                            },
                        )
                    }
                }
                ReservationRuleType.TIME_SLOTS -> {
                    // Time slots list
                    Text(
                        str(ReservationKeys.timeSlots),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    timeSlots.forEach { slot ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = slot.take(5),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            PosDangerIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = str(CommonKeys.remove),
                                onClick = { timeSlots = timeSlots - slot },
                            )
                        }
                    }

                    // Add slot button
                    PosSecondaryButton(
                        text = str(ReservationKeys.addTimeSlot),
                        onClick = { showSlotTimePicker = true },
                        icon = Icons.Default.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val initialDate =
            try {
                LocalDate.parse(ruleDate)
            } catch (_: Exception) {
                null
            }
        CustomDatePickerDialog(
            onDismiss = { showDatePicker = false },
            onDateSelected = { date ->
                ruleDate = date.toString()
                showDatePicker = false
            },
            language = language,
            initialDate = initialDate,
        )
    }

    // Start time picker
    if (showStartTimePicker) {
        val hour =
            try {
                startTime.take(2).toInt()
            } catch (_: Exception) {
                18
            }
        val minute =
            try {
                startTime.substring(3, 5).toInt()
            } catch (_: Exception) {
                0
            }

        TimePickerAlertDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                startTime = formatHourMinute(h, m)
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false },
        )
    }

    // End time picker
    if (showEndTimePicker) {
        val hour =
            try {
                endTime.take(2).toInt()
            } catch (_: Exception) {
                23
            }
        val minute =
            try {
                endTime.substring(3, 5).toInt()
            } catch (_: Exception) {
                0
            }

        TimePickerAlertDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                endTime = formatHourMinute(h, m)
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false },
        )
    }

    // Slot time picker
    if (showSlotTimePicker) {
        TimePickerAlertDialog(
            initialHour = 18,
            initialMinute = 0,
            onConfirm = { h, m ->
                val slotStr = formatHourMinute(h, m)
                if (slotStr !in timeSlots) {
                    timeSlots = (timeSlots + slotStr).sorted()
                }
                showSlotTimePicker = false
            },
            onDismiss = { showSlotTimePicker = false },
        )
    }
}

// TimePickerAlertDialog extracted to core/presentation/components/TimePickerAlertDialog.kt
