package org.example.project.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Reports a usable network to [NetworkAvailabilityNotifier], so the notification stream reconnects within seconds of
 * Wi-Fi returning instead of waiting out [ReconnectPolicy]'s slow retry interval (E2E-2026-09-15 N2).
 *
 * Requires the `ACCESS_NETWORK_STATE` permission. [start] and [stop] must each be called once, from a lifecycle that
 * guarantees it: [org.example.project.core.notification.PosNotificationService] registers in `onCreate` and unregisters
 * in `onDestroy`, which Android calls exactly once per service instance — unlike `onStartCommand`, which a repeated
 * start would run again and so would leak a callback per start.
 */
class AndroidNetworkMonitor(
    context: Context,
    private val notifier: NetworkAvailabilityNotifier,
) {
    /**
     * Declared nullable rather than left as the platform type Kotlin infers: `getSystemService(Class)` annotates only
     * its parameter `@NonNull` and says nothing about its return, so the absent-service case is real and is better
     * visible here than as an NPE at the call site.
     */
    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifier.onNetworkAvailable()
        }

    fun start() {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager?.registerNetworkCallback(request, callback)
    }

    /** Tolerates never having registered: `unregisterNetworkCallback` throws on an unknown callback, and a monitor whose [start] found no service must still stop cleanly. */
    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
