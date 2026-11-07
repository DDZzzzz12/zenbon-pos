package org.example.project.utils.formatter

import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AEAO zu § 146a AO Nr. 2.4.4: the TSE values on a Beleg must appear "in dem Format …, in dem sie
 * von der TSE … zurückgeliefert wurden" — never rounded, truncated or altered. These rows are the
 * single field list all three Beleg renderers print.
 */
class TseReceiptTextTest {
    /** 96 bytes — an ecdsa-plain-SHA384 signature — Base64-encoded: 128 characters with `+` and `/`. */
    private val signature =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(96) { (it * 7 + 251).toByte() })

    private val data =
        TseReceiptDataDto(
            transactionNumber = 4711L,
            startTime = "2026-05-05T01:02:03.000Z",
            endTime = "2026-05-05T01:02:04.000Z",
            signatureCounter = 9_876_543_210L,
            tseSerialNumber = "8C6B9B4D4A0C6FBA2E7C9F0D1E2A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C",
            clientSerialNumber = "TERM-01",
            signature = signature,
        )

    @Test
    fun `every AEAO field is present, in print order, with its value verbatim`() {
        assertEquals(
            listOf(
                "TSE-Transaktion" to "4711",
                "Start (UTC)" to "2026-05-05T01:02:03.000Z",
                "Ende (UTC)" to "2026-05-05T01:02:04.000Z",
                "Signaturzähler" to "9876543210",
                "TSE-Seriennr." to data.tseSerialNumber,
                "Kassen-Seriennr." to "TERM-01",
                "Signatur" to signature,
            ),
            TseReceiptText.rows(data),
        )
    }

    @Test
    fun `the signature is printed in full - its wrapped lines join back to the stored value`() {
        val lines = TseReceiptText.lines(data, 48)
        val label = lines.indexOf("Signatur:")
        assertTrue(label >= 0, "a signature longer than the line goes under its own label:\n${lines.joinToString("\n")}")

        val wrapped = lines.drop(label + 1)
        assertEquals(signature, wrapped.joinToString(""), "no character may be dropped or inserted")
        assertTrue(wrapped.all { it.length <= 48 }, "no line may exceed the paper width")
        assertEquals(listOf(48, 48, 32), wrapped.map { it.length })
    }

    @Test
    fun `a 64-character hex serial wraps losslessly too`() {
        val lines = TseReceiptText.lines(data, 48)
        val label = lines.indexOf("TSE-Seriennr.:")
        assertEquals(data.tseSerialNumber, lines[label + 1] + lines[label + 2])
    }

    @Test
    fun `short values stay on their label's line`() {
        val lines = TseReceiptText.lines(data, 48)
        assertTrue("TSE-Transaktion: 4711" in lines)
        assertTrue("Start (UTC): 2026-05-05T01:02:03.000Z" in lines)
        assertTrue("Ende (UTC): 2026-05-05T01:02:04.000Z" in lines)
        assertTrue("Signaturzähler: 9876543210" in lines)
        assertTrue("Kassen-Seriennr.: TERM-01" in lines)
    }

    @Test
    fun `a row exactly at the width is not split - one character more is`() {
        assertEquals(listOf("ab: 12345"), TseReceiptText.rowLines("ab", "12345", 9))
        assertEquals(listOf("ab:", "12345678", "9"), TseReceiptText.rowLines("ab", "123456789", 8))
    }

    @Test
    fun `a non-positive width is refused`() {
        assertFailsWith<IllegalArgumentException> { TseReceiptText.rowLines("a", "b", 0) }
        Unit
    }
}
