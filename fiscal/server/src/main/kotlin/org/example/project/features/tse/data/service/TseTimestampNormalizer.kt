/**
 * Shared TSE timestamp normalization helper.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.data.service

/**
 * Normalizes TSE-provided timestamps to epoch seconds.
 *
 * RA-3: `TSE_ZEITFORMAT` is declared as `unixTime` (epoch seconds) in the DSFinV-K
 * export, but the TSE provider's SIMULATION mode can hand back epoch milliseconds
 * for [org.example.project.core.domain.dto.tse.TseDto.SignResultDto.timestamp]
 * (real NORMAL-mode hardware already returns seconds). Any value implausibly far in
 * the future as seconds (year ~33658, i.e. > 1e12) is treated as millis and divided
 * down; a genuine epoch-seconds value (~1.7e9 today) is left untouched.
 *
 * Every write site that persists a TSE-provided timestamp (`tseTimestamp` /
 * `tseStartTimestamp`) — across both the synchronous TSE service
 * ([RealTseTransactionServiceImpl]) and the Nachsignieren queue worker
 * ([TseSigningQueueServiceImpl]) — must route through [toEpochSeconds] so a single
 * heuristic governs the whole system.
 */
object TseTimestampNormalizer {
    // A genuine epoch-SECONDS value is ~1.7e9 today; epoch-MILLIS is ~1.7e12. This
    // threshold (year ~33658 as seconds) sits far above any real seconds value, so it
    // only ever trips on a millis value.
    private const val MILLIS_HEURISTIC_THRESHOLD = 1_000_000_000_000L

    fun toEpochSeconds(timestamp: Long?): Long? {
        if (timestamp == null) return null
        return if (timestamp > MILLIS_HEURISTIC_THRESHOLD) timestamp / 1000 else timestamp
    }
}
