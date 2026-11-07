package org.example.project.features.settings.presentation

import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.features.settings.presentation.state.DayScheduleState
import org.example.project.features.settings.presentation.state.DaySlot
import org.example.project.features.settings.presentation.state.OpeningHoursState
import org.example.project.features.settings.presentation.state.TseConfigFormState
import org.example.project.features.settings.presentation.state.TseSettingsUiState
import org.example.project.features.settings.presentation.viewmodel.SecuritySessionsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure dirty comparisons behind the leave-confirmation for the shared settings sections. */
class SettingsLeaveDirtyStateTest {
    // ---- Security / sessions ----

    @Test
    fun securitySessions_untouchedIsClean() {
        val state =
            SecuritySessionsUiState(
                dailyResetEnabled = true,
                dailyResetTime = "03:30",
                savedDailyResetEnabled = true,
                savedDailyResetTime = "03:30",
            )
        assertFalse(state.isDirty)
    }

    @Test
    fun securitySessions_changedSwitchOrTimeIsDirty_andUndoIsClean() {
        val saved =
            SecuritySessionsUiState(
                dailyResetEnabled = true,
                dailyResetTime = "03:30",
                savedDailyResetEnabled = true,
                savedDailyResetTime = "03:30",
            )
        assertTrue(saved.copy(dailyResetEnabled = false).isDirty)
        val edited = saved.copy(dailyResetTime = "04:00")
        assertTrue(edited.isDirty)
        assertFalse(edited.copy(dailyResetTime = "03:30").isDirty)
    }

    @Test
    fun securitySessions_transientFlagsDoNotMakeItDirty() {
        assertFalse(SecuritySessionsUiState(isSaving = true, isLoading = true, saved = true).isDirty)
    }

    // ---- Opening hours ----

    @Test
    fun openingHours_untouchedIsClean() {
        assertFalse(OpeningHoursState().isScheduleDirty)
    }

    @Test
    fun openingHours_editedDayIsDirty_andUndoIsClean() {
        val clean = OpeningHoursState()
        val edited =
            clean.copy(
                weeklySchedule = clean.weeklySchedule + (2 to DayScheduleState(isClosed = true)),
            )
        assertTrue(edited.isScheduleDirty)
        assertFalse(edited.copy(weeklySchedule = clean.weeklySchedule).isScheduleDirty)
    }

    @Test
    fun openingHours_slotChangeIsDirty_untilSavedSnapshotCatchesUp() {
        val clean = OpeningHoursState()
        val changed = clean.weeklySchedule + (0 to DayScheduleState(slots = listOf(DaySlot("10:00", "20:00"))))
        val edited = clean.copy(weeklySchedule = changed)
        assertTrue(edited.isScheduleDirty)
        assertFalse(edited.copy(savedSchedule = changed).isScheduleDirty)
    }

    @Test
    fun openingHours_overrideDialogAndSavingFlagsAreNotScheduleEdits() {
        assertFalse(OpeningHoursState(showOverrideDialog = true, isSaving = true, isLoading = true).isScheduleDirty)
    }

    // ---- TSE configuration ----

    private val config = TseConfigDto.TseConfig(timeAdminPin = "123456")

    @Test
    fun tse_notEditingIsNeverDirty() {
        val state = TseSettingsUiState(config = config, isEditing = false, formState = TseConfigFormState(keepAliveIntervalSeconds = "99"))
        assertFalse(state.hasUnsavedConfigEdits())
    }

    @Test
    fun tse_editingWithoutChangesIsClean() {
        val state =
            TseSettingsUiState(
                config = config,
                isEditing = true,
                formState = TseConfigFormState.fromConfig(config),
            )
        assertFalse(state.hasUnsavedConfigEdits())
    }

    @Test
    fun tse_editingWithChangesIsDirty_andUndoIsClean() {
        val form = TseConfigFormState.fromConfig(config)
        val edited = TseSettingsUiState(config = config, isEditing = true, formState = form.copy(keepAliveIntervalSeconds = "45"))
        assertTrue(edited.hasUnsavedConfigEdits())
        assertFalse(edited.copy(formState = form).hasUnsavedConfigEdits())
    }

    @Test
    fun tse_noStoredConfigIsNeverDirty() {
        assertFalse(TseSettingsUiState(config = null, isEditing = true).hasUnsavedConfigEdits())
    }
}
