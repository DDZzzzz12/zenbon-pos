package org.example.project.admin.features.settings

import org.example.project.admin.features.settings.presentation.sections.SystemSettingsField
import org.example.project.admin.features.settings.presentation.sections.SystemSettingsState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemSettingsStateTest {
    private val saved =
        SystemSettingsState(
            startPageDefault = "admin",
            savedStartPageDefault = "admin",
            availableTargets = listOf("none", "admin"),
        )

    @Test
    fun unchangedAvailableTarget_isNeitherDirtyNorFlagged() {
        assertFalse(saved.startPageUnavailable)
        assertFalse(saved.isDirty)
        assertFalse(saved.canSave)
    }

    @Test
    fun unavailableSavedTarget_isDirtyAndPressableButCarriesAFieldError() {
        val state = saved.copy(startPageDefault = "pos", savedStartPageDefault = "pos")
        assertTrue(state.startPageUnavailable)
        assertTrue(state.isDirty)
        // Validity no longer disables Save; the save() refusal is pinned in SystemSettingsViewModelTest.
        assertTrue(state.canSave)
        assertTrue(SystemSettingsField.START_PAGE in state.fieldErrors)
        assertFalse(state.copy(isLoading = true).canSave)
    }

    @Test
    fun devModeHint_showsOnlyWhenServedWhileStoredOffAndUnlocked() {
        assertFalse(saved.apiDocsOnInDevMode)
        assertTrue(saved.copy(apiDocsEnabled = true).apiDocsOnInDevMode)
        assertFalse(saved.copy(apiDocsEnabled = true, apiDocsSetting = true).apiDocsOnInDevMode)
        assertFalse(saved.copy(apiDocsEnabled = true, apiDocsLocked = true).apiDocsOnInDevMode)
    }

    @Test
    fun choosingAnAvailableTarget_clearsTheFlagAndEnablesSave() {
        val state = saved.copy(startPageDefault = "none", savedStartPageDefault = "pos")
        assertFalse(state.startPageUnavailable)
        assertTrue(state.canSave)
        assertFalse(state.copy(isSaving = true).canSave)
    }
}
