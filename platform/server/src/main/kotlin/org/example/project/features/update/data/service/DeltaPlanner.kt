package org.example.project.features.update.data.service

import org.example.project.features.update.domain.models.BlockCopy
import org.example.project.features.update.domain.models.ByteRange
import org.example.project.features.update.domain.models.JarBlock
import org.example.project.features.update.domain.models.ReusePlan

/**
 * Decides which of the target jar's blocks the installed jar already holds.
 *
 * ### The key is the directory, not the bytes
 *
 * A client cannot compare against bytes it has not downloaded, so a block is reusable when its
 * name, CRC32, compressed size, uncompressed size and block length all match — every one of them
 * published in the central directory. Measured over the real v1.0.9 -> v1.0.10 upgrade this
 * selected exactly the 31,817 byte-identical blocks of 32,321, with zero false positives. Nothing
 * rests on that being perfect: a false positive costs a failed digest check and a fallback to the
 * full download, never a wrong install.
 *
 * ### Duplicates are consumed, not looked up
 *
 * The fat jar carries several entries called `META-INF/LICENSE` (shadowJar runs with
 * `DuplicatesStrategy.INCLUDE`). A name-keyed map would hand the same local block to two target
 * blocks and drop a third, so each local block is removed from the pool as it is claimed.
 */
class DeltaPlanner(
    private val gapToleranceBytes: Long = DEFAULT_GAP_TOLERANCE,
    private val maxRangeBytes: Long = DEFAULT_MAX_RANGE,
) {
    /**
     * Plans the upgrade from [local] to [target].
     *
     * @param local the structure of the *installed* jar — the one already on disk, whose blocks
     *   may be copied instead of downloaded.
     * @param target the structure of the *published* jar — the one being upgraded to, whose blocks
     *   the returned plan describes and whose byte ranges the fetch list addresses.
     *
     * The two parameters have the same type and swapping them yields a structurally valid but
     * semantically wrong plan, so read the order off this doc rather than off the signature.
     */
    fun plan(
        local: JarStructure,
        target: JarStructure,
    ): ReusePlan {
        val pool = local.blocks.groupByTo(mutableMapOf()) { it.identity }
        val reuse = mutableListOf<BlockCopy>()
        val missing = mutableListOf<JarBlock>()

        target.blocks.forEach { block ->
            val claimed = pool[block.identity]?.removeFirstOrNull()
            if (claimed == null) missing += block else reuse += BlockCopy(block, claimed.offset)
        }

        // Zero-length misses are dropped, never fetched. shadowJar points several central-directory
        // records at one shared local block, so all but one of each offset-group spans no bytes: the
        // reconstruction is identical whether such a block is fetched or reused. Keeping them costs
        // twice. An empty range between two real ones acts as a stepping stone through coalesce and
        // silently doubles the effective gap tolerance, and an isolated one renders as the malformed
        // header `bytes=5000-4999`, which most CDNs answer with 400 for the entire request.
        val fetchable = missing.filter { it.length > 0 }
        val ranges = coalesce(fetchable.map { ByteRange(it.offset, it.offset + it.length) })
        return ReusePlan(reuse = reuse, fetch = ranges, fetchBytes = ranges.sumOf { it.length })
    }

    /**
     * Merges ranges separated by less than [gapToleranceBytes] of unwanted data.
     *
     * Fetching a few unwanted kilobytes is cheaper than a second HTTPS round trip. Measured on the
     * real upgrade: 504 blocks become 57 requests at 4 KB (1.47 MB), or 24 requests at 64 KB but
     * 2.29 MB — so the tolerance stays small.
     *
     * The merged result is then cut back to [maxRangeBytes]-sized pieces by [split]: merging is
     * unbounded, and one range is one allocation.
     */
    private fun coalesce(ranges: List<ByteRange>): List<ByteRange> {
        val sorted = ranges.sortedBy { it.start }
        val merged = mutableListOf<ByteRange>()
        sorted.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.start - last.endExclusive <= gapToleranceBytes) {
                merged[merged.lastIndex] = last.copy(endExclusive = maxOf(last.endExclusive, range.endExclusive))
            } else {
                merged += range
            }
        }
        return merged.flatMap(::split)
    }

    /**
     * Cuts a range into contiguous pieces of at most [maxRangeBytes].
     *
     * A range is materialised whole — once as the ktor channel's buffer, once as the `ByteArray`
     * the reconstructor writes — so its size is an allocation, not just a request size. Nothing
     * bounded it before: with the bail-out fraction at 0.5 a single coalesced range may legitimately
     * span half the jar (~57 MB measured), buffered twice. The failure mode is the one the fallback
     * cannot catch, because `OutOfMemoryError` is an `Error` and `stageByDelta` catches `Exception`:
     * the delta would not fall back to the full download, it would take the process down. Capping
     * the allocation is the fix; catching `Error` is not.
     *
     * The pieces are contiguous and cover exactly the original span, so the reconstructor's
     * "the plan must tile the body with no holes" check sees the same coverage it did before —
     * splitting adds boundaries, never gaps. Each piece is written at its own absolute offset, which
     * is where its bytes belong regardless of how the fetch was chopped up.
     */
    private fun split(range: ByteRange): List<ByteRange> {
        if (range.length <= maxRangeBytes) return listOf(range)
        val pieces = mutableListOf<ByteRange>()
        var start = range.start
        while (start < range.endExclusive) {
            val end = minOf(start + maxRangeBytes, range.endExclusive)
            pieces += ByteRange(start, end)
            start = end
        }
        return pieces
    }

    companion object {
        const val DEFAULT_GAP_TOLERANCE: Long = 4096

        /**
         * The largest single range a plan may ask for: 16 MiB.
         *
         * Small enough that two copies of it are unremarkable on a POS host's heap, large enough
         * that the request count stays in the tens rather than the thousands — the real upgrade's
         * 1.47 MB of fetches is nowhere near it, so on a normal delta this constant changes nothing
         * at all. It only bites on the pathological plan, which is exactly where it is needed.
         */
        const val DEFAULT_MAX_RANGE: Long = 16L * 1024 * 1024
    }
}
