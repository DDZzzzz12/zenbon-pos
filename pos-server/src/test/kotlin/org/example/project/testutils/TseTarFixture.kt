package org.example.project.testutils

import java.io.ByteArrayOutputStream

/**
 * Synthetic TSE exports for tests: a USTAR archive of TR-03151 transaction log messages, DER-encoded
 * just far enough for `TarLogMessageReader` (pos-tse's own fixture is not visible from here). The
 * device serial is 32 × [serialByte], so `0xAB` reads back as `"AB".repeat(32)`.
 */
object TseTarFixture {
    /** A TAR holding one member per log message, terminated by two zero blocks. */
    fun tar(messages: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        messages.forEachIndexed { index, message -> out.write(member("log$index.log", message)) }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    /** A FinishTransaction log message. */
    fun logMessage(
        serialByte: Int,
        tanr: Long,
        counter: Long,
        logTime: Long = 1_784_000_000,
        client: String = "TER-001",
    ): ByteArray {
        val body =
            int(2) +
                tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x03, 0x07, 0x01, 0x01)) +
                tlv(0x80, "FinishTransaction".toByteArray()) +
                tlv(0x81, client.toByteArray()) +
                tlv(0x82, byteArrayOf(0xDE.toByte(), 0xAD.toByte())) +
                tlv(0x83, "Kassenbeleg-V1".toByteArray()) +
                tlv(0x85, unsigned(tanr)) +
                tlv(0x04, ByteArray(32) { serialByte.toByte() }) +
                tlv(0x30, tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x01, 0x01, 0x04, 0x01, 0x03))) +
                int(counter) +
                int(logTime) +
                tlv(0x04, ByteArray(96) { 0xCD.toByte() })
        return tlv(0x30, body)
    }

    private fun member(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000644 ".toByteArray().copyInto(header, 100)
        (content.size.toString(8).padStart(11, '0') + " ").toByteArray().copyInto(header, 124)
        "        ".toByteArray().copyInto(header, 148)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        (checksum.toString(8).padStart(6, '0') + "  ").toByteArray().copyInto(header, 148)
        return header + content + ByteArray((512 - content.size % 512) % 512)
    }

    private fun len(n: Int): ByteArray =
        when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }

    private fun tlv(
        tag: Int,
        value: ByteArray,
    ): ByteArray = byteArrayOf(tag.toByte()) + len(value.size) + value

    private fun unsigned(v: Long): ByteArray {
        if (v == 0L) return byteArrayOf(0)
        var x = v
        val out = ArrayList<Byte>()
        while (x > 0) {
            out.add(0, (x and 0xFF).toByte())
            x = x shr 8
        }
        if (out[0].toInt() and 0x80 != 0) out.add(0, 0)
        return out.toByteArray()
    }

    private fun int(v: Long): ByteArray = tlv(0x02, unsigned(v))
}
