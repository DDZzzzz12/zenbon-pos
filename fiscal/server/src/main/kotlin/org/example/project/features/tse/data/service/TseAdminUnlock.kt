package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory

/**
 * Starts a TSE admin sitting only for a PIN the device accepts.
 *
 * [TseAdminSession] used to hold whatever PIN it was given and report the sitting unlocked, so a
 * mistyped PIN surfaced only at the operator's *next* administrative action, as an unexplained
 * `TSE_OPERATION_REJECTED` — and each such action was another failed Admin login on the card, which
 * blocks the Admin PIN after a fixed number of failures (E2E round 4, R4-1).
 *
 * The check is one read-only admin call ([TseAdminProvider.listClients]). It costs exactly the one
 * login attempt the operator has just asked for. Only an answer from the card that names an
 * authentication failure refuses the PIN; any other failure (device not commissioned, unreachable,
 * no SDK) says nothing about the PIN, so the sitting is kept and the next action reports its own
 * cause.
 */
class TseAdminUnlock(
    private val adminProvider: TseAdminProvider,
) {
    private val logger = LoggerFactory.getLogger(TseAdminUnlock::class.java)

    suspend fun unlock(pin: String) {
        TseAdminSession.unlock(pin)
        val rejection =
            try {
                adminProvider.listClients()
                null
            } catch (e: PosException) {
                e
            }
        rejection ?: return
        if (!rejection.isPinRejection()) {
            logger.warn("TSE admin PIN could not be verified ({}); the sitting is kept", rejection.code)
            return
        }
        TseAdminSession.clear()
        throw pinRefused(rejection.isPinBlocked())
    }

    private fun pinRefused(blocked: Boolean) =
        PosException(
            code = PosErrorCode.TSE_INVALID_PIN,
            httpStatus = HttpStatusCode.UnprocessableEntity,
            message =
                if (blocked) {
                    "The TSE Admin PIN is blocked. Unblock it with the PUK before administering the TSE."
                } else {
                    "The TSE rejected the Admin PIN; administration stays locked. Every wrong PIN counts " +
                        "towards the device's lockout."
                },
        )

    companion object {
        private const val AUTH_ERROR_MARKER = "WORM_ERROR_AUTHENTICATION"
        private const val PIN_BLOCKED_MARKER = "WORM_ERROR_AUTHENTICATION_PIN_BLOCKED"

        /**
         * The admin path reports a refused login as the card's own error name inside the message
         * (`TSE rejected the operation: WORM_ERROR_AUTHENTICATION_FAILED`); `pos-tse` exposes no
         * structured field for it.
         */
        internal fun PosException.isPinRejection(): Boolean = code == PosErrorCode.TSE_AUTHENTICATION_FAILED || message.orEmpty().contains(AUTH_ERROR_MARKER)

        internal fun PosException.isPinBlocked(): Boolean = message.orEmpty().contains(PIN_BLOCKED_MARKER)
    }
}
