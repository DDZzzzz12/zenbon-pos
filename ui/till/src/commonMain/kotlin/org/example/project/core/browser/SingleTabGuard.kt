package org.example.project.core.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps one browser station to one tab.
 *
 * A new tab starts optimistically as [TabState.Owner] but announces itself with `probe:<id>`; a
 * settled owner answers at once with `alive:<id>`, which turns the newcomer into
 * [TabState.Duplicate]. Settled owners broadcast `alive` every [heartbeatMs]. A duplicate that has
 * heard no `alive` for [timeoutMs] (the owner tab closed or froze) takes over. If two tabs open
 * simultaneously and both settle as owner, the higher id yields.
 *
 * Drive [tick] from a timer at least as often as [heartbeatMs].
 */
class SingleTabGuard(
    private val messenger: TabMessenger,
    private val clock: () -> Long,
    private val tabId: Long,
    private val heartbeatMs: Long = 2_000,
    private val timeoutMs: Long = 6_000,
) {
    private val _state = MutableStateFlow(TabState.Owner)
    val state: StateFlow<TabState> = _state.asStateFlow()

    private var startedAt = 0L
    private var lastSent = Long.MIN_VALUE
    private var lastHeard = 0L
    private var settled = false

    fun start() {
        startedAt = clock()
        messenger.onMessage(::onMessage)
        messenger.send("$PROBE:$tabId")
    }

    fun tick() {
        val now = clock()
        when (_state.value) {
            TabState.Owner -> {
                if (!settled && now - startedAt >= heartbeatMs) settled = true
                if (settled && (lastSent == Long.MIN_VALUE || now - lastSent >= heartbeatMs)) sendAlive(now)
            }
            TabState.Duplicate ->
                if (now - lastHeard >= timeoutMs) {
                    settled = true
                    _state.value = TabState.Owner
                    sendAlive(now)
                }
        }
    }

    private fun sendAlive(now: Long) {
        lastSent = now
        messenger.send("$ALIVE:$tabId")
    }

    private fun onMessage(msg: String) {
        val kind = msg.substringBefore(':')
        val sender = msg.substringAfter(':', "").toLongOrNull() ?: return
        if (sender == tabId) return
        when (kind) {
            PROBE -> if (_state.value == TabState.Owner && settled) sendAlive(clock())
            ALIVE -> onForeignAlive(sender)
        }
    }

    private fun onForeignAlive(sender: Long) {
        when (_state.value) {
            TabState.Duplicate -> lastHeard = clock()
            TabState.Owner ->
                // A settled owner only yields in the simultaneous-open tie; an unsettled one always.
                if (!settled || sender < tabId) {
                    lastHeard = clock()
                    _state.value = TabState.Duplicate
                }
        }
    }

    private companion object {
        const val PROBE = "probe"
        const val ALIVE = "alive"
    }
}
