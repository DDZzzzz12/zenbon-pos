package org.example.project.features.document

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.InvoiceBackfill
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.domain.models.Invoice
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the one-shot copy of legacy `document_invoice_metadata` rows into the normalized model.
 *
 * The property that matters most is idempotency: the backfill runs on every server start, keyed on
 * the existing `source_document_id` link.
 */
class InvoiceBackfillTest {
    private val repo = DocumentRepositoryImpl()

    /** Creates a document plus a legacy metadata row through the OLD API. Returns the document id. */
    private fun legacyInvoice(
        uploadedBy: UUID,
        grossAmount: BigDecimal?,
        vendorName: String?,
    ): Long {
        val doc =
            repo.create(
                filename = "Rechnung_test.pdf",
                fileType = "INVOICE",
                filePath = "/tmp/test-${System.nanoTime()}.pdf",
                fileSize = 1024L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = uploadedBy,
                contentHash = "hash-${System.nanoTime()}",
            )
        repo.updateInvoiceMetadata(
            documentId = doc.id.value,
            grossAmount = grossAmount,
            vendorName = vendorName,
        )
        return doc.id.value
    }

    @Test
    fun `an old metadata row becomes an invoice linked to its document`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            transaction {
                val docId = legacyInvoice(admin!!, BigDecimal("3.75"), "Deutsche Post AG")

                val report = InvoiceBackfill(repo).run()

                assertEquals(1, report.migrated)
                val invoice = repo.getInvoiceBySourceDocument(docId)!!
                assertEquals(docId, invoice.sourceDocument?.id?.value)
                assertEquals("Deutsche Post AG", invoice.sellerName)
                assertEquals(BigDecimal("3.7500"), invoice.taxInclusiveTotal)
                assertEquals("MANUAL", invoice.extractionSource)
            }
        }
    }

    @Test
    fun `running the backfill twice does not duplicate invoices`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            transaction {
                val docId = legacyInvoice(admin!!, BigDecimal("10.00"), "ACME")

                assertEquals(1, InvoiceBackfill(repo).run().migrated)
                val second = InvoiceBackfill(repo).run()

                assertEquals(0, second.migrated)
                assertEquals(1, second.skippedExisting)
                assertEquals(1, Invoice.find { InvoiceTable.sourceDocumentId eq docId }.count().toInt())
            }
        }
    }

    // 96 of the 97 production rows look like this: a gross amount and nothing else. The backfill
    // must still produce an invoice — dropping them would lose the document link.
    @Test
    fun `a legacy row whose fields are all null still produces an invoice`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            transaction {
                val docId = legacyInvoice(admin!!, grossAmount = null, vendorName = null)

                assertEquals(1, InvoiceBackfill(repo).run().migrated)
                val invoice = repo.getInvoiceBySourceDocument(docId)!!
                assertNull(invoice.sellerName)
                assertNull(invoice.taxInclusiveTotal)
                assertNull(invoice.booking)
            }
        }
    }

    /**
     * The DATEV booking fields belong on the separate booking row, not on the invoice header. A row
     * carrying none of them must not get an empty booking row at all.
     */
    @Test
    fun `legacy DATEV booking fields land on the booking row`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            transaction {
                val docId = legacyInvoice(admin!!, BigDecimal("20.00"), "ACME")
                repo.updateInvoiceMetadata(
                    documentId = docId,
                    accountNo = "3400",
                    bpAccountNo = "70001",
                    bookingText = "Wareneinkauf",
                    buCode = "9",
                )

                assertEquals(1, InvoiceBackfill(repo).run().migrated)

                val booking = repo.getInvoiceBySourceDocument(docId)!!.booking!!
                assertEquals("3400", booking.accountNo)
                assertEquals("70001", booking.bpAccountNo)
                assertEquals("Wareneinkauf", booking.bookingText)
                assertEquals("9", booking.buCode)
            }
        }
    }

    /**
     * Every mapped scalar, asserted individually against a distinct recognisable value.
     *
     * Silent field loss is the defect class this sub-project exists to remove, and a transposed
     * mapping — `net_amount` landing in `taxTotal`, `supplier_zip` in `sellerCity` — passes every
     * other test in this class. No two source values are equal, so a swap cannot accidentally
     * match. Written and read in separate transactions: within one, Exposed's identity map can
     * serve the entity from memory rather than from the columns it actually wrote.
     */
    @Test
    fun `every mapped legacy scalar reaches its destination`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            val docId =
                transaction {
                    val id = legacyInvoice(admin!!, BigDecimal("119.00"), "Deutsche Post AG")
                    // The legacy update is a full replace — an omitted argument nulls its column,
                    // so every value under test has to be in this one call.
                    repo.updateInvoiceMetadata(
                        documentId = id,
                        vendorName = "Deutsche Post AG",
                        grossAmount = BigDecimal("119.00"),
                        invoiceNumber = "RE-2026-0815",
                        invoiceDate = LocalDateTime(2026, 1, 2, 3, 4, 5),
                        dueDate = LocalDateTime(2026, 2, 3, 4, 5, 6),
                        deliveryDate = LocalDateTime(2026, 3, 4, 5, 6, 7),
                        currency = "CHF",
                        netAmount = BigDecimal("100.00"),
                        taxAmount = BigDecimal("19.00"),
                        vatId = "DE111222333",
                        supplierStreet = "Poststraße 7",
                        supplierZip = "20095",
                        supplierCity = "Hamburg",
                        supplierCountry = "DE",
                        supplierPhone = "+49 40 1111111",
                        supplierEmail = "billing@post.example",
                        iban = "DE02120300000000202051",
                        paymentReference = "VZ-4711",
                        paymentConditionsText = "30 Tage netto",
                        // Booking decisions — these must land on the booking row, not the header.
                        accountNo = "3400",
                        bpAccountNo = "70001",
                        buCode = "9",
                        bookingText = "Wareneinkauf Porto",
                        costCategoryId = "KST100",
                        costCategoryId2 = "KST200",
                        costAmount = BigDecimal("42.00"),
                        typeOfReceivable = "Lieferantenkredit",
                        paymentDunningBlock = true,
                        paidAt = LocalDateTime(2026, 4, 5, 6, 7, 8),
                    )

                    assertEquals(1, InvoiceBackfill(repo).run().migrated)
                    id
                }

            transaction {
                val invoice = repo.getInvoiceBySourceDocument(docId)!!

                assertEquals(docId, invoice.sourceDocument?.id?.value)
                assertEquals("MANUAL", invoice.extractionSource)
                assertEquals("RE-2026-0815", invoice.invoiceNumber)
                assertEquals(LocalDateTime(2026, 1, 2, 3, 4, 5), invoice.issueDate)
                assertEquals(LocalDateTime(2026, 2, 3, 4, 5, 6), invoice.dueDate)
                assertEquals(LocalDateTime(2026, 3, 4, 5, 6, 7), invoice.deliveryDate)
                assertEquals("CHF", invoice.currency)
                assertEquals("Deutsche Post AG", invoice.sellerName)
                assertEquals("DE111222333", invoice.sellerVatId)
                assertEquals("Poststraße 7", invoice.sellerStreet)
                assertEquals("20095", invoice.sellerZip)
                assertEquals("Hamburg", invoice.sellerCity)
                assertEquals("DE", invoice.sellerCountry)
                assertEquals("+49 40 1111111", invoice.sellerPhone)
                assertEquals("billing@post.example", invoice.sellerEmail)
                assertEquals("DE02120300000000202051", invoice.paymentIban)
                assertEquals("VZ-4711", invoice.paymentReference)
                assertEquals("30 Tage netto", invoice.paymentTermsText)
                assertEquals(BigDecimal("100.0000"), invoice.taxExclusiveTotal)
                assertEquals(BigDecimal("19.0000"), invoice.taxTotal)
                assertEquals(BigDecimal("119.0000"), invoice.taxInclusiveTotal)

                val booking = invoice.booking!!
                assertEquals("3400", booking.accountNo)
                assertEquals("70001", booking.bpAccountNo)
                assertEquals("9", booking.buCode)
                assertEquals("Wareneinkauf Porto", booking.bookingText)
                assertEquals("KST100", booking.costCategoryId)
                assertEquals("KST200", booking.costCategoryId2)
                assertEquals(BigDecimal("42.0000"), booking.costAmount)
                assertEquals("Lieferantenkredit", booking.typeOfReceivable)
                assertEquals(true, booking.paymentDunningBlock)
                assertEquals(LocalDateTime(2026, 4, 5, 6, 7, 8), booking.paidAt)
            }
        }
    }
}
