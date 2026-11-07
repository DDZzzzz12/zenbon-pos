package org.example.project.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.browser.BrowserKiosk
import org.example.project.core.browser.isBrowserClient
import org.example.project.core.browser.rememberIdleLockModifier
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.BROWSER_IDLE_LOCK_MINUTES
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact
import org.example.project.features.auth.presentation.state.TemporarySessionUiState
import org.example.project.features.auth.presentation.state.offersRequestSession
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.screens.auth.components.PhoneRequestBanner
import org.example.project.ui.screens.auth.components.PhoneSessionBanner

@Composable
fun PosNavShell(
    selectedDestination: PosNavDestination,
    onDestinationSelected: (PosNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    notificationUnreadCount: Int = 0,
    sessionIndicator: (@Composable () -> Unit)? = null,
    managementItems: List<PosNavDestination> = emptyList(),
    onManagementItemClick: (PosNavDestination) -> Unit = {},
    onLogout: () -> Unit = {},
    onShutdown: () -> Unit = {},
    /** Station only: the cold-reset path (clears session + device token, back to pairing). Replaces logout. */
    onRepair: () -> Unit = {},
    /**
     * Opens the web admin (`<base>/admin/`) in the system browser.
     *
     * Offered on BOTH shells and ungated: the panel enforces its own login, so an anonymous helper
     * at a station reaches only its login page.
     */
    onOpenBackoffice: () -> Unit = {},
    // Temporary session support
    sessionState: TemporarySessionUiState? = null,
    sessionManager: TemporarySessionManager? = null,
    currentLanguage: Language = Language.ENGLISH,
    showBottomBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    val windowSize = LocalWindowSizeClass.current

    // The shell exists only while a person is signed in: ask the browser to confirm before the tab
    // closes or reloads on an open order.
    DisposableEffect(Unit) {
        BrowserKiosk.setLeaveGuard(true)
        onDispose { BrowserKiosk.setLeaveGuard(false) }
    }

    // Browser till left unattended: end the signed-in person's session (the device stays paired).
    val idleLockApplies = browserIdleLockApplies(isBrowserClient = isBrowserClient, isStation = rememberIsStation())
    val shellModifier =
        modifier.then(
            rememberIdleLockModifier(
                enabled = idleLockApplies,
                minutes = BROWSER_IDLE_LOCK_MINUTES,
                onLock = onLogout,
            ),
        )

    // One frame for every width: the screen content is moved between the phone and the rail
    // arrangement, never rebuilt, so a rotation or a browser resize keeps the open screen as it is.
    // The medium and expanded widths share one rail call (only its padding differs), so crossing
    // 840 dp does not even move the content.
    val expanded = windowSize.isAtLeastExpanded()
    PosNavShellFrame(
        compact = windowSize.isCompact(),
        modifier = shellModifier,
        rail = {
            PosNavRail(
                modifier = if (expanded) Modifier.padding(horizontal = 2.dp, vertical = 8.dp) else Modifier,
                selectedDestination = selectedDestination,
                onDestinationSelected = onDestinationSelected,
                sessionIndicator = sessionIndicator,
                managementItems = managementItems,
                onManagementItemClick = onManagementItemClick,
                notificationUnreadCount = notificationUnreadCount,
                onLogout = onLogout,
                onShutdown = onShutdown,
                onRepair = onRepair,
                onOpenBackoffice = onOpenBackoffice,
                sessionState = sessionState,
                sessionManager = sessionManager,
                currentLanguage = currentLanguage,
            )
        },
        bottomChrome = {
            if (showBottomBar) {
                if (sessionState != null) {
                    PhoneSessionBanner(
                        state = sessionState,
                        onExtendClick = { sessionManager?.extendSession() },
                        onEndClick = { sessionManager?.endSession() },
                    )
                    PhoneRequestBanner(
                        onClick = { sessionManager?.showDialog() },
                        visible = sessionState.offersRequestSession(LocalAccessPolicy.current, rememberIsStation()),
                    )
                }
                PosBottomBar(
                    selectedDestination = selectedDestination,
                    onDestinationSelected = onDestinationSelected,
                    managementItems = managementItems,
                    onManagementItemClick = onManagementItemClick,
                    onRepair = onRepair,
                    onOpenBackoffice = onOpenBackoffice,
                )
            }
        },
        content = content,
    )
}

/**
 * Whether the browser idle lock guards this shell: on a browser client, whenever a PERSON is signed
 * in — whatever their role, a manager's session left open on an unattended till being no less
 * exposed than a cashier's, and a temporary manager session on a cashier's till included. An order
 * station has no person session to end ([isStation], the station marker), so it is exempt; a native
 * client has no idle lock at all.
 *
 * Asked of the station marker rather than of the role: the role used to stand in for "a person on a
 * till" (`role == CASHIER`), which left every manager and admin session on a browser till unlocked.
 */
internal fun browserIdleLockApplies(
    isBrowserClient: Boolean,
    isStation: Boolean,
): Boolean = isBrowserClient && !isStation
