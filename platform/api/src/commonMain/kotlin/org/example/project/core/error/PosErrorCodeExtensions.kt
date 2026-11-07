package org.example.project.core.error

/**
 * Set of TSE-related [PosErrorCode] entries that are considered retryable by
 * [TseSigningRetryService] and similar retry-loop callers.
 *
 * Replaces the legacy `TseError.isRetryable()` extension; the membership is
 * preserved 1:1 with the seven "yes" rows in the cross-walk table from
 * docs/superpowers/plans/2026-05-04-poserror-tse-migration.md.
 */
private val TSE_RETRYABLE: Set<PosErrorCode> =
    setOf(
        PosErrorCode.TSE_CONNECTION_FAILED,
        PosErrorCode.TSE_COMMUNICATION_TIMEOUT,
        PosErrorCode.TSE_HARDWARE_BUSY,
        PosErrorCode.TSE_TRANSACTION_START_FAILED,
        PosErrorCode.TSE_TRANSACTION_UPDATE_FAILED,
        PosErrorCode.TSE_TIME_UPDATE_FAILED,
        PosErrorCode.TSE_INFO_RETRIEVAL_FAILED,
    )

/**
 * Returns `true` if this error code represents a TSE failure that may succeed on retry.
 *
 * Used by `TseSigningRetryService` and any other component that wants to decide
 * whether to retry after catching a [org.example.project.infrastructure.errors.PosException]
 * with a TSE-related [PosErrorCode].
 */
fun PosErrorCode.isTseRetryable(): Boolean = this in TSE_RETRYABLE
