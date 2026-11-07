package org.example.project.admin.features.settings

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.admin.features.settings.presentation.sections.SystemSettingsField
import org.example.project.admin.features.settings.presentation.sections.SystemSettingsViewModel
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemSettingsViewModelTest {
    @Test
    fun loadsThenSavesBothSettings() =
        runTest {
            val api = FakeWebAccessApiClient()
            val model = SystemSettingsViewModel(api)
            model.refresh()
            assertFalse(model.state.isLoading)
            assertFalse(model.state.canSave)
            model.setApiDocs(true)
            model.setStartPageDefault("admin")
            assertTrue(model.state.canSave)
            model.save()
            assertEquals(listOf(ShopConfigurationDto.UpdateWebAccessRequestDto(true, "admin")), api.updates)
            assertFalse(model.state.isDirty)
            assertTrue(model.state.apiDocsEnabled)
            assertEquals("admin", model.state.savedStartPageDefault)
            assertEquals(AdminStringKeys.SettingsSystemSaved, model.state.successMessage)
        }

    @Test
    fun lockedSwitchIsNotChangeable() =
        runTest {
            val api =
                FakeWebAccessApiClient().apply {
                    stored = stored.copy(apiDocsLockedByServerConfig = true, apiDocsEnabled = true)
                }
            val model = SystemSettingsViewModel(api)
            model.refresh()
            assertTrue(model.state.apiDocsLocked)
            model.setApiDocs(true)
            assertFalse(model.state.apiDocsSetting)
            assertFalse(model.state.isDirty)
        }

    @Test
    fun lockedSwitch_stillLetsTheStartPageBeSaved_withTheStoredSetting() =
        runTest {
            val api = FakeWebAccessApiClient().apply { stored = stored.copy(apiDocsLockedByServerConfig = true) }
            val model = SystemSettingsViewModel(api)
            model.refresh()
            model.setStartPageDefault("admin")
            model.save()
            assertEquals(listOf(ShopConfigurationDto.UpdateWebAccessRequestDto(false, "admin")), api.updates)
        }

    @Test
    fun posIsNotOfferedWhileUnavailable() =
        runTest {
            val model = SystemSettingsViewModel(FakeWebAccessApiClient())
            model.refresh()
            model.setStartPageDefault("pos")
            assertEquals("none", model.state.startPageDefault)
            assertFalse(model.state.canSave)
        }

    @Test
    fun posIsChoosableWhenTheServerOffersIt() =
        runTest {
            val api =
                FakeWebAccessApiClient().apply {
                    stored = stored.copy(availableStartPageTargets = listOf("none", "admin", "pos"))
                }
            val model = SystemSettingsViewModel(api)
            model.refresh()
            model.setStartPageDefault("pos")
            model.save()
            assertEquals("pos", api.updates.single().startPageDefault)
        }

    @Test
    fun failedSave_keepsTheSavedStateAndSurfacesATypedError() =
        runTest {
            val api = FakeWebAccessApiClient()
            val model = SystemSettingsViewModel(api)
            model.refresh()
            api.updateError = PosApiException(PosErrorCode.VALIDATION_FAILED, 400, "nope", null)
            model.setStartPageDefault("admin")
            model.save()
            assertIs<UiError.FromServer>(model.state.error)
            assertEquals("none", model.state.savedStartPageDefault)
            assertTrue(model.state.isDirty)
            assertFalse(model.state.isSaving)
            assertNull(model.state.successMessage)
        }

    @Test
    fun saveWithoutChanges_callsNothing() =
        runTest {
            val api = FakeWebAccessApiClient()
            val model = SystemSettingsViewModel(api)
            model.refresh()
            model.save()
            assertTrue(api.updates.isEmpty())
        }

    @Test
    fun storedTargetNoLongerAvailable_keepsTheDraftShowsTheHintAndRefusesSaveUntilAValidChoice() =
        runTest {
            val api = FakeWebAccessApiClient().apply { stored = stored.copy(startPageDefault = "pos") }
            val model = SystemSettingsViewModel(api)
            model.refresh()
            assertEquals("pos", model.state.startPageDefault)
            assertTrue(model.state.startPageUnavailable)
            assertTrue(model.state.isDirty)
            // Option A: Save stays pressable; pressing it validates and reveals the start page instead.
            assertTrue(model.state.canSave)
            assertEquals(
                mapOf(SystemSettingsField.START_PAGE to AdminStringKeys.SettingsSystemStartPageUnavailable),
                model.state.fieldErrors,
            )
            model.setApiDocs(true)
            model.save()
            assertTrue(api.updates.isEmpty())
            assertEquals(1, model.state.validationAttempt)
            assertNull(model.state.error)
            model.save()
            assertEquals(2, model.state.validationAttempt)
            assertTrue(api.updates.isEmpty())
            model.setStartPageDefault("admin")
            assertFalse(model.state.startPageUnavailable)
            assertTrue(model.state.canSave)
            model.save()
            assertEquals(listOf(ShopConfigurationDto.UpdateWebAccessRequestDto(true, "admin")), api.updates)
            assertFalse(model.state.isDirty)
        }

    @Test
    fun section_needsConfigWrite() {
        assertFalse(SettingsSection.SYSTEM.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_READ))))
        assertTrue(SettingsSection.SYSTEM.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_WRITE))))
    }
}
