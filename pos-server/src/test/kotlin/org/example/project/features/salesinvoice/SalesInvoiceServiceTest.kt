package org.example.project.features.salesinvoice

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.database.tables.ArNumberSequenceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service opens its own transaction through `dbQuery`, so these tests never call it from
 * inside a `transaction { }` — fixtures are built in one transaction, the service is called
 * outside, and assertions read the row back in another.
 */
class SalesInvoiceServiceTest {
    /**
     * Built per test, not as a field: `testApp` starts Koin inside the test body, and the PDF
     * service is resolved from it.
     */
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

    /** Issuing archives a document, and a document names the employee who issued it. */
    private var cachedIssuer: java.util.UUID? = null

    /**
     * One employee per test, created on first use. JUnit builds a fresh instance per test method, so
     * the cache resets — and creating a second admin inside one test collides on the unique username.
     */
    private fun issuer(): java.util.UUID =
        cachedIssuer ?: transaction {
            TestData.createAdmin(employeeNumber = "ISS001", username = "issuer").id.value
        }.also { cachedIssuer = it }

    /** A draft with one consistent line and matching totals — the shape `issue()` accepts. */
    private fun createCompleteDraft(origin: RevenueOrigin = RevenueOrigin.STANDALONE): Long =
        transaction {
            val invoice =
                SalesInvoice.new {
                    this.shop = ShopInfo.all().first()
                    this.revenueOrigin = origin
                    this.buyerName = "Musterfirma GmbH"
                    this.issueDate = currentTimeStamp()
                    this.serviceDate = currentTimeStamp()
                    this.taxInclusiveTotal = BigDecimal("37.8900")
                    this.taxTotal = BigDecimal("6.0500")
                }
            SalesInvoiceLine.new {
                this.salesInvoice = invoice
                this.lineNo = 1
                this.itemName = "Wiener Schnitzel"
                this.quantity = BigDecimal("2.0000")
                this.unitCode = "C62"
                this.netUnitPrice = BigDecimal("15.9200")
                this.lineNetAmount = BigDecimal("31.8400")
                this.vatRate = BigDecimal("19.00")
                this.vatCategoryCode = "S"
                this.vatAmount = BigDecimal("6.0500")
                this.lineGrossAmount = BigDecimal("37.8900")
            }
            SalesInvoiceVatBreakdown.new {
                this.salesInvoice = invoice
                this.vatCategoryCode = "S"
                this.vatRate = BigDecimal("19.00")
                this.taxableAmount = BigDecimal("31.8400")
                this.vatAmount = BigDecimal("6.0500")
            }
            invoice.id.value
        }

    @Test
    fun `issue stamps a number from the configured range`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()

            runBlocking { service().issue(id, issuer()) }

            transaction {
                val issued = SalesInvoice.findById(id)!!
                assertEquals(SalesInvoiceStatus.ISSUED, issued.status)
                assertTrue(
                    issued.invoiceNumber?.startsWith("RG-") == true,
                    "expected the default RG range, got ${issued.invoiceNumber}",
                )
            }
        }
    }

    @Test
    fun `a draft that fails validation consumes no number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()
            transaction {
                // The lines no longer sum to the stated total.
                SalesInvoice.findById(id)!!.taxInclusiveTotal = BigDecimal("999.0000")
            }

            val error = assertFailsWith<PosException> { runBlocking { service().issue(id, issuer()) } }
            assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)

            transaction {
                assertNull(SalesInvoice.findById(id)!!.invoiceNumber, "no number may be stamped on a rejected invoice")
                assertEquals(
                    0L,
                    ArNumberSequenceTable.selectAll().count(),
                    "the counter row must not even exist — validation runs before the sequence is touched",
                )
            }
        }
    }

    @Test
    fun `cancel issues a 381 that points back at the original`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()
            runBlocking { service().issue(id, issuer()) }

            val stornoId = runBlocking { service().cancel(id, cancelledBy = issuer(), reason = "Tippfehler") }.id.value

            transaction {
                val original = SalesInvoice.findById(id)!!
                val storno = SalesInvoice.findById(stornoId)!!

                assertEquals("381", storno.invoiceTypeCode)
                assertEquals(original.id, storno.precedingSalesInvoice?.id)
                assertEquals(SalesInvoiceStatus.CANCELLED, original.status)
                assertEquals(SalesInvoiceStatus.ISSUED, storno.status, "the Gutschrift is itself an issued document")
                assertNotNull(storno.invoiceNumber, "the Gutschrift carries its own number")
                assertEquals(
                    0,
                    BigDecimal("-37.8900").compareTo(storno.taxInclusiveTotal),
                    "a Gutschrift carries negated amounts",
                )
                assertEquals(
                    0,
                    BigDecimal.ZERO.compareTo(original.openAmount),
                    "cancelling closes the original's residual",
                )
            }
        }
    }

    @Test
    fun `the Gutschrift names the original's issuer, not today's shop data`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()
            transaction {
                val draft = SalesInvoice.findById(id)!!
                draft.sellerName = "Zum Goldenen Hirschen GmbH"
                draft.sellerVatId = "DE123456789"
                draft.sellerTaxNumber = "12/345/67890"
                draft.sellerStreet = "Hauptstr. 1"
                draft.sellerZip = "10115"
                draft.sellerCity = "Berlin"
                draft.sellerCountry = "DE"
            }
            runBlocking { service().issue(id, issuer()) }
            // The shop is renamed after issue: the credit note reverses the ORIGINAL document.
            transaction { ShopInfo.all().first().companyName = "Neuer Name GmbH" }

            val stornoId = runBlocking { service().cancel(id, cancelledBy = issuer(), reason = "Kunde hat storniert") }.id.value

            transaction {
                val storno = SalesInvoice.findById(stornoId)!!
                assertEquals("Zum Goldenen Hirschen GmbH", storno.sellerName)
                assertEquals("DE123456789", storno.sellerVatId)
                assertEquals("12/345/67890", storno.sellerTaxNumber)
                assertEquals("Hauptstr. 1", storno.sellerStreet)
                assertEquals("10115", storno.sellerZip)
                assertEquals("Berlin", storno.sellerCity)
                assertEquals("DE", storno.sellerCountry)
            }
            val text =
                org.apache.pdfbox.Loader
                    .loadPDF(runBlocking { service().reprint(stornoId) })
                    .use {
                        org.apache.pdfbox.text
                            .PDFTextStripper()
                            .getText(it)
                    }
            assertTrue(text.contains("Zum Goldenen Hirschen GmbH"), "§14 Abs. 4 Nr. 1: the issuer's name must be on the Gutschrift:\n$text")
            assertTrue(text.contains("DE123456789"), "§14 Abs. 4 Nr. 2: the issuer's USt-IdNr must be on the Gutschrift")
        }
    }

    private fun pdfText(invoiceId: Long): String =
        org.apache.pdfbox.Loader
            .loadPDF(runBlocking { service().reprint(invoiceId) })
            .use {
                org.apache.pdfbox.text
                    .PDFTextStripper()
                    .getText(it)
            }

    @Test
    fun `a Gutschrift of a signed till invoice prints the original's TSE block, never an Ausfall`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft(origin = RevenueOrigin.POS_SETTLED)
            transaction {
                val draft = SalesInvoice.findById(id)!!
                draft.tseSerialNumber = "TSE-SN-4711"
                draft.tseTransactionNumber = 42L
                draft.tseSignatureCounter = 99L
                draft.tseSignature = "c2lnbmF0dXJl"
                draft.tseStartTimestamp = 1_777_942_923L
                draft.tseTimestamp = 1_777_942_924L
                // The frozen Anhang I payload — the only source of the till's ClientID.
                draft.tseQrCodePayload =
                    "V0;TERM-GS;Kassenbeleg-V1;Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar;42;99;" +
                    "2026-05-05T01:02:03.000Z;2026-05-05T01:02:04.000Z;ecdsa-plain-SHA384;unixTime;c2lnbmF0dXJl;cHVi"
            }
            runBlocking { service().issue(id, issuer()) }

            val stornoId = runBlocking { service().cancel(id, cancelledBy = issuer(), reason = "Storno") }.id.value

            transaction {
                val storno = SalesInvoice.findById(stornoId)!!
                assertEquals("TSE-SN-4711", storno.tseSerialNumber)
                assertEquals(42L, storno.tseTransactionNumber)
                assertEquals(99L, storno.tseSignatureCounter)
                assertEquals("c2lnbmF0dXJl", storno.tseSignature)
                assertEquals(SalesInvoice.findById(id)!!.tseQrCodePayload, storno.tseQrCodePayload)
            }
            val original = pdfText(id)
            val storno = pdfText(stornoId)
            assertTrue(storno.contains("GUTSCHRIFT", ignoreCase = true), "the credit note is titled as one:\n$storno")
            assertTrue(!storno.contains("Ausfall"), "a signed sale must not be disclosed as a TSE outage:\n$storno")
            for (fragment in listOf(
                "TSE-Seriennr.: TSE-SN-4711",
                "TSE-Transaktion: 42",
                "Signaturzähler: 99",
                "Start (UTC): 2026-05-05T01:02:03.000Z",
                "Ende (UTC): 2026-05-05T01:02:04.000Z",
                "Kassen-Seriennr.: TERM-GS",
                "Signatur: c2lnbmF0dXJl",
            )) {
                assertTrue(original.contains(fragment), "the original prints '$fragment':\n$original")
                assertTrue(storno.contains(fragment), "the Gutschrift mirrors '$fragment':\n$storno")
            }
        }
    }

    @Test
    fun `a Gutschrift of a STANDALONE invoice prints no TSE block, like its original`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft(origin = RevenueOrigin.STANDALONE)
            runBlocking { service().issue(id, issuer()) }

            val stornoId = runBlocking { service().cancel(id, cancelledBy = issuer(), reason = "Storno") }.id.value

            val storno = pdfText(stornoId)
            assertTrue(!storno.contains("TSE"), "a standalone invoice never touched the till:\n$storno")
            assertTrue(!pdfText(id).contains("TSE"), "and neither does its original")
        }
    }

    @Test
    fun `cancelling a draft is refused`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()

            val error = assertFailsWith<PosException> { runBlocking { service().cancel(id, issuer(), "Tippfehler") } }
            assertEquals(PosErrorCode.SALES_INVOICE_NOT_CANCELLABLE, error.code)
        }
    }

    // FO-65: the reason is printed on the Gutschrift, so it is mandatory and at most 60 characters
    // (the incoming-invoice cancellation's bound). A refusal leaves the invoice ISSUED.
    @Test
    fun `cancelling without a usable reason is refused and leaves the invoice issued`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()
            runBlocking { service().issue(id, issuer()) }

            for (bad in listOf(null, "", "   ", "x".repeat(61))) {
                val error = assertFailsWith<PosException> { runBlocking { service().cancel(id, issuer(), bad) } }
                assertEquals(PosErrorCode.VALIDATION_FAILED, error.code, "reason=$bad")
                assertEquals(HttpStatusCode.BadRequest, error.httpStatus, "reason=$bad")
            }
            transaction { assertEquals(SalesInvoiceStatus.ISSUED, SalesInvoice.findById(id)!!.status) }
        }
    }

    @Test
    fun `a 60 character reason is accepted and stored trimmed`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()
            runBlocking { service().issue(id, issuer()) }
            val reason = "y".repeat(60)

            val stornoId = runBlocking { service().cancel(id, issuer(), "  $reason  ") }.id.value

            transaction { assertEquals(reason, SalesInvoice.findById(stornoId)!!.notes) }
        }
    }

    @Test
    fun `a POS_SETTLED invoice is issued already paid and never open`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft(origin = RevenueOrigin.POS_SETTLED)

            runBlocking { service().issue(id, issuer()) }

            transaction {
                val issued = SalesInvoice.findById(id)!!
                assertEquals(0, BigDecimal("37.8900").compareTo(issued.prepaidAmount))
                assertEquals(0, BigDecimal.ZERO.compareTo(issued.amountDue))
                assertEquals(0, BigDecimal.ZERO.compareTo(issued.openAmount))
            }
        }
    }

    @Test
    fun `a STANDALONE invoice is issued fully open`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val id = createCompleteDraft()

            runBlocking { service().issue(id, issuer()) }

            transaction {
                val issued = SalesInvoice.findById(id)!!
                assertEquals(0, BigDecimal("37.8900").compareTo(issued.openAmount))
                assertEquals(0, BigDecimal("37.8900").compareTo(issued.amountDue))
            }
        }
    }
}
