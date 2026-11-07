package org.example.project.core.browser

/**
 * One-shot hand-over of a pairing code read from the page URL at startup to the pairing screen.
 * [take] returns it once and then forgets it, so a re-entered screen never sees a stale code.
 */
object PendingPairingCode {
    private var code: String? = null

    fun set(value: String?) {
        code = value
    }

    fun take(): String? = code.also { code = null }
}
