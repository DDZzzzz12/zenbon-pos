package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceBooking
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The case-C (STANDALONE) draft path: create, edit, delete, and the issue-time artefacts
 * (payment_reference + booking row) that 4d will consume.
 */
class SalesInvoiceStandaloneTest {
    /**
     * Built per test, not as a field: `testApp` starts Koin inside the test body, and the PDF
     * service is resolved from it. Matches `SalesInvoiceFromBillTest`'s pattern exactly.
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

    private var cachedIssuer: java.util.UUID? = null

    /** One employee per test — `issue()` needs a real issuer (its KDoc says not nullable). */
    private fun issuer(): java.util.UUID =
        cachedIssuer ?: transaction {
            TestData.createAdmin(employeeNumber = "SA001", username = "standaloneissuer").id.value
        }.also { cachedIssuer = it }

    private fun request(lines: List<SalesInvoiceDto.StandaloneLineInputDto> = listOf(lineInput())) =
        SalesInvoiceDto.CreateStandaloneRequestDto(
            buyerName = "Musterfirma GmbH",
            buyerStreet = "Industriestr. 5",
            buyerZip = "10117",
            buyerCity = "Berlin",
            serviceDate = "2026-08-16T10:00:00",
            paymentTermsText = "Zahlbar innerhalb von 14 Tagen",
            revenueAccountNo = "8400",
            lines = lines,
        )

    private fun lineInput(
        name: String = "Raummiete",
        gross: String = "119.00",
        rate: String = "19.00",
    ) = SalesInvoiceDto.StandaloneLineInputDto(itemName = name, quantity = "1", grossUnitPrice = gross, vatRate = rate)

    @Test
    fun `createStandalone writes a STANDALONE draft with derived totals and the seller snapshot`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val dto = runBlocking { service().createStandaloneAsDto(request(), createdBy = issuer()) }

            assertEquals(SalesInvoiceStatus.DRAFT.name, dto.status)
            assertEquals(RevenueOrigin.STANDALONE.name, dto.revenueOrigin)
            assertNull(dto.invoiceNumber, "a draft consumes no number")
            assertEquals("119.0000", dto.taxInclusiveTotal)
            assertEquals("19.0000", dto.taxTotal)
            assertEquals("Test Company", dto.sellerName, "the seller block is snapshotted from shop_info's company_name")
            assertEquals("Raummiete", dto.lines.single().itemName)
            assertEquals(1, dto.vatBreakdown.size)
        }
    }

    @Test
    fun `updateStandalone full-replaces the draft content`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = runBlocking { service().createStandaloneAsDto(request(), createdBy = issuer()) }

            val updated =
                runBlocking {
                    service().updateStandaloneAsDto(
                        created.id,
                        SalesInvoiceDto.UpdateStandaloneRequestDto(
                            buyerName = "Neue Firma GmbH",
                            serviceDate = "2026-08-17T10:00:00",
                            lines = listOf(lineInput(name = "Tagungsraum", gross = "107.00", rate = "7.00")),
                        ),
                    )
                }

            assertEquals("Neue Firma GmbH", updated.buyerName)
            assertEquals("Tagungsraum", updated.lines.single().itemName)
            assertEquals("107.0000", updated.taxInclusiveTotal)
            assertEquals("7.0000", updated.taxTotal)
        }
    }

    @Test
    fun `updateStandalone on an ISSUED invoice is refused`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = runBlocking { service().createStandaloneAsDto(request(), createdBy = issuer()) }
            runBlocking { service().issue(created.id, issuedBy = issuer()) }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service().updateStandaloneAsDto(
                            created.id,
                            SalesInvoiceDto.UpdateStandaloneRequestDto(
                                buyerName = "Später geändert",
                                serviceDate = "2026-08-17T10:00:00",
                                lines = listOf(lineInput()),
                            ),
                        )
                    }
                }
            assertEquals(PosErrorCode.SALES_INVOICE_IMMUTABLE, error.code)
        }
    }

    @Test
    fun `deleteStandalone removes a draft and leaves nothing behind`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = runBlocking { service().createStandaloneAsDto(request(), createdBy = issuer()) }

            runBlocking { service().deleteStandalone(created.id) }

            transaction {
                assertNull(SalesInvoice.findById(created.id))
            }
        }
    }

    @Test
    fun `issuing a STANDALONE invoice writes payment_reference and the booking row`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created = runBlocking { service().createStandaloneAsDto(request(), createdBy = issuer()) }

            runBlocking { service().issue(created.id, issuedBy = issuer()) }

            transaction {
                val issued = SalesInvoice.findById(created.id)!!
                assertEquals(SalesInvoiceStatus.ISSUED, issued.status)
                assertEquals(issued.invoiceNumber, issued.paymentReference, "the number is the Verwendungszweck")
                assertEquals(0, issued.taxInclusiveTotal!!.compareTo(issued.openAmount), "STANDALONE is fully open")
                assertEquals(0, issued.taxInclusiveTotal!!.compareTo(issued.amountDue))

                val booking = SalesInvoiceBooking.find { SalesInvoiceBookingTable.salesInvoiceId eq issued.id }.firstOrNull()
                assertEquals("8400", booking?.revenueAccountNo)
                assertNull(booking?.bpAccountNo, "4d resolves the Personenkonto")
                assertNull(booking?.postedAt, "4d posts")
            }
        }
    }

    @Test
    fun `a save-nothing round trip preserves payment terms, booking and line descriptions`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val withAllFields =
                request(
                    lines =
                        listOf(
                            lineInput(name = "Raummiete", gross = "119.00", rate = "19.00")
                                .copy(itemDescription = "Saal, 50 Plätze, Technik inklusive"),
                        ),
                ).copy(
                    paymentTermsText = "Zahlbar innerhalb von 14 Tagen ohne Abzug",
                    revenueAccountNo = "8405",
                    buCode = "99",
                )
            val created = runBlocking { service().createStandaloneAsDto(withAllFields, createdBy = issuer()) }

            // The editor round trip: read the response, PUT exactly what it yields back, and the
            // fields that live only on the request DTOs must survive — a full replace that cannot
            // see them nulls them silently.
            val readBack = runBlocking { service().getAsDto(created.id) }
            val roundTrip =
                runBlocking {
                    service().updateStandaloneAsDto(
                        readBack.id,
                        SalesInvoiceDto.UpdateStandaloneRequestDto(
                            buyerName = readBack.buyerName,
                            serviceDate = readBack.serviceDate,
                            dueDate = readBack.dueDate,
                            paymentTermsText = readBack.paymentTermsText,
                            revenueAccountNo = readBack.revenueAccountNo,
                            buCode = readBack.buCode,
                            notes = readBack.notes,
                            lines =
                                readBack.lines.map {
                                    SalesInvoiceDto.StandaloneLineInputDto(
                                        itemName = it.itemName,
                                        itemDescription = it.itemDescription,
                                        quantity = it.quantity,
                                        grossUnitPrice = it.grossAmount,
                                        vatRate = it.vatRate,
                                    )
                                },
                        ),
                    )
                }

            assertEquals("Zahlbar innerhalb von 14 Tagen ohne Abzug", roundTrip.paymentTermsText)
            assertEquals("8405", roundTrip.revenueAccountNo, "the booking row's account must survive the round trip")
            assertEquals("99", roundTrip.buCode)
            assertEquals("Saal, 50 Plätze, Technik inklusive", roundTrip.lines.single().itemDescription)
        }
    }

    @Test
    fun `a sub-cent unit price survives a save-nothing reopen byte-identically`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val withSubCentUnit =
                request(
                    lines =
                        listOf(
                            lineInput(name = "Raumpauschale", gross = "3.3333", rate = "19.00")
                                .copy(quantity = "3"),
                        ),
                )
            val created = runBlocking { service().createStandaloneAsDto(withSubCentUnit, createdBy = issuer()) }
            val originalTotal = created.taxInclusiveTotal

            val readBack = runBlocking { service().getAsDto(created.id) }
            assertEquals("3.3333", readBack.lines.single().grossUnitPrice, "the typed unit price must come back verbatim")

            val roundTrip =
                runBlocking {
                    service().updateStandaloneAsDto(
                        readBack.id,
                        SalesInvoiceDto.UpdateStandaloneRequestDto(
                            buyerName = readBack.buyerName,
                            serviceDate = readBack.serviceDate,
                            lines =
                                readBack.lines.map {
                                    SalesInvoiceDto.StandaloneLineInputDto(
                                        itemName = it.itemName,
                                        itemDescription = it.itemDescription,
                                        quantity = it.quantity,
                                        grossUnitPrice = it.grossUnitPrice,
                                        vatRate = it.vatRate,
                                    )
                                },
                        ),
                    )
                }

            assertEquals(
                originalTotal,
                roundTrip.taxInclusiveTotal,
                "3 x 3.3333 = 9.9999 must stay 9.9999 — a reopen that re-derives the unit price at " +
                    "scale 2 would re-save 3.33 and land at 9.99",
            )
        }
    }

    @Test
    fun `issuing a STANDALONE invoice with a linked debtor carries the debtor's booking defaults`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val debtorId =
                transaction {
                    Debtor
                        .new {
                            // `partner_id` is non-null: the debtor role row needs a party.
                            this.partner =
                                BusinessPartner.new {
                                    this.shop = ShopInfo.all().first()
                                    this.name = "Musterfirma GmbH"
                                }
                            this.shop = ShopInfo.all().first()
                            this.defaultRevenueAccountNo = "8405"
                            this.defaultBuCode = "99"
                        }.id.value
                }

            val dto =
                runBlocking {
                    service().createStandaloneAsDto(request().copy(revenueAccountNo = null, debtorId = debtorId), createdBy = issuer())
                }
            assertEquals(debtorId, dto.debtorId)

            runBlocking { service().issue(dto.id, issuedBy = issuer()) }

            transaction {
                val booking = SalesInvoiceBooking.find { SalesInvoiceBookingTable.salesInvoiceId eq dto.id }.firstOrNull()
                assertEquals("8405", booking?.revenueAccountNo, "the debtor's default fills a row the editor left empty")
            }
        }
    }

    private fun debtor(active: Boolean): Long =
        transaction {
            Debtor
                .new {
                    this.partner =
                        BusinessPartner.new {
                            this.shop = ShopInfo.all().first()
                            this.name = "Musterfirma GmbH"
                        }
                    this.shop = ShopInfo.all().first()
                    this.isActive = active
                }.id.value
        }

    @Test
    fun `a new invoice for a deactivated debtor is refused by the server`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val inactive = debtor(active = false)

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service().createStandaloneAsDto(request().copy(debtorId = inactive), createdBy = issuer()) }
                }

            assertEquals(PosErrorCode.DEBTOR_INACTIVE, error.code)
            assertEquals(io.ktor.http.HttpStatusCode.Conflict, error.httpStatus)
            assertEquals(0L, transaction { SalesInvoice.all().count() }, "a refused create writes no draft")
        }
    }

    @Test
    fun `a draft whose debtor was deactivated afterwards cannot be issued and consumes no number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val debtorId = debtor(active = true)
            val draft = runBlocking { service().createStandaloneAsDto(request().copy(debtorId = debtorId), createdBy = issuer()) }
            transaction { Debtor.findById(debtorId)!!.isActive = false }

            val error = assertFailsWith<PosException> { runBlocking { service().issue(draft.id, issuedBy = issuer()) } }

            assertEquals(PosErrorCode.DEBTOR_INACTIVE, error.code)
            transaction {
                val stillDraft = SalesInvoice.findById(draft.id)!!
                assertEquals(SalesInvoiceStatus.DRAFT, stillDraft.status)
                assertNull(stillDraft.invoiceNumber, "a refused issue must leave no gap in the §14 sequence")
            }
        }
    }

    /**
     * E2E 2026-09-27: the refusal read "Debtor null is deactivated…" for a debtor without a
     * Debitorennummer. It names the debtor's partner instead, falling back to the id.
     */
    @Test
    fun `the DEBTOR_INACTIVE message names a debtor that has no number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val inactive = debtor(active = false)

            val error =
                assertFailsWith<PosException> {
                    runBlocking { service().createStandaloneAsDto(request().copy(debtorId = inactive), createdBy = issuer()) }
                }

            val message = error.message.orEmpty()
            assertFalse(message.contains("null"), "a missing Debitorennummer must not print as 'null': $message")
            assertTrue(message.contains("Musterfirma GmbH"), "the debtor is named by its partner: $message")
        }
    }

    /**
     * A draft for a debtor deactivated after it was written can no longer be issued, so editing it
     * is work that cannot lead anywhere — and would change the content of an invoice the debtor
     * no longer takes. Refused like the issue, with the same 409; deleting the draft stays possible.
     */
    @Test
    fun `a draft whose debtor was deactivated cannot be edited`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val debtorId = debtor(active = true)
            val draft = runBlocking { service().createStandaloneAsDto(request().copy(debtorId = debtorId), createdBy = issuer()) }
            transaction { Debtor.findById(debtorId)!!.isActive = false }

            val error =
                assertFailsWith<PosException> {
                    runBlocking {
                        service().updateStandaloneAsDto(
                            draft.id,
                            SalesInvoiceDto.UpdateStandaloneRequestDto(
                                debtorId = debtorId,
                                buyerName = "Später geändert",
                                serviceDate = "2026-08-17T10:00:00",
                                lines = listOf(lineInput()),
                            ),
                        )
                    }
                }

            assertEquals(PosErrorCode.DEBTOR_INACTIVE, error.code)
            assertEquals(io.ktor.http.HttpStatusCode.Conflict, error.httpStatus)
            assertEquals("Musterfirma GmbH", transaction { SalesInvoice.findById(draft.id)!!.buyerName }, "nothing was rewritten")

            runBlocking { service().deleteStandalone(draft.id) }
            assertNull(transaction { SalesInvoice.findById(draft.id) }, "the way out — deleting the draft — stays open")
        }
    }

    @Test
    fun `a POS_SETTLED invoice still gets no booking row`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val billId =
                transaction {
                    Bill
                        .all()
                        .first()
                        .id.value
                }
            val draft =
                runBlocking {
                    service().createFromBill(
                        billId,
                        InvoiceBuyer(name = "Musterfirma GmbH"),
                        createdBy = issuer(),
                    )
                }
            runBlocking { service().issue(draft.id.value, issuedBy = issuer()) }

            transaction {
                val issued = SalesInvoice.findById(draft.id.value)!!
                assertEquals(0, BigDecimal.ZERO.compareTo(issued.openAmount), "POS_SETTLED is paid at birth")
                assertTrue(SalesInvoiceBooking.find { SalesInvoiceBookingTable.salesInvoiceId eq issued.id }.empty(), "no booking row for POS_SETTLED")
            }
        }
    }
}
