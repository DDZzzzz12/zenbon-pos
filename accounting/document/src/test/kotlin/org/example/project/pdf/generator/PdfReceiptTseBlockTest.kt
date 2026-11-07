package org.example.project.pdf.generator

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The PDF Beleg (IPP printers set to PDF) carries the same readable TSE block as the thermal one —
 * AEAO zu § 146a AO Nr. 2.4.4, values verbatim as the TSE returned them — and no QR image.
 *
 * Before B3 this path printed nothing fiscal at all; it then printed a QR plus a "TSE-Transaktion"
 * line that was in fact the database id of the TSE row, not the TANR.
 */
class PdfReceiptTseBlockTest {
    private fun extractLines(pdf: ByteArray): List<String> {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages)
                .joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
                .lines()
                .map { it.trim() }
        } finally {
            reader.close()
        }
    }

    private val signature =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(96) { (it * 11 + 3).toByte() })

    private val tse =
        TseReceiptDataDto(
            transactionNumber = 4711L,
            startTime = "2019-11-22T11:29:48.000Z",
            endTime = "2019-11-22T11:29:49.000Z",
            signatureCounter = 4131L,
            tseSerialNumber = "8C6B9B4D4A0C6FBA2E7C9F0D1E2A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C",
            clientSerialNumber = "TERM-1",
            signature = signature,
        )

    private fun bill(
        receipt: TseReceiptDataDto? = tse,
        note: TseSigningNoteDto? = null,
        firstOrderTseStartTime: Long? = 1_574_422_188L,
    ) = BillDto.BillResponseDto(
        billId = 1L,
        employeeId = "emp-1",
        employeeNumber = "E01",
        tipRecipient = "OWNER",
        tableSessionId = 1L,
        tableNumber = "T1",
        billNumber = "1001",
        billAmount = "10.00",
        paidAmount = "10.00",
        tips = "0.00",
        startTime = "2019-11-22T11:29:00",
        endTime = "2019-11-22T11:29:49",
        orderItems = emptyList(),
        payments = emptyList(),
        // The DB id of the TSE row: deliberately different from the TANR, so a test can tell them apart.
        tseTransactionId = 77L,
        tseQrCodeContent = if (receipt != null) "V0;TERM-1;Kassenbeleg-V1;x;4711;4131;a;b;c;unixTime;$signature;cHVi" else null,
        tseReceiptData = receipt,
        tseSigningNote = note,
        firstOrderTseStartTime = firstOrderTseStartTime,
    )

    private val shopHeader = ShopHeaderData(shopName = "Test GmbH")

    @Test
    fun `a signed bill prints every TSE field and the first-Bestellung time`() {
        val lines = extractLines(generateBillReceiptPdfBytes(bill(), shopHeader))
        val text = lines.joinToString("\n")

        listOf(
            "TSE-Transaktion: 4711",
            "Start (UTC): 2019-11-22T11:29:48.000Z",
            "Ende (UTC): 2019-11-22T11:29:49.000Z",
            "Signaturzähler: 4131",
            "Kassen-Seriennr.: TERM-1",
        ).forEach { assertTrue(it in lines, "missing \"$it\":\n$text") }
        assertTrue("Erste Bestellung" in text, "Tz. 2.7.2 still requires the first Bestellung's time:\n$text")
        assertFalse("TSE-Transaktion: 77" in lines, "the DB id must never be printed as the TANR")

        val serialAt = lines.indexOf("TSE-Seriennr.:")
        assertEquals(tse.tseSerialNumber, lines[serialAt + 1] + lines[serialAt + 2], text)
    }

    @Test
    fun `the signature is printed in full`() {
        val lines = extractLines(generateBillReceiptPdfBytes(bill(), shopHeader))
        val at = lines.indexOf("Signatur:")
        assertTrue(at >= 0, lines.joinToString("\n"))

        assertEquals(signature, lines.subList(at + 1, at + 4).joinToString(""), "Prüfwert must not be truncated")
    }

    @Test
    fun `no QR image is embedded`() {
        val pdf = generateBillReceiptPdfBytes(bill(), shopHeader)
        assertFalse(
            "/Image" in String(pdf, Charsets.ISO_8859_1),
            "the TSE data is printed as text; no QR raster may be embedded",
        )
    }

    @Test
    fun `negative control - the image probe sees an embedded raster`() {
        val output = java.io.ByteArrayOutputStream()
        val document = org.openpdf.text.Document()
        org.openpdf.text.pdf.PdfWriter
            .getInstance(document, output)
        document.open()
        val png = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
        document.add(
            org.openpdf.text.Image
                .getInstance(png.toByteArray()),
        )
        document.close()

        assertTrue("/Image" in String(output.toByteArray(), Charsets.ISO_8859_1), "the probe must not be blind")
    }

    @Test
    fun `an unsigned bill says so instead of silently omitting the block`() {
        val lines = extractLines(generateBillReceiptPdfBytes(bill(receipt = null, firstOrderTseStartTime = null), shopHeader))
        val text = lines.joinToString("\n")

        assertTrue("nicht verf" in text, "an unsecured Vorgang must be declared on the Beleg:\n$text")
        listOf("TSE-Transaktion", "Start (UTC)", "Signaturzähler", "TSE-Seriennr.", "Kassen-Seriennr.")
            .forEach { assertFalse(it in text, "no \"$it\" exists, so none may be printed:\n$text") }
        assertFalse("Signatur:" in lines, "no Prüfwert exists, so none may be printed:\n$text")
    }

    @Test
    fun `a pending bill prints its Nachsignierung note and no values`() {
        val text =
            extractLines(
                generateBillReceiptPdfBytes(
                    bill(
                        receipt = null,
                        note =
                            TseSigningNoteDto(
                                pending = true,
                                reasonCode = "TSE_NOT_OPERATIONAL",
                                reasonText = "x",
                                queuedAt = "2019-11-22T11:29:49",
                            ),
                    ),
                    shopHeader,
                ),
            ).joinToString("\n")

        assertTrue("Beleg wird nachsigniert" in text, text)
        assertFalse("TSE-Transaktion" in text, text)
        assertFalse("Signatur:" in text.lines().map { it.trim() }, text)
        assertFalse("nicht verfügbar (Ausfall)" in text, "a queued Vorgang is pending, not an Ausfall:\n$text")
    }
}
