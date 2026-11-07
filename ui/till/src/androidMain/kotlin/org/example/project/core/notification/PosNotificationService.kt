package org.example.project.core.notification

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.example.project.composeapp.R
import org.example.project.core.auth.SessionStatus
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.network.AndroidNetworkMonitor
import org.example.project.core.network.NetworkAvailabilityNotifier
import org.example.project.core.network.NotificationConnection
import org.example.project.core.notification.NotificationServicePolicy.StartAction
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.NotificationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.initializeAppTranslations
import org.example.project.ui.auth.SessionState
import org.koin.android.ext.android.inject

/**
 * Holds the till's notification stream while the app is in the background, for the person logged in on it.
 *
 * **Foreground-service type `remoteMessaging`, not `dataSync`** (NEW-2, emulator pass 5): since Android 15 a
 * `dataSync` service may run only 6 h in 24 h in the background, then Android calls [onTimeout] — which this
 * service did not override, so the app died with `ForegroundServiceDidNotStopInTimeException` and the "keeps
 * stopping" dialog. Keeping a server message stream open is what `remoteMessaging` is for, and it has no daily
 * budget. [onTimeout] is overridden anyway, so that no type's limit can ever crash the app: the service stops,
 * and [restartIfTimedOut] brings it back when the app returns to the foreground.
 *
 * **Only for a session of this process** (NOTE-8, emulator pass 5): see [NotificationServicePolicy]. Resolving
 * [SessionState] here is what makes a revived service see the truth: in a fresh process that is the startup WIPE,
 * so the session is `UNAUTHENTICATED` whatever token was stored, and the service stops without streaming.
 */
class PosNotificationService : Service() {
    private val logger = Logger.withTag("PosNotificationService")
    private val notificationConnection: NotificationConnection by inject()
    private val sessionState: SessionState by inject()
    private val languageManager: LanguageManager by inject()
    private val appLifecycleObserver: AppLifecycleObserver by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationBuilder: SystemNotificationBuilder
    private lateinit var notificationManager: NotificationManager

    /** The single collector posting system notifications; see [observeMessages]. */
    private var messagesJob: Job? = null

    /** Nullable rather than `lateinit`: [onDestroy] runs even when [onCreate] failed before this was assigned. */
    private var networkMonitor: AndroidNetworkMonitor? = null

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val ACTION_STOP = "org.example.project.STOP_NOTIFICATION_SERVICE"
        private val log = Logger.withTag("PosNotificationService")

        /**
         * Set when Android timed the service out ([onTimeout]) and cleared by every explicit [start] and [stop]:
         * it marks a service that should be running for the live session but is not. Process-wide, like the
         * session it stands for — a new process starts logged out, and so with nothing to restart.
         */
        @Volatile
        private var stoppedByTimeout = false

        fun start(context: Context) {
            log.i { "PosNotificationService.start: starting foreground service" }
            stoppedByTimeout = false
            val intent = Intent(context, PosNotificationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            log.i { "PosNotificationService.stop: stopping service" }
            stoppedByTimeout = false
            val intent =
                Intent(context, PosNotificationService::class.java).apply {
                    action = ACTION_STOP
                }
            context.startService(intent)
        }

        /**
         * Called whenever the app comes to the foreground ([AppLifecycleObserver]): restarts a service Android timed
         * out while the session it served is still live. Starting a foreground service is allowed then, because the
         * app is visible.
         */
        fun restartIfTimedOut(
            context: Context,
            sessionStatus: () -> SessionStatus,
        ) {
            // Checked before the session is read: reading it can construct SessionState, and so run the startup
            // WIPE, which the app's first foreground must leave to the launch flow.
            if (!stoppedByTimeout) return
            if (!NotificationServicePolicy.restartOnForeground(stoppedByTimeout = true, sessionStatus())) return
            log.i { "PosNotificationService.restartIfTimedOut: restarting the service Android timed out" }
            start(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger.i { "PosNotificationService.onCreate: initializing service" }
        // A system restart after a crash can reach this without an Activity (it then stops at once, but may still
        // post the foreground notification), and that notification resolves localized text. Idempotent:
        // PosApplication calls it as well.
        initializeAppTranslations()
        notificationBuilder = SystemNotificationBuilder(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Registered here rather than in onStartCommand, which is what guards against double-registration: Android
        // calls onCreate exactly once per service instance and onDestroy exactly once, while onStartCommand runs again
        // on every start() — one callback leaked per start. Harmless this early: with no
        // connect yet there is no endpoint to reuse, so requestImmediateReconnect is a no-op until the stream opens.
        networkMonitor =
            AndroidNetworkMonitor(this, NetworkAvailabilityNotifier(notificationConnection)).also { it.start() }
    }

    /**
     * Returns START_NOT_STICKY on every path. A sticky restart only ever happens in a new process after a crash,
     * where the session is gone by definition (the startup WIPE); all it did was revive the stream for the
     * previous person (NOTE-8). A login, or [restartIfTimedOut], starts the service again when there is a session.
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (NotificationServicePolicy.onStartCommand(intent?.action == ACTION_STOP, sessionState.status)) {
            StartAction.STOP -> {
                logger.i { "PosNotificationService.onStartCommand: received stop action, disconnecting" }
                serviceScope.launch {
                    try {
                        notificationConnection.disconnect()
                    } catch (e: Exception) {
                        logger.e(e) { "PosNotificationService.onStartCommand: error during disconnect" }
                    }
                    stopSelf()
                }
            }
            StartAction.STOP_NO_SESSION -> {
                logger.w { "PosNotificationService.onStartCommand: nobody is logged in on this till, stopping without a stream" }
                // A start() went through startForegroundService, which Android fails unless startForeground follows.
                // A system restart (null intent) carries no such obligation, so it never enters the foreground.
                if (intent != null) startForegroundWithNotification()
                stopGracefully()
            }
            StartAction.CONNECT -> {
                logger.i { "PosNotificationService.onStartCommand: starting foreground service" }
                startForegroundWithNotification()
                connectWebSocket()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14's timeout for the `shortService` type. Not a type this service uses; handled so that no type's
     * limit can crash the app.
     */
    @Deprecated("Superseded by onTimeout(startId, fgsType) from API 35; still called on API 34")
    override fun onTimeout(startId: Int) {
        onForegroundTimeout("onTimeout(startId)")
    }

    /**
     * Android 15+: a foreground-service type's time limit ran out (`dataSync` and `mediaProcessing` have one). Left
     * unhandled, Android kills the app a few seconds later with `ForegroundServiceDidNotStopInTimeException`
     * (NEW-2). `remoteMessaging` has no limit, so this is a safety net: leave the foreground and stop — [onDestroy]
     * closes the stream — and come back through [restartIfTimedOut].
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        onForegroundTimeout("onTimeout(startId, fgsType=$fgsType)")
    }

    private fun onForegroundTimeout(callback: String) {
        logger.w { "PosNotificationService.$callback: foreground-service time limit reached, stopping" }
        stoppedByTimeout = true
        stopGracefully()
    }

    /** Leaves the foreground first — that is what Android's timeout checks — then stops; [onDestroy] closes the stream. */
    private fun stopGracefully() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val language = languageManager.getCurrentLanguage()
        val notification =
            NotificationCompat
                .Builder(this, NotificationChannelManager.CHANNEL_CONNECTION)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(NotificationKeys.systemConnectionActive, language))
                .setContentText(getString(NotificationKeys.systemConnectionActiveBody, language))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, foregroundServiceType())
    }

    /**
     * `remoteMessaging` exists from API 34, where a type is mandatory. Below it the type is only checked against the
     * manifest's, and "none" is always allowed; ServiceCompat ignores the argument below API 29.
     */
    @Suppress("DEPRECATION") // FOREGROUND_SERVICE_TYPE_NONE is deprecated from API 34, and used only below it.
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }

    /**
     * Opens the stream for the logged-in session. Runs on every login's start(); the connection re-binds a
     * stream still open for a previous user instead of keeping it (SseNotificationConnection's re-entrancy
     * guard, emulator pass 4 NOTE-4). A crash restart no longer reaches this — see [onStartCommand].
     */
    private fun connectWebSocket() {
        // Subscribe to messages BEFORE connecting — connect() suspends indefinitely
        // (startMessageListener collects from incoming channel until WebSocket closes)
        observeMessages()

        serviceScope.launch {
            try {
                logger.i { "PosNotificationService.connectWebSocket: connecting to notification stream" }
                notificationConnection.connect("api/notifications/stream")
            } catch (e: Exception) {
                logger.e(e) { "PosNotificationService.connectWebSocket: failed to connect" }
            }
        }
    }

    private fun observeMessages() {
        // onStartCommand runs again on every login's start(); one collector per start posted every
        // notification once per login since the service was created.
        if (messagesJob?.isActive == true) return
        messagesJob =
            serviceScope.launch {
                logger.i { "PosNotificationService.observeMessages: starting message collection" }
                notificationConnection.messages.collect { message ->
                    logger.d { "PosNotificationService.observeMessages: received ${message.messageType}" }
                    if (!appLifecycleObserver.isAppInForeground.value) {
                        postSystemNotification(message)
                    }
                }
            }
    }

    private fun postSystemNotification(message: NotificationDto.NotificationMessage) {
        val language = languageManager.getCurrentLanguage()
        val data = notificationBuilder.buildFromMessage(message, language) ?: return
        val notification = notificationBuilder.buildNotification(data)
        try {
            notificationManager.notify(data.id, notification)
            logger.d { "PosNotificationService.postSystemNotification: posted ${data.intentType}" }
        } catch (e: SecurityException) {
            logger.w { "PosNotificationService.postSystemNotification: notification permission not granted" }
        }
    }

    override fun onDestroy() {
        logger.i { "PosNotificationService.onDestroy: cleaning up" }
        // Before the disconnect below, so a network callback cannot ask a connection being torn down to reconnect.
        networkMonitor?.stop()
        networkMonitor = null
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                try {
                    notificationConnection.disconnect()
                } catch (e: Exception) {
                    logger.e(e) { "PosNotificationService.onDestroy: error during disconnect" }
                }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
