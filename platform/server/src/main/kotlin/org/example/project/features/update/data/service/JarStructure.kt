package org.example.project.features.update.data.service

import org.example.project.features.update.domain.models.JarBlock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A jar's byte layout, derived from its ZIP central directory.
 *
 * One parser serves both sides of a delta update: the client reads the *local* jar with [of], and
 * reads the *published* jar's layout from the `.cdir` asset with [parse]. Both produce the same
 * shape, so the planner never needs to know which is which.
 *
 * [centralDirectory] is the blob from the first central-directory record to end of file — the
 * zip64 records and EOCD included. Because the EOCD — or, on a zip64 archive, the zip64 EOCD
 * record that sits inside the same blob — states where the directory begins, the blob is
 * self-describing: [parse] needs nothing else to recover offsets and the total file size.
 *
 * [parse] is fed bytes that arrive over the network, so it treats a malformed blob as an error
 * rather than as a shape to interpret: a truncated or doctored directory throws instead of
 * yielding a plausible-but-wrong layout that would be reassembled into a corrupt jar.
 */
class JarStructure(
    val blocks: List<JarBlock>,
    val centralDirectory: ByteArray,
    val centralDirectoryOffset: Long,
) {
    val totalSize: Long get() = centralDirectoryOffset + centralDirectory.size

    companion object {
        private const val CD_SIGNATURE = 0x02014b50
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50

        /** The zip64 locator is a fixed 20 bytes: signature, disk, offset, disk count. */
        private const val ZIP64_LOCATOR_SIZE = 20

        /**
         * `size of zip64 end of central directory record` counts the bytes *after* the signature
         * and the size field itself, so the record ends at `index + 12 + size`.
         */
        private const val ZIP64_EOCD_PREFIX = 12

        /** The smallest legal value of that size field: the fixed fields through the CD offset. */
        private const val ZIP64_EOCD_MIN_SIZE = 44L
        private const val ZIP64_EXTRA_ID = 1

        /**
         * How far back from end of file the end-of-central-directory records can begin.
         *
         * 22 bytes of EOCD + a 65,535-byte maximum comment, plus the 56-byte zip64 EOCD record and
         * its 20-byte locator, which sit *before* the classic EOCD and so fall outside a
         * comment-sized window on a commented zip64 archive.
         */
        private const val MAX_TAIL_WINDOW = 22 + 65_535 + 56 + 20

        private const val UNSET_16 = 0xFFFF
        private const val UNSET_32 = 0xFFFFFFFFL

        /** Reads [jar]'s own central directory and parses it. */
        fun of(jar: File): JarStructure = parse(readCentralDirectory(jar))

        /** The bytes from the first central-directory record to end of file. */
        fun readCentralDirectory(jar: File): ByteArray =
            RandomAccessFile(jar, "r").use { file ->
                val size = file.length()
                val tailSize = minOf(size, MAX_TAIL_WINDOW.toLong()).toInt()
                val tail = ByteArray(tailSize)
                file.seek(size - tailSize)
                file.readFully(tail)

                val offset = centralDirectoryOffsetIn(tail, eocdIndexIn(tail))
                val blob = ByteArray((size - offset).toInt())
                file.seek(offset)
                file.readFully(blob)
                blob
            }

        /** Recovers blocks, the directory offset and the file size from a directory blob. */
        fun parse(centralDirectory: ByteArray): JarStructure {
            val eocd = eocdIndexIn(centralDirectory)
            val offset = centralDirectoryOffsetIn(centralDirectory, eocd)
            val records = readRecords(centralDirectory, eocd)
            records.forEach { record ->
                require(record.offset in 0..offset) {
                    "entry '${record.name}' claims offset ${record.offset}, " +
                        "outside the 0..$offset the file's $offset-byte body allows"
                }
            }

            val sorted = records.sortedBy { it.offset }
            val blocks =
                sorted.mapIndexed { index, record ->
                    val end = if (index + 1 < sorted.size) sorted[index + 1].offset else offset
                    val length = end - record.offset
                    require(length >= 0) {
                        "entry '${record.name}' at ${record.offset} spans a negative $length bytes, ending at $end"
                    }
                    JarBlock(
                        name = record.name,
                        crc = record.crc,
                        compressedSize = record.compressedSize,
                        uncompressedSize = record.uncompressedSize,
                        offset = record.offset,
                        length = length,
                    )
                }
            return JarStructure(blocks, centralDirectory, offset)
        }

        private fun eocdIndexIn(blob: ByteArray): Int = lastIndexOfSignature(blob, EOCD_SIGNATURE) ?: error("no end-of-central-directory record")

        /**
         * The absolute offset at which the central directory begins.
         *
         * Works on a file tail and on a published directory blob alike, because it needs no
         * knowledge of where [blob] sat in the file. The zip64 end-of-central-directory record is
         * located by scanning backwards for its signature, exactly as the EOCD itself is: it always
         * follows the directory, so it lies inside both windows. Its own locator is deliberately
         * ignored — the locator stores an *absolute* file offset, which a directory blob cannot
         * resolve.
         */
        private fun centralDirectoryOffsetIn(
            blob: ByteArray,
            eocd: Int,
        ): Long {
            val classic = uint32(blob, eocd + 16)
            if (classic != UNSET_32) return classic
            return int64(blob, zip64EocdIndexIn(blob, eocd) + 48)
        }

        /**
         * The zip64 end-of-central-directory record, or null if the archive has none.
         *
         * A backward scan for `PK\x06\x06` alone is not enough. Those four bytes are perfectly
         * legal content — a CRC, an extra field, a UTF-8 filename — and the blob this walks is
         * several megabytes of exactly such data, so a coincidental occurrence is a ~0.1 %
         * per-release event. Taken as the zip64 record it would move the directory's end and the
         * parse would throw: safe, but a release that fell back to a 114 MB download for no reason.
         *
         * So a candidate is accepted only where the structure says it must be. The record states
         * its own length, and the format fixes what follows it: the 20-byte locator, then the
         * classic EOCD. A candidate is therefore real only if `index + 12 + size` lands exactly on
         * a locator signature whose own end is the EOCD, or — for a writer that omits the locator —
         * exactly on the EOCD itself. Coincidental bytes do not describe their own length, so they
         * fail this and the archive parses as the non-zip64 archive it is.
         *
         * The scan keeps walking past a rejected candidate rather than giving up on it, because a
         * genuine record can sit before a spurious one.
         */
        private fun zip64EocdIndexOrNull(
            blob: ByteArray,
            eocd: Int,
        ): Int? {
            for (i in minOf(eocd, blob.size - 4) downTo 0) {
                if (uint32(blob, i) != ZIP64_EOCD_SIGNATURE.toLong()) continue
                if (isZip64Eocd(blob, i, eocd)) return i
            }
            return null
        }

        /** Whether the candidate at [at] declares a length that puts its end where the format demands. */
        private fun isZip64Eocd(
            blob: ByteArray,
            at: Int,
            eocd: Int,
        ): Boolean {
            if (at + ZIP64_EOCD_PREFIX > blob.size) return false
            val declared = int64(blob, at + 4)
            if (declared < ZIP64_EOCD_MIN_SIZE) return false
            val end = at.toLong() + ZIP64_EOCD_PREFIX + declared
            if (end > eocd.toLong()) return false
            val endIndex = end.toInt()
            if (endIndex == eocd) return true
            return endIndex + ZIP64_LOCATOR_SIZE == eocd &&
                endIndex + 4 <= blob.size &&
                uint32(blob, endIndex) == ZIP64_LOCATOR_SIGNATURE.toLong()
        }

        private fun zip64EocdIndexIn(
            blob: ByteArray,
            eocd: Int,
        ): Int =
            zip64EocdIndexOrNull(blob, eocd)
                ?: error("zip64 file without an end-of-central-directory record")

        /**
         * How many entries the directory says it holds.
         *
         * The classic field is 16 bits; 0xFFFF means "too many to state here", and the real count
         * lives in the zip64 record.
         */
        private fun totalEntriesIn(
            blob: ByteArray,
            eocd: Int,
        ): Long {
            val classic = uint16(blob, eocd + 10)
            if (classic != UNSET_16) return classic.toLong()
            return int64(blob, zip64EocdIndexIn(blob, eocd) + 32)
        }

        /**
         * Every central-directory record in [blob], with the walk checked against the directory's
         * own two statements about itself.
         *
         * Stopping at the first byte that is not a record signature is not enough: a truncated or
         * doctored blob then parses "successfully" into a shorter directory. So the record count
         * must match the declared total, and the walk must consume every byte up to the first
         * end-of-central-directory record — nothing unexplained may sit in between.
         */
        private fun readRecords(
            blob: ByteArray,
            eocd: Int,
        ): List<Record> {
            // On a zip64 archive the directory ends at the zip64 EOCD record; its 56 bytes and the
            // 20-byte locator sit between the last record and the classic EOCD, and are explained.
            val end = zip64EocdIndexOrNull(blob, eocd) ?: eocd
            val records = mutableListOf<Record>()
            var p = 0
            while (p + 46 <= end && uint32(blob, p) == CD_SIGNATURE.toLong()) {
                val nameLen = uint16(blob, p + 28)
                val extraLen = uint16(blob, p + 30)
                val commentLen = uint16(blob, p + 32)
                require(p + 46 + nameLen + extraLen + commentLen <= end) {
                    "central-directory record at $p runs past the end of the directory at $end"
                }
                records += readRecord(blob, p, nameLen, extraLen)
                p += 46 + nameLen + extraLen + commentLen
            }

            val declared = totalEntriesIn(blob, eocd)
            require(records.size.toLong() == declared) {
                "central directory declares $declared entries but ${records.size} parsed"
            }
            require(p == end) {
                "central-directory walk stopped at $p, leaving ${end - p} unexplained bytes " +
                    "before the end-of-central-directory record at $end"
            }
            return records
        }

        private fun readRecord(
            blob: ByteArray,
            p: Int,
            nameLen: Int,
            extraLen: Int,
        ): Record {
            val crc = uint32(blob, p + 16)
            var compressed = uint32(blob, p + 20)
            var uncompressed = uint32(blob, p + 24)
            var offset = uint32(blob, p + 42)
            val name = String(blob, p + 46, nameLen, Charsets.UTF_8)

            if (uncompressed == UNSET_32 || compressed == UNSET_32 || offset == UNSET_32) {
                val wanted = unsetAmong(uncompressed, compressed, offset)
                val values = zip64Values(blob, name, p + 46 + nameLen, extraLen, wanted)
                var taken = 0
                if (uncompressed == UNSET_32) uncompressed = values[taken++]
                if (compressed == UNSET_32) compressed = values[taken++]
                if (offset == UNSET_32) offset = values[taken]
            }
            return Record(name, crc, compressed, uncompressed, offset)
        }

        private fun unsetAmong(vararg fields: Long): Int = fields.count { it == UNSET_32 }

        /**
         * The [count] 64-bit values the zip64 extra field holds, in the order ZIP writes them.
         *
         * The field's own declared length bounds every read: a record claiming `dataLen == 4` must
         * fail loudly, not read the next record's bytes as a size or an offset — a wrong offset is
         * a wrong block boundary, and nothing downstream could tell.
         */
        private fun zip64Values(
            blob: ByteArray,
            name: String,
            extraStart: Int,
            extraLen: Int,
            count: Int,
        ): LongArray {
            val extraEnd = extraStart + extraLen
            var q = extraStart
            while (q + 4 <= extraEnd) {
                val headerId = uint16(blob, q)
                val dataLen = uint16(blob, q + 2)
                require(q + 4 + dataLen <= extraEnd) {
                    "extra field 0x${headerId.toString(16)} of '$name' declares $dataLen bytes, " +
                        "past the $extraLen-byte extra area"
                }
                if (headerId == ZIP64_EXTRA_ID) {
                    require(dataLen >= count * 8) {
                        "zip64 extra field of '$name' declares $dataLen bytes, too few for the " +
                            "$count 64-bit value(s) the record's 0xFFFFFFFF fields need"
                    }
                    return LongArray(count) { int64(blob, q + 4 + it * 8) }
                }
                q += 4 + dataLen
            }
            error("entry '$name' has 0xFFFFFFFF fields but no zip64 extra field")
        }

        private fun lastIndexOfSignature(
            blob: ByteArray,
            signature: Int,
        ): Int? {
            for (i in blob.size - 4 downTo 0) {
                if (uint32(blob, i) == signature.toLong()) return i
            }
            return null
        }

        private fun buffer(
            blob: ByteArray,
            at: Int,
        ) = ByteBuffer.wrap(blob, at, minOf(8, blob.size - at)).order(ByteOrder.LITTLE_ENDIAN)

        private fun uint16(
            blob: ByteArray,
            at: Int,
        ) = buffer(blob, at).short.toInt() and 0xFFFF

        private fun uint32(
            blob: ByteArray,
            at: Int,
        ) = buffer(blob, at).int.toLong() and 0xFFFFFFFFL

        private fun int64(
            blob: ByteArray,
            at: Int,
        ) = buffer(blob, at).long
    }

    private data class Record(
        val name: String,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val offset: Long,
    )
}
