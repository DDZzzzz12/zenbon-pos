package org.example.project.features.launch.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global signal for terminal-auth failures that end the current session.
 *
 * [request] may be called from non-Compose code (e.g. Ktor response validators or auth-plugin
 * callbacks). The app root collects [requests] and performs the reset through
 * [TerminalLifecycleHandler].
 */
class TerminalColdResetBus {
    private val _requests = MutableSharedFlow<TerminalResetKind>(extraBufferCapacity = 1)
    val requests = _requests.asSharedFlow()

    fun request(kind: TerminalResetKind) {
        _requests.tryEmit(kind)
    }
}
