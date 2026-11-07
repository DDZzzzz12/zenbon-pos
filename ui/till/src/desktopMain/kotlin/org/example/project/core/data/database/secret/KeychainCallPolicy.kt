package org.example.project.core.data.database.secret

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long [OsDesktopKeySource] waits for a keychain session, and how long it leaves the keychain alone after one did not
 * answer.
 *
 * @property timeout the longest a caller waits for its keychain session. Positive.
 * @property backoff the pause after the first timeout in a row; it doubles with every further one. Positive.
 * @property maxBackoff the longest pause, however many timeouts in a row. Never shorter than [backoff].
 */
data class KeychainCallPolicy(
    val timeout: Duration = 5.seconds,
    val backoff: Duration = 30.seconds,
    val maxBackoff: Duration = 30.minutes,
) {
    init {
        require(timeout > Duration.ZERO) { "The keychain timeout must be positive, was $timeout" }
        require(backoff > Duration.ZERO) { "The keychain back-off must be positive, was $backoff" }
        require(maxBackoff >= backoff) { "The longest keychain back-off ($maxBackoff) must not be shorter than the first ($backoff)" }
    }
}
