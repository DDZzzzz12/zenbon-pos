package org.example.project.features.bill

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestDatabase
import org.example.project.utils.builder.tse.clientIdFromQrPayload
import org.example.project.utils.builder.tse.toTseQrCodeContent
import org.example.project.utils.builder.tse.toTseReceiptData
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.serialHex
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Beleg no longer prints the DSFinV-K Anhang I QR; it prints the same values as text (AEAO zu
 * § 146a AO Nr. 2.4.4). The text must say exactly what the QR said — an auditor holding an old QR
 * receipt and a new text receipt for comparable Vorgänge, or the DSFinV-K export, must find the
 * same TANR, counter, times, ClientID and signature. Both are built from one `TseQrCodeDataDto`;
 * these tests pin that they stay field-for-field identical and share the SIMULATION guard.
 */
@DisplayName("Readable TSE receipt data == the QR payload it replaces")
class TseReceiptDataParityTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** A real Swissbit serial shape: 32 bytes, Base64 — printed as 64 hex like DSFinV-K `TSE_SERIAL`. */
    private val serialBytes = ByteArray(32) { (it * 9 + 1).toByte() }
    private val serialBase64 =
        java.util.Base64
            .getEncoder()
            .encodeToString(serialBytes)
    private val serialHex = serialBytes.joinToString("") { "%02X".format(it) }

    /** A P-384 plain signature: 96 bytes → 128 Base64 characters. */
    private val signature =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(96) { (it * 3 + 200).toByte() })

    private fun tx(
        mode: TseMode = TseMode.NORMAL,
        startTs: Long? = 1_777_942_923L,
        finishTs: Long? = 1_777_942_924L,
        clientId: String? = "TERM-PAR",
    ): TseTransaction =
        transaction {
            TseTransaction.new {
                this.tseSerialNumber = serialBase64
                this.transactionNumber = 4711L
                this.processType = "Kassenbeleg-V1"
                this.processDataHash = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar"
                this.signature = this@TseReceiptDataParityTest.signature
                this.signatureCounter = 9131L
                this.signatureAlgorithm = "ecdsa-plain-SHA384"
                this.publicKey = "cHVi"
                this.clientId = clientId
                this.startTime = currentTimeStamp()
                this.endTime = currentTimeStamp()
                this.tseStartTimestamp = startTs
                this.tseTimestamp = finishTs
                this.tseMode = mode
            }
        }

    private fun assertMatchesQr(t: TseTransaction) =
        transaction {
            val qr = assertNotNull(t.toTseQrCodeContent()).split(";")
            val text = assertNotNull(t.toTseReceiptData())

            assertEquals(qr[1], text.clientSerialNumber, "Kassen-Seriennr. = QR kassen-seriennummer")
            assertEquals(qr[4], text.transactionNumber.toString(), "TANR")
            assertEquals(qr[5], text.signatureCounter.toString(), "Signaturzähler")
            assertEquals(qr[6], text.startTime, "Start (UTC) must be the QR start-zeit, byte for byte")
            assertEquals(qr[7], text.endTime, "Ende (UTC) must be the QR log-time, byte for byte")
            assertEquals(qr[10], text.signature, "the Prüfwert is the QR signature, untruncated")
            assertEquals(t.serialHex(), text.tseSerialNumber, "the TSE serial is DSFinV-K's TSE_SERIAL form")
            text
        }

    @Test
    fun `every printed value equals the QR field it replaces`() {
        val text = assertMatchesQr(tx())

        assertEquals("2026-05-05T01:02:03.000Z", text.startTime, "UTC ISO form of the TSE's Unix time")
        assertEquals("2026-05-05T01:02:04.000Z", text.endTime)
        assertEquals(serialHex, text.tseSerialNumber, "Base64 serial printed as 64 hex, not Base64")
        assertEquals(signature, text.signature)
        assertEquals("TERM-PAR", text.clientSerialNumber)
    }

    @Test
    fun `the start-time fallback chain is the QR's too`() {
        assertMatchesQr(tx(startTs = null))
    }

    @Test
    fun `a SIMULATION signature yields no printable TSE data, like no QR`() {
        val t = tx(mode = TseMode.SIMULATION)
        transaction {
            assertNull(t.toTseQrCodeContent())
            assertNull(t.toTseReceiptData(), "a fabricated SIM signature must never reach a Beleg (B2)")
        }
    }

    @Test
    fun `negative control - a transaction missing a TSE field yields nothing rather than a partial block`() {
        val t = tx(clientId = null)
        transaction {
            assertNull(t.toTseQrCodeContent())
            assertNull(t.toTseReceiptData(), "no block with an empty Kassen-Seriennr.")
        }
    }

    @Test
    fun `the ClientID is read from a well-formed Anhang I payload only`() {
        val payload = transaction { tx().toTseQrCodeContent()!! }

        assertEquals("TERM-PAR", clientIdFromQrPayload(payload))
        assertNull(clientIdFromQrPayload(null))
        assertNull(clientIdFromQrPayload("V1;" + payload.substringAfter(";")), "only V0 is a known layout")
        assertNull(clientIdFromQrPayload("V0;TERM-PAR;too;short"), "a truncated payload is not trusted")
        assertNull(clientIdFromQrPayload(payload.replaceFirst(";TERM-PAR;", ";;")), "an empty ClientID is no ClientID")
    }
}
