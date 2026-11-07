package org.example.project.features.settings.presentation.components.openinghours

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.components.TimePickerAlertDialog
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.features.settings.presentation.state.DayScheduleState
import org.example.project.features.settings.presentation.state.OpeningHoursEvent
import org.example.project.features.settings.presentation.state.opensBeforeCloses
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Weekly schedule editor showing 7 days with time slots.
 *
 * Each day has a closed/open switch and configurable time slots.
 * Uses Column (not LazyColumn) since it is nested in a scrollable parent.
 */
@Composable
fun WeeklyScheduleEditor(
    weeklySchedule: Map<Int, DayScheduleState>,
    onEvent: (OpeningHoursEvent) -> Unit,
) {
    val dayKeys =
        listOf(
            ReservationKeys.dayMon,
            ReservationKeys.dayTue,
            ReservationKeys.dayWed,
            ReservationKeys.dayThu,
            ReservationKeys.dayFri,
            ReservationKeys.daySat,
            ReservationKeys.daySun,
        )

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsSectionHeader(
                    title = str(SettingsKeys.weeklySchedule),
                    icon = Icons.Default.Schedule,
                )

                HorizontalDivider()

                for (dayOfWeek in 0..6) {
                    val dayState = weeklySchedule[dayOfWeek] ?: DayScheduleState()
                    val dayName = str(dayKeys[dayOfWeek])

                    DayRow(
                        dayName = dayName,
                        dayOfWeek = dayOfWeek,
                        dayState = dayState,
                        onEvent = onEvent,
                    )

                    if (dayOfWeek < 6) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        } // end body
    }
}

@Composable
private fun DayRow(
    dayName: String,
    dayOfWeek: Int,
    dayState: DayScheduleState,
    onEvent: (OpeningHoursEvent) -> Unit,
) {
    // Track which time picker is open: Pair(slotIndex, isOpenTime=true/isCloseTime=false)
    var showTimePicker by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Day header with closed toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.width(48.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = str(SettingsKeys.closed),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (dayState.isClosed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Switch(
                    checked = dayState.isClosed,
                    onCheckedChange = { onEvent(OpeningHoursEvent.ToggleDayClosed(dayOfWeek)) },
                )
            }
        }

        // Time slots (only when not closed)
        if (!dayState.isClosed) {
            dayState.slots.forEachIndexed { index, slot ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Open time — clickable to open time picker
                    Box(modifier = Modifier.weight(1f)) {
                        PosTextField(
                            value = slot.openTime.take(5),
                            onValueChange = {},
                            readOnly = true,
                            label = str(SettingsKeys.openTime),
                            trailingIcon = {
                                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(Modifier.matchParentSize().clickable { showTimePicker = index to true })
                    }

                    Text(
                        text = "–",
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    // Close time — clickable to open time picker
                    Box(modifier = Modifier.weight(1f)) {
                        PosTextField(
                            value = slot.closeTime.take(5),
                            onValueChange = {},
                            readOnly = true,
                            label = str(SettingsKeys.closeTime),
                            trailingIcon = {
                                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(Modifier.matchParentSize().clickable { showTimePicker = index to false })
                    }

                    if (dayState.slots.size > 1) {
                        PosIconButton(
                            icon = Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            onClick = { onEvent(OpeningHoursEvent.RemoveSlot(dayOfWeek, index)) },
                            tint = MaterialTheme.colorScheme.error,
                            iconSize = 20.dp,
                        )
                    }
                }
                if (!slot.opensBeforeCloses()) {
                    Text(
                        text = str(SettingsKeys.openingMustPrecedeClosing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            // Add time slot button
            PosTextButton(
                text = str(SettingsKeys.addTimeSlot),
                onClick = { onEvent(OpeningHoursEvent.AddSlot(dayOfWeek)) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    // Time picker dialog
    showTimePicker?.let { (slotIndex, isOpenTime) ->
        val slot = dayState.slots.getOrNull(slotIndex) ?: return@let
        val timeStr = if (isOpenTime) slot.openTime else slot.closeTime
        val hour =
            try {
                timeStr.take(2).toInt()
            } catch (_: Exception) {
                if (isOpenTime) 9 else 22
            }
        val minute =
            try {
                timeStr.substring(3, 5).toInt()
            } catch (_: Exception) {
                0
            }

        TimePickerAlertDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                val newTime = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
                if (isOpenTime) {
                    onEvent(OpeningHoursEvent.UpdateSlot(dayOfWeek, slotIndex, newTime, slot.closeTime))
                } else {
                    onEvent(OpeningHoursEvent.UpdateSlot(dayOfWeek, slotIndex, slot.openTime, newTime))
                }
                showTimePicker = null
            },
            onDismiss = { showTimePicker = null },
        )
    }
}

// Uses shared TimePickerAlertDialog from core/presentation/components/
