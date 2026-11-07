package org.example.project.core.domain.enums.zBon

import kotlinx.serialization.Serializable

/**
 * Why a closing's cash movements cannot be signed right now, as `GET /api/z-bon/closing-readiness`
 * reports it. Null in the DTO means a signer is available.
 */
@Serializable
enum class ClosingSignerProblem {
    /** No till is designated on the Ladenkasse and the caller is not a till that could sign itself. */
    NOT_DESIGNATED,

    /** The signing till (designated, or the calling till) is deactivated. */
    INACTIVE,

    /** The signing till is non-fiscal. */
    NOT_FISCAL,

    /** The signing till has no registered TSE client (only reported in NORMAL TSE mode). */
    NOT_REGISTERED,
}
