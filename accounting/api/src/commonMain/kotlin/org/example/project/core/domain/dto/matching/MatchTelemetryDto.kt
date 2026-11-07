package org.example.project.core.domain.dto.matching

import kotlinx.serialization.Serializable

/**
 * How allocations came about, in counts — the evidence for judging the suggestion ladder.
 *
 * `matched_by` alone answers only *are suggestions used at all*; the signal breakdown answers the
 * question actually worth asking, **which of the seven signals earn their place**. Counts as JSON is
 * the whole feature: there is no screen and no chart, and whoever wants a graph can build one on top.
 */
object MatchTelemetryDto {
    @Serializable
    data class ResponseDto(
        /**
         * Allocation counts keyed by `matched_by` — **every constant present, zero rather than
         * absent**. A caller reading `byMatchedBy["SUGGESTION_ACCEPTED"]` must get `0`, not null, or
         * "no suggestions accepted yet" renders as a blank where a number belongs.
         */
        val byMatchedBy: Map<String, Long> = emptyMap(),
        /**
         * Counts keyed by `matched_signal`, accepted suggestions only. Unlike [byMatchedBy] a signal
         * nobody has used is simply absent: the seven names live in the server's `MatchSignal` and
         * the ladder is expected to grow, so an exhaustive list here would be stale the day a signal
         * is added.
         */
        val bySignal: Map<String, Long> = emptyMap(),
        val total: Long = 0,
    )
}
