package org.example.project.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannelManager {
    const val CHANNEL_ORDERS = "pos_orders"
    const val CHANNEL_SYSTEM = "pos_system"
    const val CHANNEL_CONNECTION = "pos_connection"

    fun createChannels(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ordersChannel =
            NotificationChannel(
                CHANNEL_ORDERS,
                "Order Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Billing requests, authorization approvals"
                enableVibration(true)
            }

        val systemChannel =
            NotificationChannel(
                CHANNEL_SYSTEM,
                "System Alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "TSE status, data changes"
            }

        val connectionChannel =
            NotificationChannel(
                CHANNEL_CONNECTION,
                "Connection Status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent connection status"
                setShowBadge(false)
            }

        notificationManager.createNotificationChannels(
            listOf(ordersChannel, systemChannel, connectionChannel),
        )
    }
}
