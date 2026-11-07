package org.example.project.features.tse.domain

import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/** Codes that mean the TSE could not be reached at all, as opposed to answering with an error. */
private val UNAVAILABLE_CODES =
    setOf(
        PosErrorCode.TSE_UNREACHABLE,
        PosErrorCode.TSE_HARDWARE_NOT_FOUND,
        PosErrorCode.TSE_INVALID_MOUNT_POINT,
        PosErrorCode.TSE_CONNECTION_FAILED,
        PosErrorCode.TSE_COMMUNICATION_TIMEOUT,
        PosErrorCode.TSE_HARDWARE_BUSY,
    )

/**
 * The Nachsignierung reason and operator-readable text for a failed sign attempt, recorded
 * permanently when the Vorgang is queued. The text keeps the error code, so the cause stays
 * identifiable long after the server log has rotated.
 */
fun Throwable.toLateSigningCause(): Pair<TseLateSigningReason, String> =
    when (this) {
        is PosException -> {
            val reason =
                when (code) {
                    PosErrorCode.TSE_NOT_OPERATIONAL -> TseLateSigningReason.TSE_NOT_OPERATIONAL
                    in UNAVAILABLE_CODES -> TseLateSigningReason.TSE_UNAVAILABLE
                    else -> TseLateSigningReason.TSE_ERROR
                }
            reason to "${code.name}: ${message ?: "TSE-Fehler"}"
        }
        else -> TseLateSigningReason.TSE_ERROR to "${this::class.simpleName}: ${message ?: "TSE-Fehler"}"
    }

/**
 * The cause for a sign attempt whose resolved decision is to queue. A [TseSignOutcome.Failed] carries
 * its own error; any other outcome that still queues means no signature came back.
 */
fun TseSignOutcome.toLateSigningCause(): Pair<TseLateSigningReason, String> =
    when (this) {
        is TseSignOutcome.Failed -> error.toLateSigningCause()
        else -> TseLateSigningReason.TSE_ERROR to "Keine Signatur erhalten"
    }
