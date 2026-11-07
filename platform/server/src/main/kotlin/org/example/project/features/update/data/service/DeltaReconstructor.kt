package org.example.project.features.update.data.service

import org.example.project.features.update.domain.models.ByteRange
import org.example.project.features.update.domain.models.ReusePlan
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Writes the target jar from the installed jar plus fetched ranges, and returns its SHA-256.
 *
 * ### Why the output is byte-identical
 *
 * The published central directory states every block's exact offset, so each block is written
 * where the publisher put it and the directory is appended verbatim at
 * [JarStructure.centralDirectoryOffset]. Nothing is re-compressed and no offset is recomputed.
 * That is what lets the caller verify the result against the release's own `SHA256SUMS` line
 * rather than inventing a second notion of correctness.
 *
 * ### Where a reused block is read from
 *
 * From [org.example.project.features.update.domain.models.BlockCopy.localOffset], never from a
 * lookup. `DeltaPlanner` paired each target block with a local one by consuming a pool, because
 * the fat jar holds 124 duplicated names (`META-INF/LICENSE` appears 25 times) and any keyed
 * lookup would hand one local block to several target blocks and drop the rest. Re-deriving the
 * pairing here would duplicate that reasoning in a second place, where it could be "simplified"
 * back into the bug. Zero-length blocks — 35 in the real jar, several directory records sharing
 * one local block — need no special case: copying no bytes is a no-op.
 *
 * ### Why it hashes in a second pass
 *
 * Blocks are not written in file order: every reused block goes down first, then the fetched
 * ranges, which interleave with the reused ones in target order. A digest therefore cannot be
 * accumulated as we go, and the file is streamed once more at the end — a sequential read of
 * ~119 MB, against a download the whole point of which was to avoid ~119 MB.
 *
 * ### Why the plan is checked to cover the whole body
 *
 * [RandomAccessFile.setLength] pre-extends the output with zeroes, so any byte the plan forgets
 * silently stays zero: a dropped block, a plan built against a different target, or a jar with a
 * prefix before its first local header would all produce a plausible file. The digest check
 * downstream does catch it, but only as "the whole download is wrong". Asserting up front that
 * the reuse and fetch spans tile `[0, centralDirectoryOffset)` names the first byte that would
 * never have been written instead.
 *
 * ### On failure, nothing survives
 *
 * The rebuild happens in a sibling `.part` file and is moved into place only once the whole jar
 * has been written; any failure deletes it and propagates. A partially written jar is
 * indistinguishable from a good one by size or name, and the caller's answer to *any* failure is
 * a full download, so leaving debris that a later run could mistake for progress buys nothing.
 * An earlier output at the destination is likewise left untouched until the move.
 */
class DeltaReconstructor(
    private val fetch: suspend (ByteRange) -> ByteArray,
) {
    suspend fun reconstruct(
        installedJar: File,
        target: JarStructure,
        plan: ReusePlan,
        into: File,
    ): String {
        into.parentFile?.mkdirs()
        val partial = File(into.parentFile, into.name + PART_SUFFIX)
        partial.delete()
        try {
            write(installedJar, target, plan, partial)
            val digest = sha256(partial)
            Files.move(
                partial.toPath(),
                into.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return digest
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    private suspend fun write(
        installedJar: File,
        target: JarStructure,
        plan: ReusePlan,
        partial: File,
    ) {
        requireWholeBodyCovered(target, plan)
        RandomAccessFile(partial, "rw").use { out ->
            out.setLength(target.totalSize)
            copyReusedBlocks(installedJar, plan, out)
            plan.fetch.forEach { range ->
                out.seek(range.start)
                out.write(fetched(range))
            }
            out.seek(target.centralDirectoryOffset)
            out.write(target.centralDirectory)
        }
    }

    private fun copyReusedBlocks(
        installedJar: File,
        plan: ReusePlan,
        out: RandomAccessFile,
    ) {
        RandomAccessFile(installedJar, "r").use { source ->
            plan.reuse.forEach { copy ->
                if (copy.block.length == 0L) return@forEach
                require(copy.block.length <= Int.MAX_VALUE) {
                    "block '${copy.block.name}' spans ${copy.block.length} bytes, more than one array can hold"
                }
                val bytes = ByteArray(copy.block.length.toInt())
                source.seek(copy.localOffset)
                source.readFully(bytes)
                out.seek(copy.block.offset)
                out.write(bytes)
            }
        }
    }

    /**
     * Fails unless the plan's spans tile the target's body exactly.
     *
     * Reused blocks and fetched ranges are the only two things that write below
     * [JarStructure.centralDirectoryOffset], so together they must leave no gap: the first byte
     * neither of them covers is a byte the output would keep as one of [RandomAccessFile.setLength]'s
     * zeroes. Overlap is expected and fine — the planner coalesces two wanted ranges across the
     * reused block between them, deliberately re-fetching those bytes — so the spans are merged,
     * not counted. Zero-length blocks span nothing and drop out.
     */
    private fun requireWholeBodyCovered(
        target: JarStructure,
        plan: ReusePlan,
    ) {
        val body = target.centralDirectoryOffset
        val reused =
            plan.reuse
                .filter { it.block.length > 0 }
                .map { it.block.offset to it.block.offset + it.block.length }
        val fetchedSpans = plan.fetch.map { it.start to it.endExclusive }
        val spans = (reused + fetchedSpans).sortedBy { it.first }

        var covered = 0L
        spans.forEach { (start, end) ->
            check(start <= covered) { uncovered(covered, start, body) }
            if (end > covered) covered = end
        }
        check(covered >= body) { uncovered(covered, body, body) }
    }

    private fun uncovered(
        from: Long,
        until: Long,
        body: Long,
    ): String = "the plan never writes bytes $from until $until of the target's $body-byte body"

    /**
     * The range's bytes, or an error.
     *
     * `GithubReleaseClient.downloadRange` already refuses a body of the wrong length, but this
     * class takes a plain lambda and must not trust it: writing a short answer would leave the
     * tail of the block as the zeroes [RandomAccessFile.setLength] wrote, and the only symptom
     * would be a digest mismatch with nothing to say which range was at fault.
     */
    private suspend fun fetched(range: ByteRange): ByteArray {
        val bytes = fetch(range)
        check(bytes.size.toLong() == range.length) {
            "range ${range.toHeaderValue()} answered ${bytes.size} bytes, expected ${range.length}"
        }
        return bytes
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(), digest).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (stream.read(buffer) >= 0) Unit
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
