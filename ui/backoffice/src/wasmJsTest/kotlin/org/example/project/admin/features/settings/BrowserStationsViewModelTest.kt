package org.example.project.admin.features.settings

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.admin.features.settings.presentation.sections.BrowserStationsViewModel
import org.example.project.admin.features.settings.presentation.sections.isUnsecuredRemoteConnection
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserStationsViewModelTest {
    @Test
    fun refresh_populatesTheEnabledRoles() =
        runTest {
            val config = FakeShopConfigApiClient().apply { browserRoles = listOf("CASHIER") }
            val model = BrowserStationsViewModel(config)
            model.refresh()
            assertEquals(setOf("CASHIER"), model.state.enabledRoles)
            assertEquals(false, model.state.isLoading)
            assertNull(model.state.error)
        }

    @Test
    fun toggleThenSave_sendsExactlyTheSelectedRoles() =
        runTest {
            val config = FakeShopConfigApiClient()
            val model = BrowserStationsViewModel(config)
            model.refresh()
            model.setRoleEnabled(TerminalRole.CASHIER, true)
            assertTrue(model.state.isDirty)
            model.save()
            assertEquals(listOf(listOf("CASHIER")), config.browserStationsRequests.map { it.roles })
            assertEquals(setOf("CASHIER"), model.state.enabledRoles)
            assertEquals(setOf("CASHIER"), model.state.savedRoles)
            assertFalse(model.state.isDirty)
            assertEquals(AdminStringKeys.SettingsBrowserStationsSaved, model.state.successMessage)
        }

    @Test
    fun switchingARoleOff_dropsItFromTheRequest() =
        runTest {
            val config = FakeShopConfigApiClient().apply { browserRoles = listOf("CASHIER", "ORDER_STATION") }
            val model = BrowserStationsViewModel(config)
            model.refresh()
            model.setRoleEnabled(TerminalRole.CASHIER, false)
            model.save()
            assertEquals(listOf(listOf("ORDER_STATION")), config.browserStationsRequests.map { it.roles })
        }

    @Test
    fun forbiddenSave_keepsTheSavedStateAndSurfacesATypedError() =
        runTest {
            val config = FakeShopConfigApiClient().apply { browserRoles = listOf("ORDER_STATION") }
            val model = BrowserStationsViewModel(config)
            model.refresh()
            config.browserStationsError = PosApiException(PosErrorCode.AUTH_FORBIDDEN, 403, "nope", null)
            model.setRoleEnabled(TerminalRole.CASHIER, true)
            model.save()
            assertIs<UiError.FromServer>(model.state.error)
            assertEquals(setOf("ORDER_STATION"), model.state.savedRoles)
            assertTrue(config.browserStationsRequests.isEmpty())
            assertNull(model.state.successMessage)
            assertEquals(false, model.state.isSaving)
        }

    @Test
    fun saveWithoutChanges_callsNothing() =
        runTest {
            val config = FakeShopConfigApiClient()
            val model = BrowserStationsViewModel(config)
            model.refresh()
            model.save()
            assertTrue(config.browserStationsRequests.isEmpty())
        }

    @Test
    fun section_needsConfigWrite() {
        assertFalse(SettingsSection.BROWSER_STATIONS.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_READ))))
        assertTrue(SettingsSection.BROWSER_STATIONS.isVisibleTo(SetAccessPolicy(setOf(Capability.CONFIG_WRITE))))
    }

    @Test
    fun insecureHint_onlyForPlainHttpOnANonLoopbackHost() {
        assertTrue(isUnsecuredRemoteConnection("http:", "192.168.1.20"))
        assertFalse(isUnsecuredRemoteConnection("https:", "192.168.1.20"))
        assertFalse(isUnsecuredRemoteConnection("http:", "localhost"))
        assertFalse(isUnsecuredRemoteConnection("http:", "127.0.0.1"))
        assertFalse(isUnsecuredRemoteConnection("http:", "[::1]"))
    }
}
