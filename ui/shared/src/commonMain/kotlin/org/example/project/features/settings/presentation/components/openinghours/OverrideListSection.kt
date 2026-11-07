package org.example.project.features.settings.presentation.components.openinghours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.core.utils.isBlankOrIsoDate
import org.example.project.core.utils.touchTargetSize
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.features.settings.presentation.state.OpeningHoursEvent
import org.example.project.features.settings.presentation.state.overrideHoursAreValid
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * Override list section showing date-specific opening hours overrides.
 *
 * Displays existing overrides with edit/delete actions and a dialog for add/edit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverrideListSection(
    overrides: List<OpeningHoursDto.OverrideResponseDto>,
    showDialog: Boolean,
    editingOverride: OpeningHoursDto.OverrideResponseDto?,
    onEvent: (OpeningHoursEvent) -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsSectionHeader(
                        title = str(SettingsKeys.dateOverrides),
                        icon = Icons.Default.CalendarMonth,
                    )

                    PosSecondaryButton(
                        text = str(SettingsKeys.addOverride),
                        onClick = { onEvent(OpeningHoursEvent.ShowOverrideDialog) },
                    )
                }

                HorizontalDivider()

                if (overrides.isEmpty()) {
                    Text(
                        text = str(SettingsKeys.noOverrides),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    overrides.forEach { override ->
                        OverrideItem(
                            override = override,
                            onEdit = { onEvent(OpeningHoursEvent.EditOverride(override)) },
                            onDelete = { onEvent(OpeningHoursEvent.DeleteOverride(override.id)) },
                        )
                    }
                }
            }
        }
    }

    // Add/Edit dialog
    if (showDialog) {
        OverrideDialog(
            editingOverride = editingOverride,
            onDismiss = { onEvent(OpeningHoursEvent.HideOverrideDialog) },
            onSave = { date, openTime, closeTime, isClosed, reason ->
                if (editingOverride != null) {
                    onEvent(
                        OpeningHoursEvent.UpdateOverride(
                            id = editingOverride.id,
                            request =
                                OpeningHoursDto.UpdateOverrideRequestDto(
                                    openTime = openTime,
                                    closeTime = closeTime,
                                    isClosed = isClosed,
                                    reason = reason,
                                ),
                        ),
                    )
                } else {
                    onEvent(
                        OpeningHoursEvent.CreateOverride(
                            request =
                                OpeningHoursDto.CreateOverrideRequestDto(
                                    overrideDate = date,
                                    openTime = openTime,
                                    closeTime = closeTime,
                                    isClosed = isClosed,
                                    reason = reason,
                                ),
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun OverrideItem(
    override: OpeningHoursDto.OverrideResponseDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = override.overrideDate.formatShortDate(LocalAppLanguage.current),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (override.isClosed) {
                Text(
                    text = str(SettingsKeys.closed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = "${override.openTime ?: ""} – ${override.closeTime ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            override.reason?.let { reason ->
                if (reason.isNotBlank()) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row {
            PosIconButton(
                icon = Icons.Default.Edit,
                contentDescription = null,
                onClick = onEdit,
                size = touchTargetSize(40.dp),
                iconSize = 20.dp,
            )
            PosIconButton(
                icon = Icons.Default.Delete,
                contentDescription = null,
                onClick = onDelete,
                size = touchTargetSize(40.dp),
                tint = MaterialTheme.colorScheme.error,
                iconSize = 20.dp,
            )
        }
    }
}

@Composable
private fun OverrideDialog(
    editingOverride: OpeningHoursDto.OverrideResponseDto?,
    onDismiss: () -> Unit,
    onSave: (date: String, openTime: String?, closeTime: String?, isClosed: Boolean, reason: String?) -> Unit,
) {
    var date by remember { mutableStateOf(editingOverride?.overrideDate ?: "") }
    var openTime by remember { mutableStateOf(editingOverride?.openTime ?: "09:00") }
    var closeTime by remember { mutableStateOf(editingOverride?.closeTime ?: "22:00") }
    var isClosed by remember { mutableStateOf(editingOverride?.isClosed ?: false) }
    var reason by remember { mutableStateOf(editingOverride?.reason ?: "") }

    val dirty =
        date != (editingOverride?.overrideDate ?: "") ||
            openTime != (editingOverride?.openTime ?: "09:00") ||
            closeTime != (editingOverride?.closeTime ?: "22:00") ||
            isClosed != (editingOverride?.isClosed ?: false) ||
            reason != (editingOverride?.reason ?: "")
    val close = rememberGuardedDismiss(dirty = dirty, onClose = onDismiss)

    val title = if (editingOverride != null) str(SettingsKeys.editOverride) else str(SettingsKeys.addOverride)
    // admin20 NOTE-E: refused here, inline and localized, instead of by the server's raw English 400.
    val hoursValid = overrideHoursAreValid(isClosed, openTime, closeTime)

    // Validate on press (UI-E2-06): Save is never greyed out; a press with no or a bad date, or hours
    // that do not make sense, marks the fields and focuses the first.
    var attempt by remember { mutableStateOf(0) }
    val dateError =
        when {
            attempt == 0 -> null
            date.isBlank() -> str(CommonKeys.fieldRequired)
            !isBlankOrIsoDate(date) -> str(CommonKeys.dateInvalid, str(CommonKeys.dateFormatHint))
            else -> null
        }
    val focus = rememberPosFormFocus<String>()
    LaunchedEffect(attempt) {
        if (attempt > 0) {
            val errors =
                buildMap {
                    if (dateError != null) put("date", true)
                    if (!hoursValid) put("hours", true)
                }
            focus.revealFirst(listOf("date", "hours"), errors)
        }
    }

    PosDialog(
        onDismiss = close,
        title = title,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(CommonKeys.save),
                onClick = {
                    if (date.isBlank() || !isBlankOrIsoDate(date) || !hoursValid) {
                        attempt++
                    } else {
                        onSave(
                            date,
                            if (isClosed) null else openTime,
                            if (isClosed) null else closeTime,
                            isClosed,
                            reason.ifBlank { null },
                        )
                    }
                },
            ),
        secondary = PosDialogAction(str(CommonKeys.cancel), close),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Date field (locked for edit): the one date field, typed or picked, ISO in the state.
            PosDateField(
                value = date,
                onValueChange = { date = it },
                label = requiredLabel(str(SettingsKeys.overrideDate)),
                modifier = with(focus) { Modifier.fillMaxWidth().formAnchor("date") },
                readOnly = editingOverride != null,
                isError = dateError != null,
                supportingText = dateError,
            )

            // Closed toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(SettingsKeys.closed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = isClosed,
                    onCheckedChange = { isClosed = it },
                )
            }

            // Time fields (only when not closed)
            if (!isClosed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosTextField(
                        value = openTime,
                        onValueChange = { openTime = it },
                        label = str(SettingsKeys.openTime),
                        modifier = with(focus) { Modifier.weight(1f).formAnchor("hours") },
                        isError = !hoursValid,
                        placeholder = "HH:mm",
                    )
                    PosTextField(
                        value = closeTime,
                        onValueChange = { closeTime = it },
                        label = str(SettingsKeys.closeTime),
                        modifier = Modifier.weight(1f),
                        isError = !hoursValid,
                        placeholder = "HH:mm",
                    )
                }
                if (!hoursValid) {
                    Text(
                        text = str(SettingsKeys.openingMustPrecedeClosing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Reason field
            PosTextField(
                value = reason,
                onValueChange = { reason = it },
                label = str(SettingsKeys.reason),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
