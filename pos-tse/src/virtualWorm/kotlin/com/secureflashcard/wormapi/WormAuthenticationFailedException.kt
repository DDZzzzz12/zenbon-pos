package com.secureflashcard.wormapi

/**
 * Virtual test double replacing `WormAuthenticationFailedException` from `lib/WormAPI.jar`.
 *
 * Message format copied from the real class: `"Authentication failed, remaining retries: N"`.
 * [remainingRetries] is a public final field like in the real jar (used by
 * `TseInitializationManager` for lockout diagnostics).
 */
class WormAuthenticationFailedException(
    code: Int,
    @JvmField val remainingRetries: Int,
) : WormException(code) {

    override val message: String
        get() = "Authentication failed, remaining retries: $remainingRetries"
}
