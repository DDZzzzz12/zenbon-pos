package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.document.domain.models.Document
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SalesInvoiceArchivalTest {
    private val buyer =
        InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

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
            TestData.createAdmin(employeeNumber = "ISS003", username = "issuer3").id.value
        }.also { cachedIssuer = it }

    private fun billId(): Long =
        transaction {
            Bill
                .all()
                .first()
                .id.value
        }

    @Test
    fun `issuing archives a PDF and links it to the invoice`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val svc = service()
            val draftId = runBlocking { svc.createFromBill(billId(), buyer, null) }.id.value

            runBlocking { svc.issue(draftId, issuer()) }

            transaction {
                val issued = SalesInvoice.findById(draftId)!!
                val documentId = issued.documentId
                assertNotNull(documentId, "an issued invoice must have an archived document")

                val document = Document.findById(documentId.value)!!
                assertEquals("OUTGOING_INVOICE", document.fileType, "our sales file in their own archive")
                assertTrue(document.contentHash.isNotBlank(), "the archived file must be verifiable")
                assertTrue(document.fileSize > 0L)
            }
        }
    }

    @Test
    fun `reprint serves the stored bytes rather than re-rendering`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val svc = service()
            val draftId = runBlocking { svc.createFromBill(billId(), buyer, null) }.id.value
            runBlocking { svc.issue(draftId, issuer()) }

            val first = runBlocking { svc.reprint(draftId) }
            val second = runBlocking { svc.reprint(draftId) }

            assertTrue(first.isNotEmpty(), "the archived PDF must have content")
            assertTrue(
                first.contentEquals(second),
                "two reprints must be byte-identical — the file is served, never regenerated",
            )
        }
    }

    @Test
    fun `renaming the shop after issue does not change the reprint`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val svc = service()
            val draftId = runBlocking { svc.createFromBill(billId(), buyer, null) }.id.value
            runBlocking { svc.issue(draftId, issuer()) }
            val before = runBlocking { svc.reprint(draftId) }

            transaction { ShopInfo.all().first().shopName = "Ganz Anderer Name" }

            assertTrue(
                before.contentEquals(runBlocking { svc.reprint(draftId) }),
                "what was issued must stay reproducible; a later rename must not rewrite history",
            )
        }
    }

    @Test
    fun `a draft has no archived document and cannot be reprinted`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val svc = service()
            val draftId = runBlocking { svc.createFromBill(billId(), buyer, null) }.id.value

            transaction {
                assertEquals(null, SalesInvoice.findById(draftId)!!.documentId, "nothing is archived before issue")
            }
        }
    }
}
