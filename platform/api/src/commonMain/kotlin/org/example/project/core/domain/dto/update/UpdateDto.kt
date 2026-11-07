package org.example.project.core.domain.dto.update

import kotlinx.serialization.Serializable

/**
 * The wire shapes for the software-update endpoints (`/api/update`).
 *
 * ### The token is write-only, structurally
 *
 * [SetTokenRequestDto] is the only shape in this file that carries a token, and it only travels
 * *inbound*. Neither read shape has a token property **at all** — not a nulled one. A nulled
 * property is an invitation: it renders in the JSON, it looks like a field waiting to be filled,
 * and the next well-meaning change populates it. Leaving the field out of the type makes exposing
 * the credential a compile error rather than a review question.
 *
 * What a read does expose is [SettingsDto.tokenPreview] — at most the last four characters, behind
 * a mask, purely so an operator can tell *which* token is stored — and [SettingsDto.tokenSetAt].
 */
object UpdateDto {
    /** Whether a usable token is stored, and if not, why not. */
    @Serializable
    enum class TokenState {
        /** No token has ever been entered. */
        NONE,

        /** A token is stored and this host's key opens it. */
        SET,

        /**
         * Ciphertext exists but this host's key cannot open it — a backup restored onto another
         * machine, or a changed `UPDATE_TOKEN_KEY`. The operator must re-enter the token.
         *
         * Deliberately distinct from [NONE]: collapsing the two would tell an operator nothing is
         * configured when in fact something is configured and broken.
         */
        UNREADABLE,
    }

    /** What the operator sees on the update screen: the running build and the last check's result. */
    @Serializable
    data class StatusDto(
        val currentVersion: String,
        val channel: String,
        val autoApply: Boolean,
        val tokenState: TokenState,
        /** ISO-8601 instant, or null when no check has ever run. */
        val lastCheckedAt: String?,
        /** The machine-readable outcome word — `UP_TO_DATE`, `UPDATE_STAGED`, … */
        val lastCheckOutcome: String?,
        /**
         * When the last unattended install was refused, which condition refused it — an
         * `UpdateBlockedReason` name; null otherwise.
         *
         * It is the nightly counterpart of the `FieldError("preflight", …)` that a manual apply
         * gets back in its 409. Without it a shop that is turned away every night at 03:00 reads
         * "staged, ready to install" and never learns that anything is being attempted at all.
         */
        val lastBlockedReason: String? = null,
        val availableVersion: String?,
        val availableNotes: String?,
        /** True when a verified artifact for [availableVersion] is on disk and ready to apply. */
        val staged: Boolean,
        /** `versionCode` of the Android APK held ready for tills, or null when none is staged. Display only. */
        val stagedClientVersionCode: Int? = null,
        /**
         * What is being downloaded right now, or null when nothing is. In-memory on the server, so
         * it describes this process's current staging run -- a manual stage, the nightly check, or
         * one started from another page -- and is gone as soon as that run ends.
         */
        val download: DownloadProgressDto? = null,
    )

    /** Which artifact a running download is fetching. */
    @Serializable
    enum class DownloadPhase {
        /** The server update itself (the jar, or the changed ranges of it). */
        SERVER,

        /** The Android client build that tills fetch after the server has restarted. */
        ANDROID,
    }

    /**
     * Progress of the staging run in flight.
     *
     * [bytesTotal] is null when the size is not known up front (a download whose response carried
     * no length, or the archive fallback, which is read whole). A client shows an indeterminate bar
     * then, never a guessed percentage.
     */
    @Serializable
    data class DownloadProgressDto(
        val phase: DownloadPhase,
        val bytesDone: Long,
        val bytesTotal: Long? = null,
    )

    /**
     * The Android build a pos-server holds ready for tills to download.
     *
     * Tills decide whether to update from [versionCode] alone. [versionName] tracks the release and
     * is shown to the operator; it may be ahead of the till's own versionName after a server-only
     * release, which is expected.
     */
    @Serializable
    data class AndroidClientDto(
        val versionCode: Int,
        val versionName: String,
        /** Lower-case hex SHA-256 of the APK. */
        val sha256: String,
        val sizeBytes: Long,
        val releaseNotes: String,
    )

    /** The operator-editable update source. Carries no token — see the container's KDoc. */
    @Serializable
    data class SettingsDto(
        val channel: String,
        val repoOwner: String,
        val repoName: String,
        val apiBaseUrl: String,
        val autoApply: Boolean,
        val tokenState: TokenState,
        /** Masked, at most the last four characters; null unless [tokenState] is `SET`. */
        val tokenPreview: String?,
        /** ISO-8601 instant the token was stored, or null. */
        val tokenSetAt: String?,
    )

    /**
     * Replaces the whole editable settings block; the token is untouched by this route.
     *
     * There is no repository here: it follows the [channel] (see `UpdateChannel`). Accepting one
     * would let a caller send a channel and a repository that disagree — the defect that made the
     * internal channel query the public repository — and a field the client cannot meaningfully
     * set is worse than no field, because it looks like it works.
     */
    @Serializable
    data class UpdateSettingsRequestDto(
        val channel: String,
        val apiBaseUrl: String,
        val autoApply: Boolean,
    )

    /** The one inbound shape carrying the credential. */
    @Serializable
    data class SetTokenRequestDto(
        val token: String,
    )

    /** The outcome of a manual check or stage, in the same words the nightly job records. */
    @Serializable
    data class CheckResultDto(
        val summary: String,
        val status: StatusDto,
    )

    /**
     * Acknowledges an apply that is under way.
     *
     * The process exits while installing, so this response is written before the swap and the
     * connection is dropped mid-flight. A client must treat a lost connection here as success in
     * progress and poll `/api/update/status` after the restart.
     */
    @Serializable
    data class ApplyStartedDto(
        val version: String,
        val message: String,
    )
}
