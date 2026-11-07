package org.example.project.tse.operations.signing

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode

/**
 * How a [WormException] from a signing operation is handled (E2E R2-6, design 2026-09-22 §4.4).
 *
 * The split is the SDK's own: codes below `0x1000` come from the host, the SDK or the connection;
 * `0x1000`–`0xFFFF` (`WORM_ERROR_FROM_CARD_FIRST`…`LAST`) are answers from the card. A card that
 * answered is connected, so only the host-side classes may reset the connection. Treating every
 * WormException as lost hardware cleared the mount point on an uncommissioned TSE, made setup fail
 * until the health tick re-acquired it, and recorded "TSE nicht erreichbar" in TSE_TA_FEHLER for a
 * device that was connected the whole time.
 *
 * Residual: the split is the SDK's packaging, not a semantic one. Some host-range codes mean "the
 * device is present but this request was wrong" rather than "the device is gone" — for example
 * `WORM_ERROR_INVALID_PARAMETER` — and this class still resets the connection for them, because
 * the SDK gives no way to tell the two apart below `0x1000`. Left as-is rather than special-cased
 * without a concrete failure it causes. The admin operation base class
 * (`operations/admin/BaseTseOperation`) applies this same classification (16779274e).
 *
 * The [NOT_READY]/[NOT_OPEN]/[TIMEOUTS] sets match on enum identity ([WormError] constant, not
 * `swigValue()`), even though a few SDK constants share a `swigValue()` (e.g.
 * `WORM_ERROR_WRONG_STATE_NEEDS_ACTIVE_CTSS`/`_NEEDS_ACTIVE_ERS`, both `4179`). That is fine:
 * [of] dispatches on the [WormError] enum constant that [WormError.swigToEnum] returns, and it
 * always returns the first-declared constant for a given value — so a set built from named
 * constants and a lookup built from a raw `swigValue()` never disagree about which one is meant.
 */
enum class WormErrorClass(
    val resetsConnection: Boolean,
    val code: PosErrorCode,
    val httpStatus: HttpStatusCode,
) {
    /** The request may have reached the device, so a signature may exist (M7: TSE_POSSIBLE_DOUBLE_SIGN). */
    COMMUNICATION_TIMEOUT(true, PosErrorCode.TSE_COMMUNICATION_TIMEOUT, HttpStatusCode.ServiceUnavailable),
    CONNECTION(true, PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable),
    NOT_OPERATIONAL(false, PosErrorCode.TSE_NOT_OPERATIONAL, HttpStatusCode.ServiceUnavailable),
    TRANSACTION_NOT_OPEN(false, PosErrorCode.TSE_TRANSACTION_NOT_OPEN, HttpStatusCode.Conflict),
    REJECTED(false, PosErrorCode.TSE_OPERATION_REJECTED, HttpStatusCode.UnprocessableEntity),
    ;

    /** The PosException message. CONNECTION keeps its historic text, which late-signing records quote. */
    fun messageFor(e: WormException): String =
        when (this) {
            CONNECTION -> "Failed to connect to TSE hardware"
            COMMUNICATION_TIMEOUT -> "TSE did not answer: ${nameOf(e)}"
            NOT_OPERATIONAL -> "TSE not operational: ${nameOf(e)}"
            TRANSACTION_NOT_OPEN -> "TSE transaction is not open: ${nameOf(e)}"
            REJECTED -> "TSE rejected the operation: ${nameOf(e)}"
        }

    companion object {
        private const val FROM_CARD_FIRST = 0x1000
        private const val FROM_CARD_LAST = 0xFFFF

        private val TIMEOUTS =
            setOf(
                WormError.WORM_ERROR_TIMEOUT,
                WormError.WORM_ERROR_RESPONSE_MISSING,
                WormError.WORM_ERROR_NETWORK_TIMEOUT,
            )

        private val NOT_READY =
            setOf(
                WormError.WORM_ERROR_TSE_NOT_INITIALIZED,
                WormError.WORM_ERROR_TSE_DECOMMISSIONED,
                WormError.WORM_ERROR_CLIENT_NOT_REGISTERED,
                WormError.WORM_ERROR_CERTIFICATE_EXPIRED,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_PIN_CHANGE,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_PUK_CHANGE,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_ACTIVE_CTSS,
                // Stale-TSE preconditions (BaseTseSignOperation.isRecoverablePrecondition):
                // recovery retries them once, but a "connected but not ready" TSE that fails
                // remediation must still be reported as NOT_OPERATIONAL, not REJECTED — it is
                // exactly the same class of problem as the others in this set.
                WormError.WORM_ERROR_NO_TIME_SET,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST,
                WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED,
            )

        private val NOT_OPEN =
            setOf(
                WormError.WORM_ERROR_TRANSACTION_NOT_STARTED,
                WormError.WORM_ERROR_NO_TRANSACTION_IN_PROGRESS,
            )

        /**
         * "Nothing new since the last incremental export" (F40 / RT2-B1). The SDK packages it as a
         * host-range code (12), but it is an answer about the data, not about the device: the TSE
         * that produced it is connected and healthy. Left in the host-side fallback it reset the
         * connection on every size query an operator made right after an export.
         */
        private val NO_DATA =
            setOf(
                WormError.WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA,
            )

        fun of(error: WormError?): WormErrorClass =
            when {
                error == null -> CONNECTION
                error in TIMEOUTS -> COMMUNICATION_TIMEOUT
                error in NOT_READY -> NOT_OPERATIONAL
                error in NOT_OPEN -> TRANSACTION_NOT_OPEN
                error in NO_DATA -> REJECTED
                error.swigValue() in FROM_CARD_FIRST..FROM_CARD_LAST -> REJECTED
                else -> CONNECTION
            }

        /** [WormException.error] throws for a message-only exception (code −1): no code, so a connection failure. */
        fun of(e: WormException): WormErrorClass = of(runCatching { e.error() }.getOrNull())

        /**
         * True when [e] is the SDK's "no new log messages since `lastState`" answer. Callers that can
         * express "nothing to export" as a result (size 0, no file) should do so instead of failing.
         */
        fun isNoNewData(e: WormException): Boolean = runCatching { e.error() }.getOrNull() in NO_DATA

        private fun nameOf(e: WormException): String = runCatching { e.error().name }.getOrDefault(e.message ?: "unknown")
    }
}
