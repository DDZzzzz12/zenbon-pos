package org.example.project.features.update.domain.models

/**
 * One reused block, paired with where the reassembler must read it from.
 *
 * [block] is the *target* block: its [JarBlock.offset] is the position the bytes take in the jar
 * being downloaded. That alone is not enough to rebuild anything — the same block sits at a
 * different offset in the installed jar, and [localOffset] is that position.
 *
 * The pairing is carried rather than re-derived because the obvious re-derivation — looking the
 * target block up in the local jar by name or by [JarBlock.identity] — is exactly the bug the
 * planner exists to avoid: the fat jar holds 124 duplicated names (`META-INF/LICENSE` appears 25
 * times), and a keyed lookup hands one local block to several target blocks and drops the rest.
 * The planner consumes each local block from a pool as it is claimed, so it — and only it — knows
 * which local block belongs to which target block.
 */
data class BlockCopy(
    val block: JarBlock,
    val localOffset: Long,
)
