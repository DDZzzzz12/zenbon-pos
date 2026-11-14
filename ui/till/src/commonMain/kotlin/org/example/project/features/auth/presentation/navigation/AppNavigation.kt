package org.example.project.features.auth.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import co.touchlab.kermit.Logger
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.SettingProvider
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.presentation.components.keyboard.VirtualKeyboardOverlay
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.LoginKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.LocalVirtualKeyboardPadding
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.ProvideWindowSizeClass
import org.example.project.core.utils.VirtualKeyboardController
import org.example.project.core.utils.exitApplication
import org.example.project.core.utils.hasPhysicalKeyboard
import org.example.project.core.utils.isDesktop
import org.example.project.features.appupdate.presentation.viewmodels.AppUpdateViewModel
import org.example.project.features.auth.presentation.viewmodels.LoginViewModel
import org.example.project.features.launch.presentation.TerminalColdResetBus
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.nav.AppDestination
import org.example.project.features.launch.presentation.nav.clearsOverlaysOnLaunchTransition
import org.example.project.features.launch.presentation.screens.LaunchInsecureUrlContent
import org.example.project.features.launch.presentation.screens.LaunchLoadingContent
import org.example.project.features.launch.presentation.screens.LaunchOfflineContent
import org.example.project.features.launch.presentation.screens.LaunchStationBlockedContent
import org.example.project.features.launch.presentation.screens.TerminalRolePlaceholderScreen
import org.example.project.features.launch.presentation.screens.WebSetupRequiredScreen
import org.example.project.features.launch.presentation.viewmodels.LaunchCoordinator
import org.example.project.features.launch.presentation.viewmodels.StationRefusal
import org.example.project.features.main.presentation.viewmodels.MainViewModel
import org.example.project.features.terminal.presentation.screens.DevicePairingScreen
import org.example.project.ui.auth.SessionState
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosNotificationHost
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.screens.auth.LoginScreen
import org.example.project.ui.screens.main.MainScreen
import org.example.project.ui.screens.notifications.BackgroundExecutionPromptDialog
import org.example.project.ui.theme.PosTheme
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private val logger = Logger.withTag("AppNavigation")

/**
 * Root navigation component for the application.
 *
 * The launch/auth flow is state-driven: [LaunchCoordinator] is the single async authority that
 * resolves an [AppDestination] from setup status, device-token/pairing state, and session status,
 * and this composable renders a `when` over that destination directly — no NavController/NavHost
 * for this flow. [org.example.project.ui.screens.main.MainScreen] keeps its own self-contained
 * nested navigation once [AppDestination.Main] is reached.
 */
@Composable
fun AppNavigation() {
    val coordinator: LaunchCoordinator = koinViewModel()
    val loginViewModel: LoginViewModel = koinViewModel()
    val appUpdateViewModel: AppUpdateViewModel = koinViewModel()
    val mainViewModel: MainViewModel = koinViewModel()
    val globalOverlayViewModel: GlobalOverlayViewModel = koinViewModel()
    val currentNotification by globalOverlayViewModel.currentNotification.collectAsState()

    // The app-level session is the single source of truth for which half of the app is showing.
    // Constructing it here (rather than in a ViewModel) is what runs StartupPolicy.WIPE at app
    // start — the cashier terminal's fresh-login boundary is enforced where it is named.
    val session = koinInject<SessionState>()
    val platformNotificationService = koinInject<PlatformNotificationService>()
    // While the prompt window covers the screen, the notification host is not composed at all —
    // so a queued toast's countdown (it starts on composition, PosToast has no `paused` input)
    // does not run behind the prompt and expire unseen. The queue head itself stays untouched in
    // GlobalOverlayViewModel; with Task P4.5's key(id) its countdown starts once the prompt closes
    // and the host is composed again.
    val backgroundExecutionPromptVisible by platformNotificationService.backgroundExecutionPromptVisible.collectAsState()

    // Session status is bridged into the coordinator's pure inputs so resolveDestination stays a
    // pure function of (setup, device, session) rather than reading Compose state itself.
    val destination by coordinator.destination.collectAsState()
    val serverAddressError by coordinator.serverAddressError.collectAsState()
    LaunchedEffect(session.status) {
        coordinator.setSessionStatus(session.status)
        loginViewModel.onSessionStatusChanged(session.status)
    }

    // A refused station-session exchange (revoke / deactivate / unreadable token) cold-resets the
    // device and lands on the pairing screen; say why, rather than leaving a silent reset. The
    // same channel also carries a block (unregistered TSE client, incomplete install, a session this
    // device cannot store right now), which leaves the device paired and shows its own retryable
    // screen. The event is one-shot, so
    // a second identical warning still shows, and re-entering composition does not re-fire it.
    var stationRefusal by remember { mutableStateOf<StationRefusal?>(null) }
    LaunchedEffect(Unit) { coordinator.refusals.collect { stationRefusal = it } }
    stationRefusal?.let { refusal ->
        // The server's own sentence when it wrote one; the ceiling's 409/403 bodies are plain
        // `{"error":"..."}` maps with no RFC 7807 detail, so the localized message is the norm.
        val title = str(refusal.error.titleKey)
        val message = refusal.serverDetail ?: str(refusal.error.messageKey)
        LaunchedEffect(refusal) {
            globalOverlayViewModel.showWarning(title = title, message = message)
            stationRefusal = null
        }
    }

    val loginUiState by loginViewModel.uiState.collectAsState()
    val mainUiState by mainViewModel.uiState.collectAsState()

    var showExitDialog by remember { mutableStateOf(false) }
    val onShowExitDialog: () -> Unit = { showExitDialog = !showExitDialog }

    SettingProvider(
        session.status == SessionStatus.AUTHENTICATED,
        mainUiState.syncCompleted,
    ) {
        val settingManager = LocalSettingManager.current
        val themeMode = settingManager.themeMode.collectAsState().value

        val lifecycleHandler = koinInject<TerminalLifecycleHandler>()
        val coldResetBus = koinInject<TerminalColdResetBus>()

        // Wire terminal lifecycle handling: server-pushed termination SSEs and API-level
        // auth failures both perform a cold reset and return to the launch gate. The reset
        // itself just clears the coordinator's device state — resolveDestination takes it
        // from there.
        //
        // A role change is the other half: the server drops the session but keeps the pairing, so
        // the flow is re-run rather than dropped to the pairing screen. `onTerminalRoleChanged()`
        // re-reads the new role from the server, and an ORDER_STATION then performs its
        // station-session exchange — which is what stops the device presenting the previous
        // person's JWT.
        LaunchedEffect(lifecycleHandler, coldResetBus) {
            lifecycleHandler.onColdReset = { coordinator.onColdReset() }
            // One call, not `setSessionStatus(UNAUTHENTICATED)` + `refresh()`. The new status is
            // still PUSHED rather than waited for — the reset has already signed the session out,
            // but the bridge above only fires on the next recomposition, and the device check is a
            // network round-trip that could land first, leaving a re-roled CASHIER resolving to Main
            // on a token store that was just emptied. It has to land in the SAME input update as the
            // device reset, though: two updates recompute the destination on the first, while the
            // PREVIOUS role is still held, and (UNAUTHENTICATED + a stale ORDER_STATION) resolves to
            // StationSession — which fired the exchange against the new role, got 403, and cold-reset
            // the device the `requiresRepairing = false` flag exists to keep paired.
            lifecycleHandler.onSessionReset = { coordinator.onTerminalRoleChanged() }
            lifecycleHandler.start(this)
            coldResetBus.requests.collect { kind ->
                val coldResetReason = kind.coldResetReason
                if (coldResetReason == null) lifecycleHandler.sessionReset() else lifecycleHandler.coldReset(coldResetReason)
            }
        }
        DisposableEffect(lifecycleHandler) {
            onDispose {
                lifecycleHandler.stop()
            }
        }

        // The overlay VM lives above the session boundary, so clear any queued overlays
        // when the user signs out. Otherwise session A toasts can render over session B.
        LaunchedEffect(session.status) {
            if (session.status == SessionStatus.UNAUTHENTICATED) {
                globalOverlayViewModel.clearAll()
            }
        }

        // Sync once per entry into Main, mirroring the old navigate-into-Main side effect.
        LaunchedEffect(destination) {
            if (destination == AppDestination.Main) mainViewModel.performSync()
        }

        // A toast from one launch screen must not greet the operator on the next. Only
        // launch-screen → launch-screen moves clear; see clearsOverlaysOnLaunchTransition for
        // the warnings that must survive (station refusal, blocked station).
        var previousDestination by remember { mutableStateOf<AppDestination?>(null) }
        LaunchedEffect(destination) {
            if (clearsOverlaysOnLaunchTransition(previousDestination, destination)) {
                globalOverlayViewModel.clearAll()
            }
            previousDestination = destination
        }

        // Virtual keyboard controller
        val virtualKeyboardController = remember { VirtualKeyboardController() }
        val isDesktopPlatform = isDesktop()
        val physicalKeyboard = hasPhysicalKeyboard()
        LaunchedEffect(physicalKeyboard) {
            virtualKeyboardController.hasPhysicalKeyboard = physicalKeyboard
        }

        val languageManager = LocalLanguageManager.current

        PosTheme(
            themeMode = themeMode,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                val keyboardPadding =
                    with(LocalDensity.current) {
                        virtualKeyboardController.keyboardHeightPx.toDp()
                    }

                CompositionLocalProvider(
                    LocalVirtualKeyboard provides virtualKeyboardController,
                    LocalVirtualKeyboardPadding provides keyboardPadding,
                    LocalGlobalOverlayViewModel provides globalOverlayViewModel,
                ) {
                    ProvideWindowSizeClass {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = keyboardPadding),
                            ) {
                                when (val d = destination) {
                                    AppDestination.Resolving -> LaunchLoadingContent()
                                    AppDestination.InsecureServerUrl -> {
                                        val localDb = koinInject<LocalDatabaseManager>()
                                        LaunchInsecureUrlContent(
                                            currentUrl = localDb.getApiBaseUrl(),
                                            onSave = { url, checkCode -> coordinator.saveHttpsAddress(url, checkCode) },
                                        )
                                    }
                                    is AppDestination.Offline ->
                                        LaunchOfflineContent(
                                            canChangeServerAddress = d.canChangeServerAddress,
                                            onRetry = coordinator::refresh,
                                            onChangeServerAddress = { coordinator.changeServerAddress(it) },
                                            certificateUntrusted = d.certificateUntrusted,
                                            onPinCertificate = coordinator::pinServerCertificate,
                                            onRepair = coordinator::repairFromOffline,
                                        )
                                    is AppDestination.Setup ->
                                        WebSetupRequiredScreen(
                                            canChangeServerAddress = d.canChangeServerAddress,
                                            onCheckAgain = coordinator::refresh,
                                            onChangeServerAddress = { coordinator.changeServerAddress(it) },
                                        )
                                    AppDestination.Pairing -> {
                                        BackHandler { onShowExitDialog() }
                                        DevicePairingScreen(onPaired = coordinator::onPaired)
                                    }
                                    AppDestination.StationSession -> LaunchLoadingContent()
                                    is AppDestination.StationBlocked ->
                                        LaunchStationBlockedContent(
                                            block = d.block,
                                            onRetry = coordinator::refresh,
                                        )
                                    is AppDestination.RolePlaceholder -> {
                                        BackHandler { onShowExitDialog() }
                                        TerminalRolePlaceholderScreen(
                                            terminalId = d.terminalId,
                                            role = d.role,
                                            onPairDifferentDevice = coordinator::pairDifferentDevice,
                                        )
                                    }
                                    AppDestination.Login -> {
                                        BackHandler { onShowExitDialog() }
                                        LoginScreen(
                                            onLogin = { identifier, secret, rememberMe ->
                                                loginViewModel.login(identifier, secret, rememberMe) {
                                                    languageManager.setLanguage(Language.fromCode(it.lowercase()))
                                                }
                                            },
                                            isAuthenticated = session.status == SessionStatus.AUTHENTICATED,
                                            loginViewModel = loginViewModel,
                                            appUpdateViewModel = appUpdateViewModel,
                                        )
                                    }
                                    AppDestination.Main -> {
                                        BackHandler { onShowExitDialog() }
                                        MainScreen(
                                            onLogout = {
                                                mainViewModel.onLogout()
                                                // Modal error dialogs and loading overlays live in the
                                                // global overlay queue, above the session boundary: drop
                                                // them now rather than waiting for the status flip, so a
                                                // sign-out (idle lock included) leaves nothing behind.
                                                globalOverlayViewModel.clearAll()
                                                loginViewModel.logout()
                                            },
                                            onBackOnDashboard = onShowExitDialog,
                                            viewModel = mainViewModel,
                                        )
                                    }
                                }
                            }

                            // Global error surface. PosErrorWatcher routes by error content:
                            // validation detail and 5xx server faults escalate to the typed
                            // PosErrorDialog (field list, code, copyable traceId); routine
                            // 4xx and transport blips toast on the notification queue like
                            // every other error in the app.
                            PosErrorWatcher(
                                error = loginUiState.error,
                                title = str(LoginKeys.title),
                                onClear = loginViewModel::clearError,
                                // The login destination renders while the session is
                                // UNAUTHENTICATED, so the default suppression cleared every
                                // rejected sign-in before it could toast — a wrong password gave
                                // no feedback at all. Logout failures raised from the same state
                                // were swallowed for the same reason.
                                suppressWhenSignedOut = false,
                                screen = "Login",
                            )
                            // A server address the offline or setup-required launch screen could not
                            // store (not a valid address, or a paired device). Shown while signed
                            // out, like login.
                            PosErrorWatcher(
                                error = serverAddressError,
                                title = str(LaunchKeys.serverAddressErrorTitle),
                                onClear = coordinator::clearServerAddressError,
                                suppressWhenSignedOut = false,
                                screen = "Launch",
                            )
                            PosErrorWatcher(
                                error = mainUiState.error,
                                title = str(CommonKeys.error),
                                onClear = mainViewModel::dismissError,
                                screen = "Main",
                            )
                            // Exit confirmation dialog
                            if (showExitDialog) {
                                PosDialog(
                                    onDismiss = { showExitDialog = false },
                                    title = str(CommonKeys.exitAppTitle),
                                    titleIcon = Icons.AutoMirrored.Filled.ExitToApp,
                                    footer = {
                                        PosTextButton(
                                            text = str(CommonKeys.cancel),
                                            onClick = { showExitDialog = false },
                                        )
                                        PosTextButton(
                                            text = str(CommonKeys.exitAppConfirm),
                                            onClick = {
                                                mainViewModel.onLogout()
                                                // Exit only after the server logout has run —
                                                // logout() returns immediately, so exiting on the
                                                // next line would kill the coroutine before
                                                // POST /api/auth/logout is sent and leave the
                                                // refresh token live on the server.
                                                loginViewModel.logout { exitApplication() }
                                            },
                                        )
                                    },
                                ) {
                                    Text(str(CommonKeys.exitAppMessage))
                                }
                            }

                            // Android only: explain background operation before the battery settings
                            // open. Main only, so it can never cover login, pairing or a launch screen.
                            if (destination == AppDestination.Main) {
                                BackgroundExecutionPromptDialog(platformNotificationService = platformNotificationService)
                            }

                            // Global overlay surface: toasts, dialogs, and loading (one at a time).
                            // A TOAST is skipped while the background-execution prompt is visible,
                            // so it cannot count itself down unseen underneath it; a DIALOG (e.g. the
                            // AuthenticationFailed popup right after login) must still get through —
                            // only a toast waits for the prompt to close.
                            if (!backgroundExecutionPromptVisible || currentNotification?.displayMode == DisplayMode.DIALOG) {
                                PosNotificationHost(
                                    notification = currentNotification,
                                    onDismiss = globalOverlayViewModel::dismissCurrent,
                                )
                            }

                            // Virtual keyboard overlay at bottom, on top of content
                            VirtualKeyboardOverlay(
                                windowSizeClass = LocalWindowSizeClass.current,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}
