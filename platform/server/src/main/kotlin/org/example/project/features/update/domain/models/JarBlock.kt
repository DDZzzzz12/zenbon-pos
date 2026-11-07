package org.example.project.features.update.domain.models

/**
 * One entry's local block: the bytes from its local file header up to the next record.
 *
 * [length] deliberately spans the trailing data descriptor that shadowJar writes (general-purpose
 * flag bit 3), which is why it is measured from the *next* entry's offset rather than computed
 * from the header. Computing it as `30 + name + extra + compressedSize` truncates every entry.
 *
 * The four identity fields are what a delta client can compare without downloading anything:
 * they all come from the central directory. See `DeltaPlanner`.
 */
data class JarBlock(
    val name: String,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val offset: Long,
    val length: Long,
) {
    /** Everything except position: two blocks with equal identity hold equal bytes. */
    val identity: String get() = "$name|$crc|$compressedSize|$uncompressedSize|$length"
}
