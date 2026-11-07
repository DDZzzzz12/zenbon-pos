package org.example.project.features.update.domain.models

/**
 * A half-open byte span of the published jar, as sent in an HTTP `Range` header.
 *
 * Both invariants are enforced here rather than at the one consumer that used to check them, so
 * every producer is guarded: a malformed span is a caller bug, and the type is the only place that
 * knows what "malformed" means. `bytes=5000-4999` -- what an empty range serializes to -- is
 * answered with a `400` for the whole request by most CDNs, and a negative start is not
 * representable in a `Range` header at all.
 */
data class ByteRange(
    val start: Long,
    val endExclusive: Long,
) {
    init {
        require(start >= 0) { "range start $start is negative" }
        require(endExclusive > start) { "refusing an empty range at offset $start" }
    }

    val length: Long get() = endExclusive - start

    /** `Range` is inclusive on both ends, unlike this type. */
    fun toHeaderValue(): String = "bytes=$start-${endExclusive - 1}"
}
