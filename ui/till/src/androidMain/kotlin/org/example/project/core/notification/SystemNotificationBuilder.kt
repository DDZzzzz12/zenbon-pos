package org.example.project.core.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.example.project.composeapp.R
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.NotificationKeys

class SystemNotificationBuilder(
    private val context: Context,
) {
    fun buildFromMessage(
        message: NotificationDto.NotificationMessage,
        language: Language,
    ): NotificationData? =
        when (message) {
            is NotificationDto.BillingRequestNotificationDto ->
                NotificationData(
                    id = "billing_${message.tableId}".hashCode(),
                    channelId = NotificationChannelManager.CHANNEL_ORDERS,
                    title = getString(NotificationKeys.billingRequestTitle, language),
                    body =
                        if (message.requestedByStation) {
                            getString(NotificationKeys.billingRequestMessageStation, language, message.tableNumber)
                        } else {
                            // Both slots: {0} is the employee number, {1} the table. Passing only the
                            // table number filled {0} with it and left a literal "{1}" on screen.
                            getString(
                                NotificationKeys.billingRequestMessage,
                                language,
                                message.requestingEmployeeNumber,
                                message.tableNumber,
                            )
                        },
                    intentType = "billing_request",
                    intentEntityId = message.tableId.toString(),
                )
//            is NotificationDto.AuthorizationNotificationDto -> NotificationData(
//                id = "auth_${message.notificationType}".hashCode(),
//                channelId = NotificationChannelManager.CHANNEL_ORDERS,
//                title = getString(NotificationKeys.systemAuthorizationTitle, language),
//                body = getString(NotificationKeys.systemAuthorizationBody, language),
//                intentType = "authorization",
//                intentEntityId = null
//            )
//            is NotificationDto.TableStatusUpdateDto -> NotificationData(
//                id = "table_${message.tableId}".hashCode(),
//                channelId = NotificationChannelManager.CHANNEL_ORDERS,
//                title = getString(NotificationKeys.tableStatusChanged, language),
//                body = getString(NotificationKeys.tableStatusChanged, language),
//                intentType = "table_status",
//                intentEntityId = message.tableId.toString()
//            )
//            is NotificationDto.DataChangeNotificationDto -> NotificationData(
//                id = "data_${message.entityType.name}".hashCode(),
//                channelId = NotificationChannelManager.CHANNEL_SYSTEM,
//                title = getString(NotificationKeys.systemDataChangeTitle, language),
//                body = getString(NotificationKeys.systemDataChangeBody, language, message.entityType.name),
//                intentType = "data_change",
//                intentEntityId = null
//            )
//            is NotificationDto.TseStatusUpdateDto -> NotificationData(
//                id = "tse_status".hashCode(),
//                channelId = NotificationChannelManager.CHANNEL_SYSTEM,
//                title = getString(NotificationKeys.tseWarningTitle, language),
//                body = getString(NotificationKeys.systemTseAlertBody, language),
//                intentType = "tse_alert",
//                intentEntityId = null
//            )
//            is NotificationDto.WebSocketErrorDto -> NotificationData(
//                id = "ws_error".hashCode(),
//                channelId = NotificationChannelManager.CHANNEL_SYSTEM,
//                title = getString(NotificationKeys.systemConnectionErrorTitle, language),
//                body = getString(NotificationKeys.systemConnectionErrorBody, language),
//                intentType = "error",
//                intentEntityId = null
//            )
            else -> null
        }

    fun buildNotification(data: NotificationData): Notification {
        // Resolve the launcher activity from the app module without a direct
        // class reference, since composeApp is an Android library and
        // MainActivity lives in :ui-till-android.
        val intent =
            (
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("notification_type", data.intentType)
                data.intentEntityId?.let { putExtra("notification_entity_id", it) }
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                data.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val isHighPriority = data.channelId == NotificationChannelManager.CHANNEL_ORDERS

        return NotificationCompat
            .Builder(context, data.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(data.title)
            .setContentText(data.body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(
                if (isHighPriority) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            ).apply {
                if (isHighPriority) {
                    setDefaults(NotificationCompat.DEFAULT_ALL)
                    setCategory(NotificationCompat.CATEGORY_MESSAGE)
                }
            }.build()
    }
}
