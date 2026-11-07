package org.example.project.printer.core.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The thermal Beleg prints the TSE data as readable text (AEAO zu § 146a AO Nr. 2.4.4) and no QR.
 * The values must reach the paper exactly as the TSE returned them — the AEAO forbids rounding,
 * truncating or altering them.
 */
class BillFormatterTseBlockTest {
    /** 96 bytes Base64-encoded, as an ecdsa-plain-SHA384 signature is: 128 chars, `+`, `/` included. */
    private val signature =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(96) { (it * 13 + 250).toByte() })

    private val tse =
        TseReceiptDataDto(
            transactionNumber = 4711L,
            startTime = "2026-05-05T01:02:03.000Z",
            endTime = "2026-05-05T01:02:04.000Z",
            signatureCounter = 9131L,
            tseSerialNumber = "8C6B9B4D4A0C6FBA2E7C9F0D1E2A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C",
            clientSerialNumber = "TERM-01",
            signature = signature,
        )

    /** The QR payload a bill carried before; it must not reach the paper any more. */
    private val qrPayload =
        "V0;TERM-01;Kassenbeleg-V1;Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar;4711;9131;" +
            "2026-05-05T01:02:03.000Z;2026-05-05T01:02:04.000Z;ecdsa-plain-SHA384;unixTime;$signature;cHVi"

    private fun bill(
        receipt: TseReceiptDataDto? = tse,
        qr: String? = qrPayload,
        note: TseSigningNoteDto? = null,
    ) = BillDto.BillResponseDto(
        billId = 1,
        employeeId = "emp-1",
        employeeNumber = "K002",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 1,
        tableNumber = "T1",
        billNumber = "RE-261005-1",
        billAmount = "10.00",
        paidAmount = "10.00",
        tips = "0.00",
        startTime = "2026-05-05T03:02:00",
        endTime = "2026-05-05T03:02:04",
        orderItems = emptyList(),
        payments = emptyList(),
        tseTransactionId = 99L,
        tseQrCodeContent = qr,
        tseReceiptData = receipt,
        tseSigningNote = note,
    )

    private fun bytes(bill: BillDto.BillResponseDto) = runBlocking { BillFormatter().format(bill, ShopHeaderData(shopName = "Test"), Language.GERMAN) }

    /**
     * The printed lines, with each `ESC`/`GS` command removed together with its one-byte argument
     * (`printedLines` strips only control characters, which would leave e.g. the `a` of `ESC a 0`
     * glued to the text). `ESC @` (initialize) is the one argument-less command the formatter emits.
     */
    private fun lines(bill: BillDto.BillResponseDto): List<String> =
        bytes(bill)
            .toString(charset("Cp850"))
            .replace("\u001B@", "")
            .replace(Regex("[\u001B\u001D].."), "")
            .replace(Regex("[\u0000-\u0009\u000b-\u001f\u007f]"), "")
            .lines()
            .map { it.trimEnd() }

    /** `GS ( k` — the ESC/POS 2D-symbol function prefix every QR model/size/store/print command starts with. */
    private fun ByteArray.containsQrCommand(): Boolean =
        (0..size - 3).any {
            this[it] == 0x1D.toByte() &&
                this[it + 1] == 0x28.toByte() &&
                this[it + 2] == 0x6B.toByte()
        }

    @Test
    fun `every TSE field is printed with its value`() {
        val printed = lines(bill())

        listOf(
            "TSE-Transaktion: 4711",
            "Start (UTC): 2026-05-05T01:02:03.000Z",
            "Ende (UTC): 2026-05-05T01:02:04.000Z",
            "Signaturzähler: 9131",
            "TSE-Seriennr.:",
            "Kassen-Seriennr.: TERM-01",
            "Signatur:",
        ).forEach { expected ->
            assertTrue(expected in printed, "missing \"$expected\" on the Beleg:\n${printed.joinToString("\n")}")
        }
        val serialAt = printed.indexOf("TSE-Seriennr.:")
        assertEquals(tse.tseSerialNumber, printed[serialAt + 1] + printed[serialAt + 2], "the serial is printed whole")
    }

    @Test
    fun `the signature is printed in full - the wrapped lines join back to the stored value`() {
        val printed = lines(bill())
        val at = printed.indexOf("Signatur:")
        val wrapped = printed.subList(at + 1, at + 1 + 3)

        assertEquals(signature, wrapped.joinToString(""), "Prüfwert must not be truncated or altered")
        assertTrue(wrapped.all { it.length <= 48 }, "no line exceeds the 48-column paper")
    }

    @Test
    fun `the printed times are the QR payload's times, byte for byte`() {
        val fields = qrPayload.split(";")
        val printed = lines(bill())

        assertTrue("Start (UTC): ${fields[6]}" in printed)
        assertTrue("Ende (UTC): ${fields[7]}" in printed)
    }

    @Test
    fun `no QR command is emitted - even when the bill still carries a QR payload`() {
        val out = bytes(bill())

        assertFalse(out.containsQrCommand(), "the Beleg must carry no QR code (GS ( k)")
        assertFalse(String(out, charset("Cp850")).contains("V0;TERM-01"), "the QR payload itself is not printed")
    }

    @Test
    fun `negative control - the QR detector finds a real QR store command`() {
        val store = byteArrayOf(0x1B, 0x40, 0x1D, 0x28, 0x6B, 0x05, 0x00, 0x31, 0x50, 0x30, 0x41, 0x0A)
        assertTrue(store.containsQrCommand(), "the detector must not be blind")
    }

    @Test
    fun `a pending bill prints the Nachsignierung note and no fabricated TSE values`() {
        val printed =
            lines(
                bill(
                    receipt = null,
                    qr = null,
                    note =
                        TseSigningNoteDto(
                            pending = true,
                            reasonCode = "TSE_NOT_OPERATIONAL",
                            reasonText = "device busy",
                            queuedAt = "2026-05-05T03:02:04",
                        ),
                ),
            )
        val text = printed.joinToString("\n")

        assertTrue("Beleg wird nachsigniert" in text, text)
        listOf("TSE-Transaktion", "Start (UTC)", "Ende (UTC)", "Signaturzähler", "TSE-Seriennr.", "Kassen-Seriennr.", "Signatur:")
            .forEach { assertFalse(it in text, "\"$it\" must not appear on an unsigned Beleg:\n$text") }
    }

    @Test
    fun `negative control - the QR payload alone prints nothing - only the structured data does`() {
        val text = lines(bill(receipt = null)).joinToString("\n")

        assertFalse("TSE-Transaktion" in text, text)
        assertFalse(signature.take(20) in text, text)
    }

    @Test
    fun `a late-signed bill prints both the TSE data and the Nachsigniert line`() {
        val text =
            lines(
                bill(
                    note =
                        TseSigningNoteDto(
                            pending = false,
                            reasonCode = "TSE_NOT_OPERATIONAL",
                            reasonText = "x",
                            queuedAt = "2026-05-05T03:02:04",
                            signedAt = "2026-05-05T03:10:00",
                        ),
                ),
            ).joinToString("\n")

        assertTrue("TSE-Transaktion: 4711" in text, text)
        assertTrue("Nachsigniert am 05.05.2026 03:10:00" in text, text)
    }
}
