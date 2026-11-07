package org.example.project.features.notifications.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.isStation
import org.example.project.core.auth.personLeft
import org.example.project.core.browser.ServerBuildWatcher
import org.example.project.core.data.cache.TableStatusCacheWriter
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.network.NotificationConnection
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.OverlayEventBus
import org.example.project.core.presentation.strings.approvalOperationLabel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ApprovalKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.EnumKeys
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.isDesktop
import org.example.project.features.notifications.presentation.NotificationConnectionErrorKeys
import org.example.project.features.notifications.presentation.state.ConnectionState
import org.example.project.features.notifications.presentation.state.NotificationUiState
import org.example.project.features.notifications.presentation.state.TseConnectionState
import org.example.project.features.rotation.RotationNextStore

/**
 * ViewModel for managing real-time notifications.
 *
 * Connects to WebSocket and handles incoming notifications,
 * providing UI state for notification display and management.
 *
 * @property notificationConnection Transport-agnostic notification connection
 * @property languageManager Manager for application language settings
 * @property syncService Service for synchronizing data with the server
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class NotificationViewModel(
    private val notificationConnection: NotificationConnection,
    private val languageManager: LanguageManager,
    private val syncService: SyncService,
    /**
     * Write-through to the local table cache, or null on clients that have no local cache (the
     * wasm browser preview builds no `PosDatabase`). Null means "nothing to keep live", not
     * "writing failed" — the emit above still happens either way. Mirrors the optional
     * `SyncDataManager` on `SettingManager`.
     */
    private val tableStatusCacheWriter: TableStatusCacheWriter?,
    private val overlayEventBus: OverlayEventBus,
    /**
     * Tells a login-less station apart from a person ([isStation]). A station has nobody to dismiss a
     * modal, so warnings it must still see are raised as toasts.
     */
    private val tokenStore: TokenStore,
    /** Receives the server's version hello; the default never flags (native clients, tests). */
    private val serverBuildWatcher: ServerBuildWatcher = ServerBuildWatcher(bundleVersion = "", enabled = false),
    /** Receives the rotation's next-employee updates; tests that don't care get a private instance. */
    private val rotationNextStore: RotationNextStore = RotationNextStore(),
    /**
     * Tells who is signed in ([PermissionManager.signedInPerson]). This ViewModel is root-scoped and
     * outlives a sign-out, so the previous person's notifications are dropped when that person
     * leaves; see [clearWhenThePersonLeaves]. Null only in tests that don't care.
     */
    private val permissionManager: PermissionManager? = null,
) : ViewModel() {
    companion object {
        /**
         * Overlay id of the "connection to the server lost" error, so the toast can be withdrawn the
         * moment the stream is back instead of lingering over a working connection.
         */
        const val CONNECTION_ERROR_OVERLAY_ID = "notification-connection-error"
    }

    private val logger = Logger.withTag("NotificationViewModel")

    // Single source of truth for all UI state
    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private val _notificationEvents = MutableSharedFlow<NotificationUiEvent>()
    val notificationEvents = _notificationEvents.asSharedFlow()

    private val _authError = MutableStateFlow<Boolean>(false)
    val authError: StateFlow<Boolean> = _authError.asStateFlow()

    /**
     * Whether this instance has a TSE status to compare the next one with, seeded from the connection or
     * received live. Not the same as the footer reading [TseConnectionState.Unknown]: a live message maps to
     * that too, and a change out of it is a real transition. Declared before `init`, which sets it.
     */
    private var hasKnownTseStatus = false

    /** Content keys of the certificate-expiry warnings this instance has already raised. */
    private val shownTseWarnings = mutableSetOf<String>()

    init {
        // Drop any error retained by the connection from a previous session BEFORE we
        // subscribe below. The connection is a process-wide singleton whose `errors`
        // flow replays its last value; without this, an expired-token AuthenticationFailed
        // from the prior session would be replayed to this freshly recreated ViewModel
        // (created after the user logs back in) and immediately log them out again.
        notificationConnection.clearErrors()
        // Observe incoming messages BEFORE reading the latestTseStatus snapshot below: viewModelScope
        // dispatches on Dispatchers.Main.immediate, so this subscribes synchronously before init
        // continues, when constructed on the main thread (koinViewModel in composition). Reading the
        // snapshot first would leave a window where a status arriving between the read and the
        // subscribe is neither in the seed nor delivered live.
        observeMessages()
        // Start from the TSE status the stream already delivered, if any. The server sends it only on
        // change and once per new stream, and this ViewModel is re-created on every MainScreen mount
        // while the process-wide stream stays open — without the seed the footer read "TSE Unbekannt"
        // until the next change. Silent on purpose: an earlier instance already announced this state,
        // and it becomes the previous state the transition warnings in handleTseStatusUpdate compare to.
        notificationConnection.latestTseStatus.value?.let { status ->
            hasKnownTseStatus = true
            _uiState.update { it.copy(tseConnectionState = status.toTseConnectionState()) }
        }
        // Observe WebSocket connection state
        observeConnectionState()
        // Observe errors
        observeErrors()
        // Drop the previous person's notifications when they sign out or are replaced
        clearWhenThePersonLeaves()
        // Auto-connect to notifications (desktop only — Android uses background service)
        if (isDesktop()) {
            connectToNotifications()
        }
    }

    /**
     * Drops what belonged to the person who just left: the notification list, its badge, the
     * selection and the open panel.
     *
     * This ViewModel is root-scoped (`MainScreen` obtains it with `koinViewModel()` and has no NavHost
     * entry above it), so it outlives a sign-out; without this the next person saw the previous
     * one's billing and approval requests. The stream and TSE state describe the device, not the
     * person, and stay. What the next person is entitled to arrives again from the server: the
     * reconciler replays parked requests on every connect.
     */
    private fun clearWhenThePersonLeaves() {
        viewModelScope.launch {
            val signedInPerson = permissionManager?.signedInPerson ?: return@launch
            signedInPerson.personLeft().collect {
                _uiState.update {
                    it.copy(
                        notifications = emptyList(),
                        unreadCount = 0,
                        selectedNotification = null,
                        showNotificationPanel = false,
                        successMessage = null,
                    )
                }
            }
        }
    }

    /**
     * Connect to WebSocket notification endpoint.
     */
    fun connectToNotifications(reset: Boolean = false) {
        // Clear stale auth error from previous session
        _authError.value = false
        viewModelScope.launch {
            try {
                if (reset) {
                    notificationConnection.resetReconnectAttempts()
                }
                logger.i { "NotificationViewModel.connectToNotifications: connecting to notification stream..." }
                notificationConnection.connect("api/notifications/stream")
            } catch (e: Exception) {
                logger.e(e) { "NotificationViewModel.connectToNotifications: failed to connect to notifications" }
                val lang = languageManager.currentLanguage.value
                overlayEventBus.dismissById(CONNECTION_ERROR_OVERLAY_ID)
                overlayEventBus.showError(
                    title = getString(NotificationKeys.connectionErrorTitle, lang),
                    message = getString(NotificationKeys.connectionErrorUnavailable, lang),
                    displayMode = DisplayMode.TOAST,
                    retryLabel = getString(CommonKeys.retry, lang),
                    retryAction = { connectToNotifications(reset = true) },
                    id = CONNECTION_ERROR_OVERLAY_ID,
                )
            }
        }
    }

    /**
     * Disconnect from WebSocket.
     */
    fun disconnect() {
        viewModelScope.launch {
            notificationConnection.disconnect()
        }
    }

    /**
     * Toggle notification panel visibility.
     */
    fun toggleNotificationPanel() {
        _uiState.update { it.copy(showNotificationPanel = !it.showNotificationPanel) }
    }

    /**
     * Show notification panel.
     */
    fun showNotificationPanel() {
        _uiState.update { it.copy(showNotificationPanel = true) }
    }

    /**
     * Hide notification panel.
     */
    fun hideNotificationPanel() {
        _uiState.update { it.copy(showNotificationPanel = false) }
    }

    /**
     * Mark a notification as read.
     */
    fun markAsRead(notificationId: String) {
        _uiState.update { currentState ->
            val updatedNotifications =
                currentState.notifications.map { notification ->
                    if (notification.id == notificationId && !notification.isRead) {
                        notification.copy(isRead = true)
                    } else {
                        notification
                    }
                }
            currentState.copy(
                notifications = updatedNotifications,
                unreadCount = updatedNotifications.count { !it.isRead },
            )
        }
    }

    /**
     * Mark all notifications as read.
     */
    fun markAllAsRead() {
        _uiState.update { currentState ->
            currentState.copy(
                notifications = currentState.notifications.map { it.copy(isRead = true) },
                unreadCount = 0,
            )
        }
    }

    /**
     * Clear all notifications.
     */
    fun clearAllNotifications() {
        _uiState.update { it.copy(notifications = emptyList(), unreadCount = 0) }
    }

    /**
     * Remove a specific notification from the list.
     *
     * @param notificationId ID of the notification to remove
     */
    fun removeNotification(notificationId: String) {
        _uiState.update { currentState ->
            val updatedNotifications = currentState.notifications.filter { it.id != notificationId }
            currentState.copy(
                notifications = updatedNotifications,
                unreadCount = updatedNotifications.count { !it.isRead },
            )
        }
        logger.i { "NotificationViewModel.removeNotification: removed notification $notificationId" }
    }

    /**
     * Select a notification for detail view.
     */
    fun selectNotification(notification: NotificationItem?) {
        _uiState.update { it.copy(selectedNotification = notification) }
        notification?.let { markAsRead(it.id) }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            notificationConnection.connectionState.collect { state ->
                val newConnectionState =
                    when (state) {
                        is NotificationConnection.ConnectionState.Disconnected -> ConnectionState.Disconnected
                        is NotificationConnection.ConnectionState.Connecting -> ConnectionState.Connecting
                        is NotificationConnection.ConnectionState.Connected -> ConnectionState.Connected
                        is NotificationConnection.ConnectionState.Failed -> ConnectionState.Failed
                    }
                _uiState.update { it.copy(connectionState = newConnectionState) }
                if (state is NotificationConnection.ConnectionState.Connected) {
                    // The stream is back: a "connection lost" toast still on screen now reads as a live fault.
                    overlayEventBus.dismissById(CONNECTION_ERROR_OVERLAY_ID)
                }
                logger.d { "NotificationViewModel.observeConnectionState: connection state changed $newConnectionState" }
            }
        }
    }

    /**
     * Observe incoming WebSocket messages.
     */
    private fun observeMessages() {
        viewModelScope.launch {
            notificationConnection.messages.collect { message ->
                logger.d { "NotificationViewModel.observeMessages: received message ${message.messageType}" }
                try {
                    handleWebSocketMessage(message)
                } catch (e: Exception) {
                    logger.e(e) { "handleWebSocketMessage failed for ${message.messageType}" }
                }
            }
        }
    }

    /**
     * Observe WebSocket errors.
     */
    private fun observeErrors() {
        viewModelScope.launch {
            notificationConnection.errors.collect { error ->
                logger.e { "NotificationViewModel.observeErrors: notification connection error - ${error.diagnostic}" }
                val lang = languageManager.currentLanguage.value
                when (error) {
                    is NotificationConnection.NotificationConnectionError.AuthenticationFailed -> {
                        overlayEventBus.showError(
                            title = getString(NotificationKeys.authenticationErrorTitle, lang),
                            message = getString(NotificationConnectionErrorKeys.messageKey(error.reason), lang),
                            displayMode = DisplayMode.DIALOG,
                        )
                        delay(500L)
                        _authError.emit(true)
                    }

                    is NotificationConnection.NotificationConnectionError.ConnectionFailed -> {
                        overlayEventBus.dismissById(CONNECTION_ERROR_OVERLAY_ID)
                        overlayEventBus.showError(
                            title = getString(NotificationKeys.connectionErrorTitle, lang),
                            message = getString(NotificationConnectionErrorKeys.messageKey(error.reason), lang),
                            displayMode = DisplayMode.TOAST,
                            retryLabel = getString(CommonKeys.retry, lang),
                            retryAction = { connectToNotifications(reset = true) },
                            id = CONNECTION_ERROR_OVERLAY_ID,
                        )
                    }

                    else -> {
                        // MessageError / DeserializationFailed / SendFailed carry an exception text
                        // for the log line above; it is developer English and must not be shown.
                        overlayEventBus.showWarning(
                            title = getString(NotificationKeys.connectionErrorTitle, lang),
                            message = getString(NotificationKeys.messageProcessingError, lang),
                            displayMode = DisplayMode.TOAST,
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle incoming WebSocket messages and convert to notifications.
     */
    private fun handleWebSocketMessage(message: NotificationDto.NotificationMessage) {
        when (message) {
            is NotificationDto.TableStatusUpdateDto -> {
                handleTableStatusUpdate(message)
            }

            is NotificationDto.DataChangeNotificationDto -> {
                handleDataChangeNotification(message)
            }

            is NotificationDto.TseStatusUpdateDto -> {
                handleTseStatusUpdate(message)
            }

            is NotificationDto.BillingRequestNotificationDto -> {
                handleBillingRequest(message)
            }

            is NotificationDto.AuthorizationNotificationDto -> {
                handleAuthorizationNotification(message)
            }

            is NotificationDto.ConnectionAckDto -> {
                logger.i { "WebSocket connection acknowledged: ${message.message}" }
            }

            is NotificationDto.ServerHelloDto -> {
                logger.i { "Server hello received: ${message.serverVersion}" }
                serverBuildWatcher.onServerHello(message.serverVersion)
            }

            is NotificationDto.TerminalSessionTerminatedDto -> {
                logger.i { "Terminal session terminated event received; handled by lifecycle handler" }
            }

            is NotificationDto.RotationNextUpdateDto -> rotationNextStore.apply(message)

            is NotificationDto.WebSocketErrorDto -> {
                overlayEventBus.showWarning(
                    title = getString(NotificationKeys.websocketTitle, languageManager.currentLanguage.value),
                    message = message.message,
                    displayMode = DisplayMode.TOAST,
                )
            }
        }
    }

    /**
     * Handle table status update message.
     *
     * Processes the update, updates local data, and sends acknowledgment.
     */
    private fun handleTableStatusUpdate(message: NotificationDto.TableStatusUpdateDto) {
        viewModelScope.launch {
            try {
                val currentLanguage = languageManager.currentLanguage.value

                val status = TableStatus.valueOf(message.status)

                // Emit UI event
                _notificationEvents.emit(
                    NotificationUiEvent.TableStatusChanged(
                        getString(NotificationKeys.tableStatusChanged, currentLanguage, getTableStatusText(status, currentLanguage)),
                        message.tableId,
                        status,
                        openAmount = message.openAmount,
                        employeeNumber = message.employeeNumber,
                        sessionId = message.sessionId,
                        isTraining = message.isTraining,
                    ),
                )

                // Write the volatile fields through to the cached table row (keeps cache live).
                // Isolated so a cache-write failure can never suppress the emit above.
                try {
                    tableStatusCacheWriter?.applyStatusUpdate(
                        tableId = message.tableId,
                        status = message.status,
                        employeeNumber = message.employeeNumber,
                        sessionId = message.sessionId,
                        isTraining = message.isTraining,
                        openAmount = message.openAmount,
                    )
                } catch (e: Exception) {
                    logger.e(e) { "TableStatus cache write-through failed for table ${message.tableId}" }
                }

                // Send acknowledgment to server (if message has messageId)
                // Note: Direct broadcasts may not have messageId, only queued messages do
                logger.d { "Table status update processed for table ${message.tableId}" }
            } catch (e: Exception) {
                logger.e(e) { "Failed to process table status update" }
            }
        }
    }

    /**
     * Handle TSE status update message.
     *
     * Maps the TSE status to a UI state and raises the TSE popups: a change into DISABLED, SIMULATION or
     * Connected, and the certificate-expiry and firmware-update warnings.
     */
    private fun handleTseStatusUpdate(message: NotificationDto.TseStatusUpdateDto) {
        val newState = message.toTseConnectionState()
        // Null until this instance knows a status. The server re-sends the current status on every new stream,
        // so the first one is this client learning the state, not a change: warning on it meant a popup on
        // every Main mount, reconnect and station start. The init seed makes a re-mounted instance compare
        // with the status the previous instance already announced.
        val previousState = _uiState.value.tseConnectionState.takeIf { hasKnownTseStatus }
        hasKnownTseStatus = true
        _uiState.update { it.copy(tseConnectionState = newState) }

        raiseModeChangeWarning(previousState, newState)
        if (newState == TseConnectionState.Connected) {
            raiseCertificateExpiryOrConnected(message.daysUntilCertExpiry, previousState)
        }
        if (message.firmwareUpdateAvailable) {
            raiseFirmwareUpdateWarning()
        }

        logger.i {
            "TSE status updated: connected=${message.connected}, mode=${message.mode}, daysUntilCertExpiry=${message.daysUntilCertExpiry}"
        }
    }

    /** A change into [newState] from a status this instance knew: not the first status, not a re-sent one. */
    private fun isTseTransition(
        previousState: TseConnectionState?,
        newState: TseConnectionState,
    ): Boolean = previousState != null && previousState != newState

    /** Warns on a change into DISABLED (nothing is signed) or SIMULATION (mocked, not fiscally compliant). */
    private fun raiseModeChangeWarning(
        previousState: TseConnectionState?,
        newState: TseConnectionState,
    ) {
        val warning =
            when (newState) {
                TseConnectionState.Disabled -> NotificationKeys.tseDisabledWarning
                TseConnectionState.Simulation -> NotificationKeys.tseSimulationWarning
                else -> return
            }
        if (!isTseTransition(previousState, newState)) return
        val lang = languageManager.currentLanguage.value
        raiseTseWarning(getString(NotificationKeys.tseWarningTitle, lang), getString(warning, lang))
    }

    /**
     * With the TSE connected: the certificate-expiry warning once expiry is 30 days or less away, at most once per
     * content, and the "TSE connected" toast on a change into Connected unless that warning was shown instead.
     */
    private fun raiseCertificateExpiryOrConnected(
        daysLeft: Long?,
        previousState: TseConnectionState?,
    ) {
        val lang = languageManager.currentLanguage.value
        if (daysLeft != null && daysLeft <= 30) {
            val expired = daysLeft <= 0
            val shown =
                raiseTseWarningOnce(
                    key = if (expired) "certificate-expired" else "certificate-expiry:$daysLeft",
                    title = getString(NotificationKeys.tseCertExpiryTitle, lang),
                    message =
                        if (expired) {
                            getString(NotificationKeys.tseCertExpiredWarning, lang)
                        } else {
                            getString(NotificationKeys.tseCertExpiryWarning, lang, daysLeft)
                        },
                )
            // An already-raised warning shows nothing, so it must not swallow the toast for a recovery
            // (Connected → offline → Connected with an unchanged day count).
            if (shown) return
        }
        if (!isTseTransition(previousState, TseConnectionState.Connected)) return
        overlayEventBus.showSuccess(
            title = getString(NotificationKeys.tseConnected, lang),
            message = getString(NotificationKeys.tseConnected, lang),
            displayMode = DisplayMode.TOAST,
            autoDismissMs = 1500,
        )
    }

    /**
     * Firmware updates, raised wherever the operator happens to be. The settings-tab indicator reaches only
     * someone who opens that tab; [AGD] 2.8 puts the duty to inform on the manufacturer and warns that an
     * ERS-User who does not install updates in time runs into legal problems. Raised on every status message that
     * carries it, deliberately not deduplicated like the certificate warning: the obligation does not lapse because
     * someone closed a dialog. Status messages are not periodic — the server sends one when the TSE status changes
     * and when a stream connects — so in practice it repeats on a TSE change and after every reconnect.
     */
    private fun raiseFirmwareUpdateWarning() {
        val lang = languageManager.currentLanguage.value
        raiseTseWarning(
            title = getString(NotificationKeys.tseFirmwareUpdateTitle, lang),
            message = getString(NotificationKeys.tseFirmwareUpdateMessage, lang),
        )
    }

    /** [raiseTseWarning], at most once per content [key] for this instance. Returns whether it was shown. */
    private fun raiseTseWarningOnce(
        key: String,
        title: String,
        message: String,
    ): Boolean {
        if (!shownTseWarnings.add(key)) return false
        raiseTseWarning(title, message)
        return true
    }

    /**
     * A TSE warning the operator must see. A person gets a modal to dismiss; a login-less station
     * ([isStation]), where nobody would dismiss it, gets a toast that clears itself after 5 s.
     */
    private fun raiseTseWarning(
        title: String,
        message: String,
    ) {
        val station = tokenStore.isStation
        overlayEventBus.showWarning(
            title = title,
            message = message,
            displayMode = if (station) DisplayMode.TOAST else DisplayMode.DIALOG,
            autoDismissMs = if (station) 5_000L else null,
        )
    }

    /** The footer state a TSE status message stands for. Shared by the live handler and the init seed. */
    private fun NotificationDto.TseStatusUpdateDto.toTseConnectionState(): TseConnectionState =
        when {
            mode == "DISABLED" -> TseConnectionState.Disabled
            mode == "SIMULATION" -> TseConnectionState.Simulation
            mode == "NORMAL" && !connected -> TseConnectionState.NormalOffline
            health == TseHealthState.MAINTENANCE -> TseConnectionState.Maintenance
            health == TseHealthState.DEGRADED -> TseConnectionState.Degraded
            connected && health == TseHealthState.HEALTHY -> TseConnectionState.Connected
            else -> TseConnectionState.Unknown
        }

    /**
     * Handle data change notification from server.
     *
     * Triggers a targeted re-sync of only the affected entity type
     * so clients stay up-to-date without a full sync cycle.
     */
    private fun handleDataChangeNotification(message: NotificationDto.DataChangeNotificationDto) {
        viewModelScope.launch {
            logger.i { "Data change notification: ${message.entityType} ${message.operation} ${message.entityId ?: "(batch)"}" }
            val lang = languageManager.currentLanguage.value

            // Apply a TARGETED delta in the background — NO blocking modal dialog. A failed
            // apply (e.g. an unknown/mismatched entity id) must never crash the app, so the
            // call is wrapped in runCatchingApi. The cursor is owned by the reconcile pull;
            // the live message's `seq` is a 0/hint and is never used to advance it.
            runCatchingApi {
                val entityId = message.entityId
                if (entityId != null) {
                    syncService.applyChangeDelta(
                        listOf(
                            ChangeFeedDto.EntryDto(
                                seq = message.seq,
                                entityType = message.entityType,
                                entityId = entityId,
                                operation = message.operation.toLogOperation(),
                            ),
                        ),
                    )
                } else {
                    // Batch hint (no id) — clear all local data for this type and re-sync.
                    syncService.clearLocalEntityData(message.entityType)
                    syncService.syncEntityByType(message.entityType)
                }
            }.onError { uiError ->
                logger.w { "Data-change apply failed for ${message.entityType} ${message.entityId}: $uiError" }
            }

            // Subtle, non-blocking signal that local data changed.
            _notificationEvents.emit(
                NotificationUiEvent.DataUpdated(
                    message = getString(NotificationKeys.dataChangeTitle, lang),
                ),
            )
        }
    }

    /**
     * Handle authorization notification from WebSocket.
     *
     * Processes APPROVAL_REQUEST, APPROVAL_RESPONSE, and APPROVAL_RESULT notification types.
     */
    private fun handleAuthorizationNotification(message: NotificationDto.AuthorizationNotificationDto) {
        val language = languageManager.currentLanguage.value

        when (message.notificationType) {
            "APPROVAL_REQUEST" -> {
                try {
                    val request = Json.decodeFromString<AuthDto.ApprovalRequestDto>(message.data)

                    val operationLabel = approvalOperationLabel(request.operation, language)

                    val id = "approval_${request.requestId}"
                    addNotification(
                        NotificationItem(
                            id = id,
                            type = NotificationType.APPROVAL_REQUEST,
                            title = getString(ApprovalKeys.approvalRequestTitle, language),
                            message = getString(ApprovalKeys.approvalRequestOperation, language, operationLabel),
                            timestamp = request.timestamp,
                            isRead = false,
                            data =
                                mapOf(
                                    "requestId" to request.requestId,
                                    "operation" to request.operation,
                                    "employeeId" to request.requestingEmployeeId,
                                    "deviceId" to request.deviceId,
                                    "requestJson" to message.data,
                                ),
                        ),
                    )

                    viewModelScope.launch {
                        _notificationEvents.emit(NotificationUiEvent.ApprovalRequestReceived(id, request))
                    }
                } catch (e: Exception) {
                    logger.e(e) { "Failed to parse approval request" }
                }
            }

            "APPROVAL_RESPONSE" -> {
                logger.d { "Received approval response" }
            }

            "APPROVAL_RESULT" -> {
                try {
                    val result = Json.decodeFromString<AuthDto.ApprovalResultNotificationDto>(message.data)
                    val localizedMessage =
                        when {
                            result.approved && result.success -> getString(ApprovalKeys.approvalActionCompleted, language)
                            result.approved -> getString(ApprovalKeys.approvalActionFailed, language, result.resultMessage)
                            result.errorCode == "APPROVAL_EXPIRED" -> getString(ApprovalKeys.approvalExpired, language)
                            else -> getString(ApprovalKeys.approvalDenied, language, result.resultMessage)
                        }
                    if (result.approved && result.success) {
                        overlayEventBus.showSuccess(localizedMessage, localizedMessage)
                        // The action ran on the server: tell the screen that asked for it, or it
                        // keeps showing what was true before the manager decided (R3-3).
                        viewModelScope.launch {
                            _notificationEvents.emit(NotificationUiEvent.ApprovalCompleted(result.operation))
                        }
                    } else {
                        overlayEventBus.showWarning(localizedMessage, localizedMessage)
                    }
                    overlayEventBus.dismissById("approval_${result.requestId}")
                    removeNotification("approval_${result.requestId}")
                } catch (e: Exception) {
                    logger.e(e) { "Failed to parse approval result" }
                }
            }

            else -> {
                logger.d { "Unhandled authorization notification type: ${message.notificationType}" }
            }
        }
    }

    /**
     * Handle billing request notification from WebSocket.
     */
    private fun handleBillingRequest(message: NotificationDto.BillingRequestNotificationDto) {
        viewModelScope.launch {
            val currentLanguage = languageManager.currentLanguage.value

            val notification =
                NotificationItem(
                    id = "billing_request_${message.tableId}_${message.timestamp}",
                    type = NotificationType.BILLING_REQUEST,
                    title = getString(NotificationKeys.billingRequestTitle, currentLanguage),
                    message =
                        if (message.requestedByStation) {
                            // The shared device account has no person behind it, so its employee
                            // number would read as a colleague's. The server decides this flag.
                            getString(NotificationKeys.billingRequestMessageStation, currentLanguage, message.tableNumber)
                        } else {
                            getString(
                                NotificationKeys.billingRequestMessage,
                                currentLanguage,
                                message.requestingEmployeeNumber,
                                message.tableNumber,
                            )
                        },
                    timestamp = message.timestamp,
                    data =
                        mapOf(
                            "tableId" to message.tableId.toString(),
                            "tableNumber" to message.tableNumber,
                        ),
                )

            addNotification(notification)
        }
    }

    /**
     * Add a new notification to the list.
     *
     * Ignores a notification whose id is already present. Ids are derived from the payload
     * (e.g. `billing_request_{tableId}_{timestamp}`), and the same payload legitimately arrives
     * twice: [org.example.project.core.network.NotificationReconciler] replays server-parked
     * notifications on every SSE connect, while a live push delivered the same one already. The
     * list is keyed by id, so a duplicate used to break the `LazyColumn` key contract and take
     * down the whole composition — de-duplicating here is what makes the replay safe.
     */
    private fun addNotification(notification: NotificationItem) {
        _uiState.update { currentState ->
            if (currentState.notifications.any { it.id == notification.id }) {
                return@update currentState
            }
            val updatedNotifications = listOf(notification) + currentState.notifications
            currentState.copy(
                notifications = updatedNotifications,
                unreadCount = updatedNotifications.count { !it.isRead },
            )
        }
        logger.i { "Added notification: ${notification.title}" }
    }

    /**
     * Re-emit an approval request event so MainScreen can show the dialog.
     */
    fun emitApprovalRequestEvent(
        notificationId: String,
        request: AuthDto.ApprovalRequestDto,
    ) {
        viewModelScope.launch {
            _notificationEvents.emit(NotificationUiEvent.ApprovalRequestReceived(notificationId, request))
        }
    }

    private fun getTableStatusText(
        status: TableStatus,
        language: Language,
    ): String =
        when (status) {
            TableStatus.FREE -> getString(EnumKeys.tableStatusFree, language)
            TableStatus.OCCUPIED -> getString(EnumKeys.tableStatusOccupied, language)
            TableStatus.RESERVED -> getString(EnumKeys.tableStatusReserved, language)
        }

    /**
     * Clean up when ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        if (isDesktop()) {
            viewModelScope.launch {
                notificationConnection.disconnect()
            }
        }
    }
}

/**
 * Maps a live [DataChangeOperation] (SSE hint) to the change-feed [LogOperation]
 * used by [ChangeFeedDto.EntryDto]. DEACTIVATED is treated as a DELETE locally.
 */
internal fun DataChangeOperation.toLogOperation(): LogOperation =
    when (this) {
        DataChangeOperation.CREATED -> LogOperation.CREATE
        DataChangeOperation.UPDATED -> LogOperation.UPDATE
        DataChangeOperation.DELETED -> LogOperation.DELETE
        DataChangeOperation.DEACTIVATED -> LogOperation.DELETE
    }

sealed interface NotificationUiEvent {
    data class TableStatusChanged(
        val message: String,
        val tableId: Long,
        val status: TableStatus,
        val openAmount: String? = null,
        val employeeNumber: String? = null,
        val sessionId: Long? = null,
        val isTraining: Boolean = false,
    ) : NotificationUiEvent

    data class DataUpdated(
        val message: String,
    ) : NotificationUiEvent

    data class BillingRequested(
        val tableId: Long,
        val tableNumber: String,
    ) : NotificationUiEvent

    data class ApprovalRequestReceived(
        val notificationId: String,
        val request: AuthDto.ApprovalRequestDto,
    ) : NotificationUiEvent

    /**
     * A request this device raised was approved AND the deferred action ran on the server, so
     * whatever screen asked for it is now looking at stale data.
     *
     * Raised only for `approved && success`: a denial or an expiry changes nothing on the server,
     * and reloading on those would replace the operator's own unsaved context for no gain.
     * [operation] is the server's operation name (`BACKDATE_CASH_BOOK_ENTRY`,
     * `CANCEL_ORDER_ITEMS`, …) so each screen reloads only for what concerns it.
     *
     * E2E-2026-09-22 R3-3: without this the approval only raised a toast, and the requesting
     * device kept its pre-approval list until someone pressed refresh.
     */
    data class ApprovalCompleted(
        val operation: String,
    ) : NotificationUiEvent
}

/**
 * Notification item data class.
 */
data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    val timestamp: String,
    val isRead: Boolean = false,
    val data: Map<String, String> = emptyMap(),
)

/**
 * Notification types for categorization.
 */
enum class NotificationType {
    TABLE_STATUS,
    SESSION_STARTED,
    SESSION_ENDED,
    ORDER_UPDATE,
    BILLING_REQUEST,
    APPROVAL_REQUEST,
    SYSTEM,
}
