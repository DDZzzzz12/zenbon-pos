package org.example.project.features.maintenance.domain.dto

import kotlinx.serialization.Serializable

/**
 * Wire types of the maintenance API. Server-only: the one client is a static JavaScript page, so
 * nothing here belongs in `shared`.
 */
object MaintenanceDto {
    @Serializable
    data class LoginRequestDto(
        val username: String,
        val password: String,
    ) {
        /** The password must never reach a log line through an accidental `"$request"`. */
        override fun toString(): String = "LoginRequestDto(username=…, password=…)"
    }

    @Serializable
    data class LoginResponseDto(
        val accessToken: String,
        val expiresInSeconds: Long,
    )

    @Serializable
    data class ExceptionDto(
        val summary: String,
        val frames: List<String>,
    )

    /**
     * One log entry as the maintainer sees it. By construction there is NO field for an employee id,
     * device id, username or employee number — see `LogEntryMapperTest`, which pins that.
     */
    @Serializable
    data class LogEntryDto(
        val source: String,
        val timestamp: String,
        val level: String,
        val message: String,
        val logger: String? = null,
        val traceId: String? = null,
        val code: String? = null,
        val statusCode: Int? = null,
        val method: String? = null,
        val path: String? = null,
        val durationMs: Long? = null,
        val exception: ExceptionDto? = null,
        val clientApp: String? = null,
        val clientErrorId: String? = null,
        val appVersion: String? = null,
    )

    /**
     * @property matched how many entries matched before `limit` cut the list (bounded by the scan caps)
     * @property truncated `MATCH_CAP` or `SCAN_BUDGET` when the scan stopped early, else null
     * @property oldestAvailable the date of the oldest rotated file seen, so the page can say when a
     *   requested range reaches past retention
     * @property from the lower time bound the search actually used (ISO-8601 instant): the caller's `from`,
     *   or the 24-hour default when neither `from` nor a trace id was given; null when unbounded
     */
    @Serializable
    data class LogSearchResponseDto(
        val entries: List<LogEntryDto>,
        val matched: Int,
        val scannedFiles: Int,
        val truncated: String? = null,
        val oldestAvailable: String? = null,
        val from: String? = null,
    )
}
