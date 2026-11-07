package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.models.Bill
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
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The origin guard: the standalone editor must not be able to rewrite a bill-snapshotted draft, and
 * an issued invoice must still agree with the bill it claims to render.
 */
class SalesInvoiceOriginGuardTest {
    private val repository = SalesInvoiceRepositoryImpl()

    private fun service() =
        SalesInvoiceServiceImpl(
            repository = repository,
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
            TestData.createAdmin(employeeNumber = "OG001", username = "originguard").id.value
        }.also { cachedIssuer = it }

    private val buyer = InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private fun updateRequest() =
        SalesInvoiceDto.UpdateStandaloneRequestDto(
            buyerName = "Umgeschrieben GmbH",
            serviceDate = "2026-08-17T10:00:00",
            lines =
                listOf(
                    SalesInvoiceDto.StandaloneLineInputDto(
                        itemName = "Erfundene Position",
                        quantity = "1",
                        grossUnitPrice = "9999.00",
                        vatRate = "19.00",
                    ),
                ),
        )

    @Test
    fun `editing a POS_SETTLED draft through the standalone editor is refused and leaves it unchanged`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val billId =
                transaction {
                    Bill
                        .all()
                        .first()
                        .id.value
                }
            val draft = runBlocking { service().createFromBill(billId, buyer, createdBy = issuer()) }
            val originalTotal = draft.taxInclusiveTotal
            val originalLine =
                transaction {
                    SalesInvoice
                        .findById(draft.id.value)!!
                        .lines
                        .first()
                        .itemName
                }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service().updateStandaloneAsDto(draft.id.value, updateRequest()) }
                }
            assertEquals(PosErrorCode.SALES_INVOICE_ORIGIN_MISMATCH, error.code)

            transaction {
                val after = SalesInvoice.findById(draft.id.value)!!
                assertEquals(SalesInvoiceStatus.DRAFT, after.status)
                assertEquals(0, originalTotal!!.compareTo(after.taxInclusiveTotal), "the bill-snapshotted total must survive")
                assertEquals(originalLine, after.lines.first().itemName, "the bill-snapshotted lines must survive")
                assertEquals("Musterfirma GmbH", after.buyerName, "the buyer block must survive")
            }
        }
    }

    @Test
    fun `a POS_SETTLED draft whose totals no longer match its bill cannot be issued`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val billId =
                transaction {
                    Bill
                        .all()
                        .first()
                        .id.value
                }
            val draft = runBlocking { service().createFromBill(billId, buyer, createdBy = issuer()) }

            // The realistic attack: a future editor with only a status guard calls
            // `replaceDraftContent` directly (bypassing the origin check), rewriting lines AND
            // totals consistently — so the content validator passes and only the bill re-assertion
            // can catch it.
            transaction {
                val invoice = SalesInvoice.findById(draft.id.value)!!
                repository.replaceDraftContent(invoice, rewrittenContent())
            }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service().issue(draft.id.value, issuedBy = issuer()) }
                }
            assertEquals(PosErrorCode.SALES_INVOICE_AMOUNT_MISMATCH, error.code)

            transaction {
                val after = SalesInvoice.findById(draft.id.value)!!
                assertNull(after.invoiceNumber, "a rejected issuance must consume no number")
                assertEquals(SalesInvoiceStatus.DRAFT, after.status)
            }
        }
    }

    /** A self-consistent draft content whose total has nothing to do with the bill's 28.30. */
    private fun rewrittenContent(): org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceContent {
        val net = BigDecimal("999.0000")
        val vat = BigDecimal("189.8100")
        val gross = net.add(vat)
        val line =
            org.example.project.features.salesinvoice.domain.service.StandaloneLineContent(
                itemName = "Erfundene Position",
                itemDescription = null,
                quantity = BigDecimal.ONE,
                unitCode = "C62",
                grossUnitPrice = gross,
                netUnitPrice = net,
                lineNetAmount = net,
                vatRate = BigDecimal("19.00"),
                vatCategoryCode = "S",
                vatAmount = vat,
                lineGrossAmount = gross,
            )
        return org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceContent(
            buyerName = "Umgeschrieben GmbH",
            buyerVatId = null,
            buyerStreet = null,
            buyerZip = null,
            buyerCity = null,
            buyerCountry = null,
            buyerEmail = null,
            serviceDate = kotlinx.datetime.LocalDateTime(2026, 8, 17, 10, 0),
            dueDate = null,
            paymentTermsText = null,
            notes = null,
            lines = listOf(line),
            vatRows =
                listOf(
                    org.example.project.features.salesinvoice.domain.service.StandaloneVatRowContent(
                        vatRatePercent = BigDecimal("19.00"),
                        vatCategoryCode = "S",
                        taxableAmount = net,
                        vatAmount = vat,
                    ),
                ),
            lineNetTotal = net,
            taxTotal = vat,
            taxInclusiveTotal = gross,
        )
    }

    @Test
    fun `a POS_SETTLED draft still issues when its totals agree with the bill`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val billId =
                transaction {
                    Bill
                        .all()
                        .first()
                        .id.value
                }
            val draft = runBlocking { service().createFromBill(billId, buyer, createdBy = issuer()) }

            runBlocking { service().issue(draft.id.value, issuedBy = issuer()) }

            transaction {
                val after = SalesInvoice.findById(draft.id.value)!!
                assertEquals(SalesInvoiceStatus.ISSUED, after.status)
            }
        }
    }
}
