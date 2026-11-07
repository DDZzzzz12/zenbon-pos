package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.toResponseDto
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The till disables its invoice action from these two fields, so a bill that already carries a live
 * invoice must say so on the wire rather than only refusing at `from-bill` with a 409.
 */
class BillInvoiceLinkTest {
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

    private var cachedIssuer: java.util.UUID? = null

    private fun issuer(): java.util.UUID =
        cachedIssuer ?: transaction {
            TestData.createAdmin(employeeNumber = "LNK001", username = "linkissuer").id.value
        }.also { cachedIssuer = it }

    private val buyer =
        InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private fun billId(): Long =
        transaction {
            Bill
                .all()
                .first()
                .id.value
        }

    @Test
    fun `a bill with no invoice reports none, and an issued one reports its number`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val id = billId()

            transaction {
                assertNull(Bill.findById(id)!!.toResponseDto(SalesInvoiceBillInvoiceLink()).salesInvoiceId, "no invoice yet")
                assertNull(Bill.findById(id)!!.toResponseDto(SalesInvoiceBillInvoiceLink()).salesInvoiceNumber, "no number yet")
            }

            val draftId = runBlocking { service().createFromBill(id, buyer, createdBy = null) }.id.value
            val issued = runBlocking { service().issue(draftId, issuedBy = issuer()) }
            val issuedNumber = transaction { issued.invoiceNumber }

            transaction {
                val dto = Bill.findById(id)!!.toResponseDto(SalesInvoiceBillInvoiceLink())
                assertEquals(draftId, dto.salesInvoiceId)
                assertNotNull(issuedNumber, "issuing consumes a number")
                assertEquals(issuedNumber, dto.salesInvoiceNumber)
            }
        }
    }

    @Test
    fun `a cancelled invoice frees the bill's slot again`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val id = billId()

            val draftId = runBlocking { service().createFromBill(id, buyer, createdBy = null) }.id.value
            runBlocking { service().issue(draftId, issuedBy = issuer()) }
            runBlocking { service().cancel(draftId, cancelledBy = issuer(), reason = "Tippfehler") }

            transaction {
                assertNull(
                    Bill.findById(id)!!.toResponseDto(SalesInvoiceBillInvoiceLink()).salesInvoiceId,
                    "cancelling frees the bill's slot so a corrected invoice can be issued",
                )
            }
        }
    }
}
