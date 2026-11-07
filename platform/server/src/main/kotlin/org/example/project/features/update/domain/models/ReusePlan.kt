package org.example.project.features.update.domain.models

/**
 * What a delta download will copy from the installed jar and what it must fetch.
 *
 * [reuse] pairs each target block with the offset it is read from in the installed jar; see
 * [BlockCopy] for why both offsets are needed.
 *
 * [fetchBytes] is the size of [fetch] — the bytes that actually go over the wire, *including* the
 * unwanted data the gap tolerance swallows when it merges two ranges. It is deliberately not the
 * size of the wanted blocks: it is the decision input for the bail-out in `UpdateServiceImpl`,
 * where past roughly half the file many small ranges cost more than one sequential download, and
 * that comparison only makes sense against what is really transferred.
 */
data class ReusePlan(
    val reuse: List<BlockCopy>,
    val fetch: List<ByteRange>,
    val fetchBytes: Long,
)
