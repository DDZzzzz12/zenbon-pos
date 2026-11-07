package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.components.openinghours.OverrideListSection
import org.example.project.features.settings.presentation.components.openinghours.WeeklyScheduleEditor
import org.example.project.features.settings.presentation.state.OpeningHoursEvent
import org.example.project.features.settings.presentation.state.hasInvalidSlot
import org.example.project.features.settings.presentation.viewmodel.OpeningHoursViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.guard.rememberDirtySource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Opening hours section wrapper that integrates the weekly schedule editor
 * and date overrides into the shop info content.
 *
 * Gets its own ViewModel via Koin and handles loading + error propagation.
 */
@Composable
fun OpeningHoursSection(viewModel: OpeningHoursViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val openingHoursTitle = str(SettingsKeys.openingHours)

    // Inert in the till; adminApp's guard asks first. The VM outlives the section, so Discard reloads
    // the schedule or the next visit would resurrect the discarded draft.
    rememberDirtySource(
        dirty = uiState.isScheduleDirty,
        onDiscard = { viewModel.onEvent(OpeningHoursEvent.LoadSchedule) },
    )

    // Load schedule and overrides on first composition
    LaunchedEffect(Unit) {
        if (!uiState.isScheduleLoaded) {
            viewModel.onEvent(OpeningHoursEvent.LoadSchedule)
            viewModel.onEvent(OpeningHoursEvent.LoadOverrides)
        }
    }

    // The typed UiError goes to the shared watcher, not the string overload: only the typed path
    // attaches the error to the notification, which is what lets the host render the traceId, the
    // server's detail and the error code — and what records the failure with ClientErrorReporter.
    //
    // No displayMode override: the previous forced TOAST meant a 5xx here was dismissible and
    // carried no trace id. Under the bus policy a routine failure still toasts exactly as before,
    // and only a server fault or a validation failure escalates to the dialog.
    PosErrorWatcher(
        error = uiState.error,
        title = openingHoursTitle,
        onClear = viewModel::clearError,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Weekly schedule editor
        WeeklyScheduleEditor(
            weeklySchedule = uiState.weeklySchedule,
            onEvent = viewModel::onEvent,
        )

        // Save schedule button
        PosButton(
            text = str(SettingsKeys.saveSchedule),
            icon = Icons.Default.Save,
            onClick = { viewModel.onEvent(OpeningHoursEvent.SaveSchedule) },
            enabled = !uiState.isSaving && !uiState.weeklySchedule.hasInvalidSlot(),
            isLoading = uiState.isSaving,
            modifier = Modifier.align(Alignment.End),
        )

        // Date overrides section
        OverrideListSection(
            overrides = uiState.overrides,
            showDialog = uiState.showOverrideDialog,
            editingOverride = uiState.editingOverride,
            onEvent = viewModel::onEvent,
        )

        Spacer(Modifier.height(8.dp))
    }
}
