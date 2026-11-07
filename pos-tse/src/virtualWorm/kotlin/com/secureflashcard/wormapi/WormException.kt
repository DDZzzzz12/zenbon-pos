package com.secureflashcard.wormapi

/**
 * Virtual test double replacing `WormException` from `lib/WormAPI.jar`.
 *
 * Behavior copied from the real class (probed 2026-07-14):
 * - `WormException(int)` renders its message as `"WormException: <ERROR_NAME>"`.
 * - `WormException(String)` keeps the plain message and stores error code -1, so
 *   calling [error] on it throws IllegalArgumentException — exactly like the real jar.
 * - Extends RuntimeException (unchecked), so `catch (e: Exception)` in
 *   `TseStatus.detectHardware` handles the virtual no-device case the same way it
 *   handles a missing device on real Linux/Windows hardware hosts.
 */
open class WormException : RuntimeException {

    private val code: Int
    private val plainMessage: String?

    constructor(code: Int) : super() {
        this.code = code
        this.plainMessage = null
    }

    constructor(message: String) : super() {
        this.code = -1
        this.plainMessage = message
    }

    fun error(): WormError = WormError.swigToEnum(code)

    override val message: String
        get() = plainMessage ?: "WormException: ${error().name}"
}
