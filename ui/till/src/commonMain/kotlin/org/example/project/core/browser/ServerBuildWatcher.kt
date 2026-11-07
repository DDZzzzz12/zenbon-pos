package org.example.project.core.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices that the server was upgraded underneath a long-lived browser tab.
 *
 * A browser station keeps running the wasm bundle it loaded, so after a server upgrade it would
 * keep talking to a newer API with stale code. The server announces its version as the first SSE
 * frame of every connection; a differing version flags an update, and a later matching one (rollback) clears it. Native clients ship with their
 * installer and never flag ([enabled] = false).
 */
class ServerBuildWatcher(
    private val bundleVersion: String,
    private val enabled: Boolean = true,
    private val reloadGuard: ReloadLoopGuard? = null,
) {
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _innerBusy = MutableStateFlow(false)

    /**
     * True while a screen of the dashboard's own navigation (an open table order, billing, history …)
     * is on top. The main navigation never sees those: they live in a second NavHost under the one
     * "Dashboard" destination, so [isBusyRoute] alone called a half-built order "idle".
     */
    val innerBusy: StateFlow<Boolean> = _innerBusy.asStateFlow()

    fun setInnerBusy(busy: Boolean) {
        _innerBusy.value = busy
    }

    fun onServerHello(serverVersion: String) {
        if (enabled) _updateAvailable.value = serverVersion != bundleVersion
    }

    /**
     * The automatic reload decision: idle, an update pending, and the loop guard (if any) agrees.
     * Calling it records the attempt, so call it once per intended reload.
     */
    fun consumeAutoReload(busy: Boolean): Boolean = shouldReloadNow(busy) && (reloadGuard?.tryAcquire() ?: true)

    /** Reload on idle only: an open order or payment must never be thrown away by an update. */
    fun shouldReloadNow(busy: Boolean): Boolean = _updateAvailable.value && !busy

    companion object {
        /**
         * True when a screen other than the dashboard root is on top of the main navigation (a table
         * order, billing, history, reports, settings ...). The dashboard root is the only idle place;
         * a null route is the not-yet-resolved start destination, which is the dashboard.
         */
        fun isBusyRoute(route: String?): Boolean = route != null && !route.contains("Dashboard")
    }
}
