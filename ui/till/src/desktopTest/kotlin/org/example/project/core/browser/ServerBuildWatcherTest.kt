package org.example.project.core.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerBuildWatcherTest {
    @Test
    fun `same version does not flag an update`() {
        val w = ServerBuildWatcher(bundleVersion = "1.1.0")
        w.onServerHello("1.1.0")
        assertFalse(w.updateAvailable.value)
    }

    @Test
    fun `different version flags an update`() {
        val w = ServerBuildWatcher(bundleVersion = "1.1.0")
        w.onServerHello("1.1.1")
        assertTrue(w.updateAvailable.value)
    }

    @Test
    fun `flag clears when a later hello matches the bundle again`() {
        val w = ServerBuildWatcher(bundleVersion = "1.1.0")
        w.onServerHello("1.1.1")
        assertTrue(w.updateAvailable.value)
        w.onServerHello("1.1.0")
        assertFalse(w.updateAvailable.value)
        assertFalse(w.shouldReloadNow(busy = false))
    }

    @Test
    fun `reload only when idle`() {
        val w = ServerBuildWatcher("1.1.0")
        w.onServerHello("1.1.1")
        assertFalse(w.shouldReloadNow(busy = true))
        assertTrue(w.shouldReloadNow(busy = false))
    }

    @Test
    fun `native clients never flag`() {
        val w = ServerBuildWatcher("1.1.0", enabled = false)
        w.onServerHello("9.9.9")
        assertFalse(w.updateAvailable.value)
        w.onServerHello("1.1.0")
        assertFalse(w.updateAvailable.value)
    }

    @Test
    fun `only the dashboard root counts as idle`() {
        assertFalse(ServerBuildWatcher.isBusyRoute(null))
        assertFalse(ServerBuildWatcher.isBusyRoute("org.example.project.features.main.presentation.navigation.Dashboard"))
        assertFalse(ServerBuildWatcher.isBusyRoute("org.example.project.features.dashboard.presentation.navigation.DashboardMain"))
        assertTrue(ServerBuildWatcher.isBusyRoute("org.example.project.features.dashboard.presentation.navigation.TableOrder/{tableId}"))
        assertTrue(ServerBuildWatcher.isBusyRoute("org.example.project.features.main.presentation.navigation.Reports"))
    }

    @Test
    fun `an open dashboard screen counts as busy`() {
        val w = ServerBuildWatcher(bundleVersion = "1.1.0")
        w.onServerHello("1.1.1")
        assertTrue(w.shouldReloadNow(busy = w.innerBusy.value))
        w.setInnerBusy(true)
        assertFalse(w.shouldReloadNow(busy = w.innerBusy.value), "a half-built order must not be thrown away by an update")
        w.setInnerBusy(false)
        assertTrue(w.shouldReloadNow(busy = w.innerBusy.value))
    }
}
