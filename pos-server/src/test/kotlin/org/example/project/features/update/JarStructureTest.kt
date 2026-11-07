package org.example.project.features.update

import org.example.project.features.update.data.service.JarStructure
import org.junit.jupiter.api.Tag
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JarStructureTest {
    private val dir: File = Files.createTempDirectory("jarstructure").toFile()

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun `every block starts at a local file header and carries the entry's own fields`() {
        val jar = JarFixtures.writeJar(File(dir, "a.jar"), JarFixtures.THREE_ENTRIES)
        val structure = JarStructure.of(jar)

        assertEquals(3, structure.blocks.size)
        assertEquals(0L, structure.blocks.first().offset)
        assertLocalHeadersAt(jar, structure)
        assertFieldsMatchZipFile(jar, structure)
    }

    @Test fun `a block length spans its trailing data descriptor`() {
        val jar = JarFixtures.writeJar(File(dir, "b.jar"), JarFixtures.THREE_ENTRIES)
        val structure = JarStructure.of(jar)

        // 30-byte local header + name + extra + compressed data is the NAIVE length; the real
        // block also carries the descriptor, so every block must be strictly longer than that.
        //
        // Scoped to the fixtures on purpose: it does NOT hold on the real shadowJar, where several
        // central-directory records share one local block and all but one of each group therefore
        // has length 0. See `the real shadowJar has central-directory records sharing one block`.
        structure.blocks.forEach { block ->
            assertTrue(
                block.length > 30 + block.name.length + block.compressedSize,
                "block ${block.name} is missing its data descriptor",
            )
        }
        assertDescriptorEndsEachBlock(jar, structure)
    }

    @Test fun `duplicate entry names are kept as separate blocks`() {
        val jar = JarFixtures.writeJar(File(dir, "c.jar"), JarFixtures.WITH_DUPLICATES)
        val structure = JarStructure.of(jar)

        assertEquals(3, structure.blocks.size)
        assertEquals(2, structure.blocks.count { it.name == "META-INF/LICENSE" })
        assertDescriptorEndsEachBlock(jar, structure)
    }

    @Test fun `parsing a published central directory yields the same blocks as reading the file`() {
        val jar = JarFixtures.writeJar(File(dir, "d.jar"), JarFixtures.WITH_DUPLICATES)
        val fromFile = JarStructure.of(jar)
        val fromBlob = JarStructure.parse(fromFile.centralDirectory)

        assertEquals(fromFile.blocks, fromBlob.blocks)
        assertEquals(fromFile.centralDirectoryOffset, fromBlob.centralDirectoryOffset)
        assertEquals(fromFile.totalSize, fromBlob.totalSize)
    }

    @Test fun `a zip64 archive parses from its file and from its directory blob alike`() {
        // Synthetic on purpose: neither the local shadowJar nor any published release artifact is
        // zip64 yet (no PK\x06\x06 record, a real 32-bit directory offset), so zip64 support would
        // otherwise ship completely unproven — and the jar is climbing towards the 65,535-entry
        // limit that forces it.
        val jar = JarFixtures.writeZip64Jar(File(dir, "z.jar"), JarFixtures.THREE_ENTRIES)
        assertTrue(containsSignature(jar, ZIP64_EOCD), "fixture is not zip64: no PK\\x06\\x06 record")
        assertEquals(
            UNSET_32,
            eocdDirectoryOffsetField(jar),
            "fixture's EOCD still carries a 32-bit directory offset, so the zip64 branch never runs",
        )

        val fromFile = JarStructure.of(jar)
        val fromBlob = JarStructure.parse(fromFile.centralDirectory)

        assertEquals(3, fromFile.blocks.size)
        assertEquals(0L, fromFile.blocks.first().offset)
        assertLocalHeadersAt(jar, fromFile)
        assertFieldsMatchZipFile(jar, fromFile)
        assertEquals(fromFile.blocks, fromBlob.blocks)
        assertEquals(fromFile.centralDirectoryOffset, fromBlob.centralDirectoryOffset)
        assertEquals(jar.length(), fromBlob.totalSize)
    }

    @Test fun `a directory blob missing a record is refused`() {
        // The count in the EOCD is the directory's own statement about itself, and it is free to
        // check: without it, a blob that lost a record parses into a shorter, plausible directory.
        val jar = JarFixtures.writeJar(File(dir, "e.jar"), JarFixtures.THREE_ENTRIES)
        val blob = JarStructure.readCentralDirectory(jar)
        val corrupt = blob.copyOfRange(0, recordStarts(blob).last()) + blob.copyOfRange(eocdIndex(blob), blob.size)

        val failure = assertFailsWith<IllegalArgumentException> { JarStructure.parse(corrupt) }

        assertEquals("central directory declares 3 entries but 2 parsed", failure.message)
    }

    @Test fun `unexplained bytes before the end-of-central-directory record are refused`() {
        val jar = JarFixtures.writeJar(File(dir, "f.jar"), JarFixtures.THREE_ENTRIES)
        val blob = JarStructure.readCentralDirectory(jar)
        val eocd = eocdIndex(blob)
        val corrupt = blob.copyOfRange(0, eocd) + ByteArray(8) + blob.copyOfRange(eocd, blob.size)

        val failure = assertFailsWith<IllegalArgumentException> { JarStructure.parse(corrupt) }

        assertEquals(
            "central-directory walk stopped at $eocd, leaving 8 unexplained bytes " +
                "before the end-of-central-directory record at ${eocd + 8}",
            failure.message,
        )
    }

    @Test fun `a directory blob truncated mid-record is refused`() {
        val jar = JarFixtures.writeJar(File(dir, "g.jar"), JarFixtures.THREE_ENTRIES)
        val blob = JarStructure.readCentralDirectory(jar)
        val eocd = eocdIndex(blob)
        // Six bytes of the final record's name are gone; its declared lengths now overrun the EOCD.
        val corrupt = blob.copyOfRange(0, eocd - 6) + blob.copyOfRange(eocd, blob.size)

        val failure = assertFailsWith<IllegalArgumentException> { JarStructure.parse(corrupt) }

        assertEquals(
            "central-directory record at ${recordStarts(blob).last()} runs past the end of the directory at ${eocd - 6}",
            failure.message,
        )
    }

    @Test fun `a zip64 extra field too short for its values is refused`() {
        // A record claiming dataLen == 4 with the sentinels present used to read the NEXT record's
        // bytes as an offset: a silently wrong block boundary, indistinguishable from a real one.
        val jar = JarFixtures.writeZip64Jar(File(dir, "h.jar"), JarFixtures.THREE_ENTRIES)
        val blob = JarStructure.readCentralDirectory(jar)
        val record = recordStarts(blob).first()
        val extra = record + 46 + uint16(blob, record + 28)
        assertEquals(1, uint16(blob, extra), "fixture's first extra field is not the zip64 one")
        val corrupt = blob.copyOf().also { writeUint16(it, extra + 2, 4) }

        val failure = assertFailsWith<IllegalArgumentException> { JarStructure.parse(corrupt) }

        assertEquals(
            "zip64 extra field of 'META-INF/MANIFEST.MF' declares 4 bytes, too few for the " +
                "3 64-bit value(s) the record's 0xFFFFFFFF fields need",
            failure.message,
        )
    }

    @Test fun `an entry offset beyond the central directory is refused`() {
        // An offset past the directory means a block reaching into the directory itself, and a
        // NEGATIVE length for the entry that precedes it once the records are sorted.
        val jar = JarFixtures.writeJar(File(dir, "i.jar"), JarFixtures.THREE_ENTRIES)
        val blob = JarStructure.readCentralDirectory(jar)
        val directoryOffset = JarStructure.parse(blob).centralDirectoryOffset
        val corrupt = blob.copyOf().also { writeUint32(it, recordStarts(blob)[1] + 42, directoryOffset + 1) }

        val failure = assertFailsWith<IllegalArgumentException> { JarStructure.parse(corrupt) }

        assertEquals(
            "entry 'org/example/A.class' claims offset ${directoryOffset + 1}, " +
                "outside the 0..$directoryOffset the file's $directoryOffset-byte body allows",
            failure.message,
        )
    }

    @Tag("fatjar")
    @Test
    fun `parses a large real fat jar with duplicate names and data descriptors`() {
        val real = JarFixtures.requireShadowJar()
        val structure = JarStructure.of(real)

        assertTrue(structure.blocks.size > 10_000, "expected a fat jar, got ${structure.blocks.size} blocks")
        assertEquals(0L, structure.blocks.first().offset)
        assertTrue(
            structure.blocks
                .groupingBy { it.name }
                .eachCount()
                .any { it.value > 1 },
            "expected DuplicatesStrategy.INCLUDE to leave duplicate names",
        )
        assertLocalHeadersAt(real, structure)
        assertFieldsMatchZipFile(real, structure)
        assertEquals(structure.blocks, JarStructure.parse(structure.centralDirectory).blocks)
    }

    @Tag("fatjar")
    @Test
    fun `the real shadowJar has central-directory records sharing one block`() {
        val real = JarFixtures.requireShadowJar()
        val structure = JarStructure.of(real)

        // shadowJar emits several directory records pointing at ONE local block (six for
        // META-INF/ASL-2.0.txt, seven for META-INF/LICENSE, ...). All but one of each group get
        // length 0, which is load-bearing for the planner and the reconstructor: a zero-length
        // block contributes no bytes, and the single non-zero block covers the whole span.
        assertTrue(structure.blocks.any { it.length == 0L }, "expected shared local blocks")
        structure.blocks.groupBy { it.offset }.forEach { (offset, group) ->
            assertEquals(
                1,
                group.count { it.length > 0L },
                "offset $offset is shared by ${group.size} records with more than one non-zero length",
            )
        }
    }

    /**
     * A stray zip64 end-of-central-directory signature must not be mistaken for the real record.
     *
     * Those four bytes are legal content. Here they sit inside an entry NAME, which the central
     * directory stores verbatim, but a CRC or an extra field would serve just as well — and the
     * blob being scanned is several megabytes of exactly such data. A backward scan that takes the
     * last occurrence anywhere reads this one as the zip64 record, moves the directory's end back
     * to it and throws: safe, but it costs a ~114 MB full download for a jar that is not zip64 at
     * all, at roughly a 0.1 % per-release rate. The real record states its own length and the
     * format fixes what follows it, so a candidate is accepted only where that length lands on the
     * locator or on the EOCD — which coincidental bytes do not.
     */
    @Test fun `a spurious zip64 signature in the directory does not break a non-zip64 jar`() {
        val jar =
            JarFixtures.writeJar(
                File(dir, "spurious.jar"),
                listOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
                    "org/example/PK\u0006\u0006Trap.class" to "a body",
                    "org/example/B.class" to "class B body",
                ),
            )

        // The fixture proves nothing unless the bytes really are in the blob the parser scans.
        val blob = JarStructure.readCentralDirectory(jar)
        val zip64Signature = byteArrayOf(0x50, 0x4B, 0x06, 0x06)
        assertTrue(
            (0..blob.size - 4).any { at -> blob.copyOfRange(at, at + 4).contentEquals(zip64Signature) },
            "the fixture must actually contain a zip64 end-of-central-directory signature",
        )

        val structure = JarStructure.of(jar)

        assertEquals(3, structure.blocks.size)
        assertEquals(jar.length(), structure.totalSize)
        assertLocalHeadersAt(jar, structure)
        assertEquals(structure.blocks, JarStructure.parse(structure.centralDirectory).blocks)
    }

    /** Independent oracle: an offset is only right if real local-header bytes sit there. */
    private fun assertLocalHeadersAt(
        jar: File,
        structure: JarStructure,
    ) {
        RandomAccessFile(jar, "r").use { file ->
            val signature = ByteArray(4)
            structure.blocks.forEach { block ->
                file.seek(block.offset)
                file.readFully(signature)
                assertTrue(
                    signature.contentEquals(LOCAL_HEADER),
                    "block ${block.name} at ${block.offset} does not start with a local file header",
                )
            }
        }
    }

    /** Independent oracle: the JDK's own zip reader must report the same name, crc and sizes. */
    private fun assertFieldsMatchZipFile(
        jar: File,
        structure: JarStructure,
    ) {
        val oracle =
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map { fingerprint(it.name, it.crc, it.compressedSize, it.size) }
                    .sorted()
                    .toList()
            }
        val parsed =
            structure.blocks.map { fingerprint(it.name, it.crc, it.compressedSize, it.uncompressedSize) }.sorted()
        assertEquals(oracle, parsed)
    }

    /** Every block ends with the 16-byte data descriptor `PK\x07\x08` + crc + the two sizes. */
    private fun assertDescriptorEndsEachBlock(
        jar: File,
        structure: JarStructure,
    ) {
        RandomAccessFile(jar, "r").use { file ->
            val signature = ByteArray(4)
            structure.blocks.forEach { block ->
                file.seek(block.offset + block.length - DESCRIPTOR_SIZE)
                file.readFully(signature)
                assertTrue(
                    signature.contentEquals(DATA_DESCRIPTOR),
                    "block ${block.name} does not end with a $DESCRIPTOR_SIZE-byte data descriptor",
                )
            }
        }
    }

    /** Where each central-directory record begins, walked exactly as the parser walks it. */
    private fun recordStarts(blob: ByteArray): List<Int> {
        val starts = mutableListOf<Int>()
        var p = 0
        while (p + 46 <= blob.size && uint32(blob, p) == CD_SIGNATURE) {
            starts += p
            p += 46 + uint16(blob, p + 28) + uint16(blob, p + 30) + uint16(blob, p + 32)
        }
        return starts
    }

    private fun eocdIndex(blob: ByteArray): Int = (blob.size - 4 downTo 0).first { at -> blob.copyOfRange(at, at + 4).contentEquals(EOCD) }

    private fun uint16(
        blob: ByteArray,
        at: Int,
    ) = ByteBuffer
        .wrap(blob, at, 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .short
        .toInt() and 0xFFFF

    private fun uint32(
        blob: ByteArray,
        at: Int,
    ) = ByteBuffer
        .wrap(blob, at, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and UNSET_32

    private fun writeUint16(
        blob: ByteArray,
        at: Int,
        value: Int,
    ) {
        ByteBuffer.wrap(blob, at, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }

    private fun writeUint32(
        blob: ByteArray,
        at: Int,
        value: Long,
    ) {
        ByteBuffer.wrap(blob, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    }

    private fun fingerprint(
        name: String,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
    ) = "$name|$crc|$compressedSize|$uncompressedSize"

    /** The classic EOCD's central-directory offset field; 0xFFFFFFFF means "read the zip64 record". */
    private fun eocdDirectoryOffsetField(jar: File): Long {
        val bytes = jar.readBytes()
        val eocd =
            (bytes.size - 4 downTo 0).first { at -> bytes.copyOfRange(at, at + 4).contentEquals(EOCD) }
        return ByteBuffer
            .wrap(bytes, eocd + 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and UNSET_32
    }

    private fun containsSignature(
        jar: File,
        signature: ByteArray,
    ): Boolean {
        val bytes = jar.readBytes()
        for (i in 0..bytes.size - signature.size) {
            if (bytes.copyOfRange(i, i + signature.size).contentEquals(signature)) return true
        }
        return false
    }

    private companion object {
        /** `PK\x03\x04` — the local file header signature. */
        val LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** `PK\x06\x06` — the zip64 end-of-central-directory record signature. */
        val ZIP64_EOCD = byteArrayOf(0x50, 0x4B, 0x06, 0x06)

        /** `PK\x05\x06` — the classic end-of-central-directory record signature. */
        val EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

        /** `PK\x07\x08` — the data-descriptor signature that closes a streamed entry's block. */
        val DATA_DESCRIPTOR = byteArrayOf(0x50, 0x4B, 0x07, 0x08)

        /** Signature + crc + compressed size + uncompressed size, all 32-bit. */
        const val DESCRIPTOR_SIZE = 16

        /** `PK\x01\x02` — the central-directory record signature. */
        const val CD_SIGNATURE = 0x02014b50L

        const val UNSET_32 = 0xFFFFFFFFL
    }
}
