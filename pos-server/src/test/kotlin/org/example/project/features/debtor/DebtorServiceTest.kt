package org.example.project.features.debtor

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.data.service.DebtorServiceImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules this slice adds on top of the repository: what may be created, what a number collision
 * costs, and why a debtor an invoice points at cannot be deleted.
 */
class DebtorServiceTest {
    private fun service() = DebtorServiceImpl(DebtorRepositoryImpl())

    private fun create(
        name: String = "Musterfirma GmbH",
        debtorNo: String? = null,
        vatId: String? = null,
    ) = runBlocking {
        service().create(
            DebtorDto.CreateRequestDto(name = name, debtorNo = debtorNo, vatId = vatId),
        )
    }

    @Test
    fun `a blank name is rejected before anything is written`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val error = assertFailsWith<PosException> { create(name = "   ") }

            assertEquals(PosErrorCode.VALIDATION_FAILED, error.code)
            assertEquals(HttpStatusCode.BadRequest, error.httpStatus)
            transaction { assertEquals(0, Debtor.all().count(), "nothing was written") }
        }
    }

    @Test
    fun `a debtor number is never handed to a second debtor`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            create(name = "Erste GmbH", debtorNo = "10247")

            val error = assertFailsWith<PosException> { create(name = "Zweite GmbH", debtorNo = "10247") }

            assertEquals(PosErrorCode.DEBTOR_NUMBER_TAKEN, error.code)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus)
        }
    }

    @Test
    fun `a VAT id collides regardless of spacing and case`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            create(name = "Erste GmbH", vatId = "DE123456789")

            val error = assertFailsWith<PosException> { create(name = "Zweite GmbH", vatId = "de 123 456 789") }

            assertEquals(PosErrorCode.DEBTOR_VAT_ID_TAKEN, error.code)
        }
    }

    @Test
    fun `keeping a debtor's own number while editing is not a collision`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = create(name = "Erste GmbH", debtorNo = "10247", vatId = "DE123456789")

            val updated =
                runBlocking {
                    service().update(
                        created.id,
                        DebtorDto.UpdateRequestDto(
                            name = "Erste Handels GmbH",
                            debtorNo = "10247",
                            vatId = "DE123456789",
                        ),
                    )
                }

            assertEquals("Erste Handels GmbH", updated.name, "an unchanged number must not block a rename")
            assertEquals("10247", updated.debtorNo)
        }
    }

    @Test
    fun `deactivating removes a debtor from the active list but keeps the row`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = create(name = "Ehemalig GmbH")

            runBlocking {
                service().update(
                    created.id,
                    DebtorDto.UpdateRequestDto(name = created.name, isActive = false),
                )
            }

            val active = runBlocking { service().list(activeOnly = true, search = null, limit = 50, offset = 0) }
            val all = runBlocking { service().list(activeOnly = false, search = null, limit = 50, offset = 0) }

            assertTrue(active.items.none { it.id == created.id }, "deactivated debtors leave the active list")
            assertNotNull(all.items.firstOrNull { it.id == created.id }, "but the row is kept")
            assertEquals(1L, all.total)
        }
    }

    @Test
    fun `an unreferenced debtor can be deleted outright`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = create(name = "Tippfehler GmbH")

            runBlocking { service().delete(created.id) }

            val error = assertFailsWith<PosException> { runBlocking { service().get(created.id) } }
            assertEquals(HttpStatusCode.NotFound, error.httpStatus)
        }
    }

    @Test
    fun `a debtor an invoice points at is refused, and survives the refusal`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val created = create(name = "Rechnungsempfänger GmbH")
            linkInvoiceTo(created.id)

            val error = assertFailsWith<PosException> { runBlocking { service().delete(created.id) } }

            assertEquals(PosErrorCode.DEBTOR_IN_USE, error.code)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus)
            transaction {
                assertNotNull(Debtor.findById(created.id), "a refused delete must leave the row alone")
            }
            assertEquals(1L, runBlocking { service().get(created.id) }.salesInvoiceCount)
        }
    }

    /** Issues a real invoice for the seeded bill and attaches it to [debtorId]. */
    private fun linkInvoiceTo(debtorId: Long) {
        val invoiceService =
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
        val billId =
            transaction {
                org.example.project.features.bill.domain.models.Bill
                    .all()
                    .first()
                    .id.value
            }
        val draft =
            runBlocking {
                invoiceService.createFromBill(
                    billId,
                    InvoiceBuyer(name = "Rechnungsempfänger GmbH", street = "Weg 1", zip = "10117", city = "Berlin"),
                    createdBy = null,
                )
            }
        transaction {
            val invoice = SalesInvoice.findById(draft.id.value)!!
            invoice.debtor = Debtor.findById(debtorId)
            assertNull(null)
        }
    }
}
