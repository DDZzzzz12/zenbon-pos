package org.example.project.core.domain.dto.clienterror

import kotlinx.serialization.Serializable

/**
 * Client-side error reports.
 *
 * Every error the user is actually shown — server-rejected, network, or a client-side throwable —
 * is posted here so it lands in the server's log folder next to the server's own failures.
 * Server errors are included deliberately: their `traceId` already appears in `error.log`, but only
 * this report proves an operator *saw* it, and pairs the raw server fault with the screen and
 * terminal it surfaced on.
 */
object ClientErrorDto {
    /**
     * One shown error.
     *
     * Free-text fields are capped by [validate] rather than trusted: they are attacker-influenced
     * (an exception message can carry arbitrary server response text) and end up in a log file.
     */
    @Serializable
    data class ReportRequestDto(
        /** Client-generated id, stable across retries of the same error, for de-duplication. */
        val clientErrorId: String,
        /** ISO-8601 instant at which the client showed the error. */
        val occurredAt: String,
        /** Which client reported it — `POS_TILL` or `ADMIN`. */
        val app: String,
        /** `FROM_SERVER`, `NETWORK` or `UNKNOWN`, mirroring the `UiError` subtype. */
        val kind: String,
        /** Localized dialog title as the user saw it. */
        val title: String,
        /** Localized dialog message as the user saw it. */
        val message: String,
        /** `PosErrorCode` name when the failure came from the server. */
        val code: String? = null,
        /** HTTP status when the failure came from the server. */
        val httpStatus: Int? = null,
        /** Server-issued trace id, when the failure came from the server. */
        val traceId: String? = null,
        /** Throwable class name for a client-side failure. */
        val exceptionType: String? = null,
        /** Throwable message for a client-side failure. */
        val exceptionMessage: String? = null,
        /** Truncated stack trace for a client-side failure. */
        val stackTrace: String? = null,
        /** Screen or route the error surfaced on. */
        val screen: String? = null,
        /** Logged-in employee, when there is one — errors before login legitimately have none. */
        val employeeNumber: String? = null,
        /** Terminal id as the client knows it. */
        val terminalId: String? = null,
        /** Client build identifier. */
        val appVersion: String? = null,
    ) {
        /**
         * Rejects a malformed or oversized report.
         *
         * A report that fails this is dropped rather than logged: the endpoint exists to make
         * failures visible, and an unbounded field would turn it into a way to flood the disk.
         */
        fun validate(): List<String> =
            buildList {
                if (clientErrorId.isBlank() || clientErrorId.length > MAX_ID) add("clientErrorId must be 1..$MAX_ID characters")
                if (occurredAt.isBlank() || occurredAt.length > MAX_SHORT) add("occurredAt must be 1..$MAX_SHORT characters")
                if (app !in ALLOWED_APPS) add("app must be one of $ALLOWED_APPS")
                if (kind !in ALLOWED_KINDS) add("kind must be one of $ALLOWED_KINDS")
                if (title.length > MAX_TEXT) add("title must be at most $MAX_TEXT characters")
                if (message.length > MAX_TEXT) add("message must be at most $MAX_TEXT characters")
                if ((exceptionMessage?.length ?: 0) > MAX_TEXT) add("exceptionMessage must be at most $MAX_TEXT characters")
                if ((stackTrace?.length ?: 0) > MAX_STACK) add("stackTrace must be at most $MAX_STACK characters")
            }

        companion object {
            const val MAX_ID = 64
            const val MAX_SHORT = 64
            const val MAX_TEXT = 2_000
            const val MAX_STACK = 8_000
            val ALLOWED_APPS = setOf("POS_TILL", "ADMIN")
            val ALLOWED_KINDS = setOf("FROM_SERVER", "NETWORK", "UNKNOWN")
        }
    }

    /** Acknowledgement carrying the id the server logged, so a client can cross-reference it. */
    @Serializable
    data class ReportResponseDto(
        val clientErrorId: String,
        val logged: Boolean,
    )
}
