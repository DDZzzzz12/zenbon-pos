package org.example.project.utils.builder.tse

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.features.allocation.AllocationFixtures
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the concrete readable-TSE-data output of the two builders in TseReceiptDataBuilder.kt (the
 * bill's QR-data form and the from-bill sales invoice's snapshot form) before the invoice one moves
 * to its own file. Expected values are literals, not derived from the code under test.
 */
class TseReceiptDataBuilderTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private var counter = 1

    private val hexSerial = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"
    private val payload = "V0;TERM-INV;Kassenbeleg-V1;Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar;4711;9131;x;y;ecdsa;sig;pk;serial"

    @Test
    fun `bill QR data is re-rendered as readable values`() {
        val qr =
            BillDto.TseQrCodeDataDto(
                kassenSeriennummer = "TERM-1",
                processType = "Kassenbeleg-V1",
                processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
                transactionNumber = 4711L,
                signatureCounter = 9131L,
                startTimeSeconds = 1_777_942_923L,
                logTimeSeconds = 1_777_942_924L,
                signatureAlgorithm = "ecdsa-plain-SHA384",
                signature = "SIGNATURE",
                publicKey = "cHVi",
                tseSerialNumber = hexSerial,
            )

        assertEquals(
            TseReceiptDataDto(
                transactionNumber = 4711L,
                startTime = "2026-05-05T01:02:03.000Z",
                endTime = "2026-05-05T01:02:04.000Z",
                signatureCounter = 9131L,
                tseSerialNumber = hexSerial,
                clientSerialNumber = "TERM-1",
                signature = "SIGNATURE",
            ),
            qr.toTseReceiptData(),
        )
    }

    private fun invoiceData(
        payloadValue: String? = payload,
        serial: String? = hexSerial,
        signatureValue: String? = "SIGNATURE",
    ): TseReceiptDataDto? =
        transaction {
            if (ShopInfo.count() == 0L) TestData.createShopInfo()
            AllocationFixtures
                .newSalesInvoice(BigDecimal("10.0000"), number = "RG-${counter++}")
                .apply {
                    tseQrCodePayload = payloadValue
                    tseSerialNumber = serial
                    tseTransactionNumber = 4711L
                    tseSignatureCounter = 9131L
                    tseSignature = signatureValue
                    tseStartTimestamp = 1_777_942_923L
                    tseTimestamp = 1_777_942_924L
                }.toTseReceiptData()
        }

    @Test
    fun `sales invoice snapshot is rendered with the payload's ClientID`() {
        assertEquals(
            TseReceiptDataDto(
                transactionNumber = 4711L,
                startTime = "2026-05-05T01:02:03.000Z",
                endTime = "2026-05-05T01:02:04.000Z",
                signatureCounter = 9131L,
                tseSerialNumber = hexSerial,
                clientSerialNumber = "TERM-INV",
                signature = "SIGNATURE",
            ),
            invoiceData(),
        )
    }

    @Test
    fun `sales invoice serial is normalised to hex`() {
        val base64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(32) { (it + 1).toByte() })
        assertEquals(hexSerial, invoiceData(serial = base64)?.tseSerialNumber)
    }

    @Test
    fun `sales invoice without payload, signature or serial yields nothing`() {
        assertNull(invoiceData(payloadValue = null))
        assertNull(invoiceData(signatureValue = ""))
        assertNull(invoiceData(serial = null))
    }
}
