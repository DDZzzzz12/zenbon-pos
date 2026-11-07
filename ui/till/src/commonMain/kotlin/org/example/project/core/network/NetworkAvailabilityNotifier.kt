package org.example.project.core.network

/**
 * Lets a platform tell the notification stream that connectivity returned, short-circuiting the
 * slow retry interval (see [ReconnectPolicy]) into an immediate attempt.
 */
class NetworkAvailabilityNotifier(
    private val connection: NotificationConnection,
) {
    fun onNetworkAvailable() = connection.requestImmediateReconnect()
}
