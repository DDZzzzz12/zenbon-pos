package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object NotificationKeys {
    val panelTitle = StringKey("notification.panel_title")
    val allCaughtUp = StringKey("notification.all_caught_up")
    val markAllAsRead = StringKey("notification.mark_all_as_read")
    val clearAll = StringKey("notification.clear_all")
    val reconnect = StringKey("notification.reconnect")
    val dismiss = StringKey("notification.dismiss")
    val billingRequestTitle = StringKey("notification.billing_request_title")
    val billingRequestMessage = StringKey("notification.billing_request_message")

    // Same event raised by a login-less order station. The shared device account has no person
    // behind it, so naming its employee number ("Employee TERMINAL-DEVICE") tells the operator
    // something false. {0} = tableNumber.
    val billingRequestMessageStation = StringKey("notification.billing_request_message_station")
    val tseConnected = StringKey("notification.tse_connected")
    val tseWarningTitle = StringKey("notification.tse_warning_title")
    val tseDisabledWarning = StringKey("notification.tse_disabled_warning")
    val tseSimulationWarning = StringKey("notification.tse_simulation_warning")

    // warns that an ERS-User who does not install updates in time runs into legal problems.
    val tseFirmwareUpdateTitle = StringKey("notification.tse_firmware_update_title")
    val tseFirmwareUpdateMessage = StringKey("notification.tse_firmware_update_message")
    val tseCertExpiryTitle = StringKey("notification.tse_cert_expiry_title")
    val tseCertExpiredWarning = StringKey("notification.tse_cert_expired_warning")
    val tseCertExpiryWarning = StringKey("notification.tse_cert_expiry_warning")
    val tableStatusChanged = StringKey("notification.table_status_changed")

    // Filter keys
    val filterAll = StringKey("notification.filter_all")
    val filterUnread = StringKey("notification.filter_unread")
    val filterBilling = StringKey("notification.filter_billing")
    val filterTables = StringKey("notification.filter_tables")
    val filterOrders = StringKey("notification.filter_orders")
    val filterSystem = StringKey("notification.filter_system")

    // Detail panel
    val selectNotification = StringKey("notification.select_notification")
    val statusRead = StringKey("notification.status_read")
    val statusUnread = StringKey("notification.status_unread")
    val goToBilling = StringKey("notification.go_to_billing")

    // Connection status
    val connected = StringKey("notification.connected")
    val connecting = StringKey("notification.connecting")
    val disconnected = StringKey("notification.disconnected")
    val connectionFailed = StringKey("notification.connection_failed")

    // Toast/dialog titles
    val connectionErrorTitle = StringKey("notification.connection_error_title")
    val authenticationErrorTitle = StringKey("notification.authentication_error_title")
    val websocketTitle = StringKey("notification.websocket_title")
    val dataChangeTitle = StringKey("notification.data_change_title")
    val connectionErrorMessage = StringKey("notification.connection_error_message")
    val noReasonProvided = StringKey("notification.no_reason_provided")

    // Notification-stream failures. One key per distinct failure the connection layer can report,
    // so no developer text ever reaches the dialog (see NotificationConnection.AuthFailureReason).
    val authErrorSessionRenewalFailed = StringKey("notification.auth_error_session_renewal_failed")
    val authErrorNoSession = StringKey("notification.auth_error_no_session")
    val connectionErrorRetriesExhausted = StringKey("notification.connection_error_retries_exhausted")
    val connectionErrorStreamLost = StringKey("notification.connection_error_stream_lost")
    val connectionErrorUnavailable = StringKey("notification.connection_error_unavailable")
    val messageProcessingError = StringKey("notification.message_processing_error")

    // Android background-execution prompt, shown after a person login before the battery settings open.
    val backgroundExecutionTitle = StringKey("notification.background_execution_title")
    val backgroundExecutionMessage = StringKey("notification.background_execution_message")
    val backgroundExecutionSetUpNow = StringKey("notification.background_execution_set_up_now")
    val backgroundExecutionLater = StringKey("notification.background_execution_later")

    // System notification keys (Android foreground service)
    val systemConnectionActive = StringKey("notification.system_connection_active")
    val systemConnectionActiveBody = StringKey("notification.system_connection_active_body")
    val systemAuthorizationTitle = StringKey("notification.system_authorization_title")
    val systemAuthorizationBody = StringKey("notification.system_authorization_body")
    val systemDataChangeTitle = StringKey("notification.system_data_change_title")
    val systemDataChangeBody = StringKey("notification.system_data_change_body")
    val systemTseAlertBody = StringKey("notification.system_tse_alert_body")
    val systemConnectionErrorTitle = StringKey("notification.system_connection_error_title")
    val systemConnectionErrorBody = StringKey("notification.system_connection_error_body")
}
