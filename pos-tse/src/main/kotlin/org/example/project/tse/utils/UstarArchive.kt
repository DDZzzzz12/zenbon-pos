package org.example.project.tse.utils

import java.io.ByteArrayOutputStream

/**
 * Minimal USTAR (tar) reader/writer used by the software-side filtered export on TSE 2.0
 * ([FilteredTarExport]). Only the fields that matter for reading back and repackaging a TSE
 * export are handled: member name, size and content. It is deliberately lenient on read (it does
 * not verify the header checksum) so it round-trips archives produced by real hardware and by the
 * virtual test device alike.
 */
internal object UstarArchive {

    private const val BLOCK = 512

    class Member(val name: String, val content: ByteArray)

    /** Parse a tar archive into its members, stopping at the first zero-filled terminator block. */
    fun read(tar: ByteArray): List<Member> {
        val members = ArrayList<Member>()
        var offset = 0
        while (offset + BLOCK <= tar.size) {
            val header = tar.copyOfRange(offset, offset + BLOCK)
            if (header.all { it == 0.toByte() }) break // end-of-archive marker
            val name = asciiField(header, 0, 100)
            val size = octalField(header, 124, 12)
            val contentStart = offset + BLOCK
            val contentEnd = (contentStart + size).coerceAtMost(tar.size.toLong()).toInt()
            members.add(Member(name, tar.copyOfRange(contentStart, contentEnd)))
            offset = contentStart + paddedTo(size).toInt()
        }
        return members
    }

    /** Serialise members into a valid tar archive terminated by two zero blocks. */
    fun write(members: List<Member>): ByteArray {
        val out = ByteArrayOutputStream()
        members.forEach { member ->
            out.write(header(member.name, member.content.size))
            out.write(member.content)
            val padding = (BLOCK - member.content.size % BLOCK) % BLOCK
            if (padding > 0) out.write(ByteArray(padding))
        }
        out.write(ByteArray(2 * BLOCK))
        return out.toByteArray()
    }

    /** A NUL/space-terminated ASCII header field (name, magic, ...). */
    private fun asciiField(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.US_ASCII)
            .takeWhile { it.code != 0 }
            .trim()

    /** A NUL/space-terminated octal numeric header field (size, mtime, ...). */
    private fun octalField(header: ByteArray, offset: Int, length: Int): Long {
        val digits = String(header, offset, length, Charsets.US_ASCII).takeWhile { it in '0'..'7' }
        return if (digits.isEmpty()) 0L else digits.toLong(8)
    }

    private fun paddedTo(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    private fun header(name: String, size: Int): ByteArray {
        val header = ByteArray(BLOCK)
        fun put(offset: Int, text: String) {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            bytes.copyInto(header, offset, 0, bytes.size)
        }
        put(0, name.take(100))
        put(100, "0000644 ") // mode
        put(108, "0000000 ") // uid
        put(116, "0000000 ") // gid
        put(124, size.toString(8).padStart(11, '0') + " ") // size (octal)
        put(136, "00000000000 ") // mtime
        put(148, "        ") // checksum placeholder (spaces during computation)
        header[156] = '0'.code.toByte() // typeflag: regular file
        put(257, "ustar")
        header[262] = 0
        put(263, "00")
        val checksum = header.sumOf { it.toInt() and 0xFF }
        put(148, checksum.toString(8).padStart(6, '0') + "  ")
        return header
    }
}
