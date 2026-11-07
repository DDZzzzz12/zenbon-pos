package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.builder.tse.toTseQrCodeContent
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An A4 invoice from a bill carries the bill's TSE data as readable text — the same values its
 * receipt printed (AEAO zu § 146a AO Nr. 2.4.4): TANR, Start/Ende in UTC, Signaturzähler, TSE and
 * till serial numbers and the full Prüfwert. It used to print only the serial (raw, not hex), the
 * TANR and the counter.
 *
 * The till serial (ClientID) has no column on `sales_invoice`; it is frozen in
 * `tse_qr_code_payload`, which until now no code path ever wrote.
 */
class SalesInvoiceTseTextTest {
    private val buyer = InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private val serialBytes = ByteArray(32) { (it * 5 + 2).toByte() }
    private val serialHex = serialBytes.joinToString("") { "%02X".format(it) }
    private val signature =
        java.util.Base64
            .getEncoder()
            .encodeToString(ByteArray(96) { (it * 7 + 1).toByte() })

    private var cachedIssuer: java.util.UUID? = null

    private fun service() =
        SalesInvoiceServiceImpl(
            repository = SalesInvoiceRepositoryImpl(),
            numbers = ArNumberSequenceRepositoryImpl(),
            facts = BillInvoiceFactsGatherer(),
            pdf = GlobalContext.get().get(),
            billRepository = GlobalContext.get().get(),
            terminalRepository = GlobalContext.get().get(),
            saleSigning = GlobalContext.get().get(),
            configManager = GlobalContext.get().get(),
            allocationWriter = GlobalContext.get().get(),
        )

    private fun issuer(): java.util.UUID =
        cachedIssuer ?: transaction {
            TestData.createAdmin(employeeNumber = "ISS_TSE", username = "issuer_tse").id.value
        }.also { cachedIssuer = it }

    /** The fixture bill, its TSE transaction completed to everything a receipt prints. */
    private fun settledSignedBill(mode: TseMode) {
        val bill = TestData.createSettledBillForInvoicing()
        transaction {
            val tx = Bill.findById(bill.id)!!.tseTransaction!!
            tx.tseSerialNumber =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(serialBytes)
            tx.processDataHash = "Beleg^28.30_0.00_0.00_0.00_0.00^28.30:Bar"
            tx.publicKey = "cHVi"
            tx.clientId = "TERM-INV"
            tx.signature = signature
            tx.tseMode = mode
        }
    }

    private fun billId(): Long =
        transaction {
            Bill
                .all()
                .first()
                .id.value
        }

    private fun billQr(): String? = transaction { Bill.findById(billId())!!.tseTransaction!!.toTseQrCodeContent() }

    private fun textOf(bytes: ByteArray): List<String> =
        Loader
            .loadPDF(bytes)
            .use { PDFTextStripper().getText(it) }
            .lines()
            .map { it.trim() }

    private fun issueAndRead(beforeIssue: (Long) -> Unit = {}): Pair<Long, List<String>> {
        val svc = service()
        val draftId = runBlocking { svc.createFromBill(billId(), buyer, null) }.id.value
        beforeIssue(draftId)
        runBlocking { svc.issue(draftId, issuer()) }
        return draftId to textOf(runBlocking { svc.reprint(draftId) })
    }

    @Test
    fun `a from-bill invoice prints the full TSE data, equal to the receipt's QR`() {
        testApp(setupData = { settledSignedBill(TseMode.NORMAL) }) {
            val (draftId, lines) = issueAndRead()
            val text = lines.joinToString("\n")
            val qr = assertNotNull(billQr()).split(";")

            transaction {
                assertEquals(billQr(), SalesInvoice.findById(draftId)!!.tseQrCodePayload, "the payload is frozen at creation")
            }
            listOf(
                "TSE-Transaktion: ${qr[4]}",
                "Start (UTC): ${qr[6]}",
                "Ende (UTC): ${qr[7]}",
                "Signaturzähler: ${qr[5]}",
                "Kassen-Seriennr.: TERM-INV",
            ).forEach { assertTrue(it in lines, "missing \"$it\":\n$text") }

            val serialAt = lines.indexOf("TSE-Seriennr.:")
            assertEquals(serialHex, lines[serialAt + 1] + lines[serialAt + 2], "hex, as DSFinV-K TSE_SERIAL:\n$text")
            val sigAt = lines.indexOf("Signatur:")
            assertEquals(signature, lines.subList(sigAt + 1, sigAt + 4).joinToString(""), "Prüfwert untruncated:\n$text")
            assertFalse("Ausfall" in text, text)
        }
    }

    @Test
    fun `a SIMULATION-signed bill's invoice prints the Ausfall line, never the fabricated values`() {
        testApp(setupData = { settledSignedBill(TseMode.SIMULATION) }) {
            val (draftId, lines) = issueAndRead()
            val text = lines.joinToString("\n")

            transaction { assertNull(SalesInvoice.findById(draftId)!!.tseQrCodePayload) }
            assertTrue("Ausfall" in text, text)
            assertFalse("Signatur:" in lines, text)
            assertFalse(signature.take(30) in text.replace("\n", ""), "the SIM signature must not be printed:\n$text")
            assertFalse("TSE-Transaktion" in text, text)
        }
    }

    @Test
    fun `a draft created without the payload gets it at issue from the same transaction`() {
        testApp(setupData = { settledSignedBill(TseMode.NORMAL) }) {
            val (draftId, lines) =
                issueAndRead { id -> transaction { SalesInvoice.findById(id)!!.tseQrCodePayload = null } }

            transaction { assertEquals(billQr(), SalesInvoice.findById(draftId)!!.tseQrCodePayload) }
            assertTrue("Kassen-Seriennr.: TERM-INV" in lines, lines.joinToString("\n"))
        }
    }

    @Test
    fun `negative control - the backfill refuses a transaction whose TANR differs from the snapshot`() {
        testApp(setupData = { settledSignedBill(TseMode.NORMAL) }) {
            val (draftId, lines) =
                issueAndRead { id ->
                    transaction {
                        val draft = SalesInvoice.findById(id)!!
                        draft.tseQrCodePayload = null
                        draft.tseTransactionNumber = 1L // no longer the bill's TANR 4711
                    }
                }

            transaction { assertNull(SalesInvoice.findById(draftId)!!.tseQrCodePayload) }
            assertFalse("Kassen-Seriennr.: TERM-INV" in lines, "two transactions must never be mixed in one block")
        }
    }
}
