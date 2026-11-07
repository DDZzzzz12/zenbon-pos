package org.example.project.core.browser

/**
 * Locks a browser till after [minutes] without input.
 *
 * Pure logic: the caller feeds [onInput] and calls [tick] on a timer. [onLock] fires once per idle
 * period, not on every tick, and re-arms on the next input.
 */
class IdleLock(
    private val minutes: Int,
    private val clock: () -> Long,
    private val onLock: () -> Unit,
) {
    private var lastInput = clock()
    private var fired = false

    fun onInput() {
        lastInput = clock()
        fired = false
    }

    fun tick() {
        if (fired || clock() - lastInput < minutes * MILLIS_PER_MINUTE) return
        fired = true
        onLock()
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
