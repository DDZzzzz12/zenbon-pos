package org.example.project.core.notification

data class NotificationData(
    val id: Int,
    val channelId: String,
    val title: String,
    val body: String,
    val intentType: String,
    val intentEntityId: String?,
)
