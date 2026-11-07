package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.einvoice.DefaultEInvoiceParser
import org.example.project.einvoice.EInvoiceParseResult
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 8 — the issue-flow hook. A debtor who opted into ZUGFeRD gets a hybrid PDF/A-3 e-invoice
 * archived at issue; a debtor on NONE keeps the plain PDF; and a ZUGFeRD invoice missing an EN 16931
 * required field is blocked with a 422 **before** the §14 number is consumed, so a refused issue
 * leaves no gap in the sequence.
 */
class SalesInvoiceEInvoiceIssueTest {
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
            TestData.createAdmin(employeeNumber = "EIV01", username = "einvoiceissuer").id.value
        }.also { cachedIssuer = it }

    /** A debtor with the given e-invoice preference. */
    private fun debtorWith(format: EInvoiceFormatPreference): Long =
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
                    this.defaultRevenueAccountNo = "8400"
                    this.einvoiceFormat = format
                }.id.value
        }

    /** A standalone request with a full buyer address (a valid EN 16931 buyer). */
    private fun fullAddressRequest(debtorId: Long) =
        SalesInvoiceDto.CreateStandaloneRequestDto(
            buyerName = "Musterfirma GmbH",
            buyerStreet = "Industriestr. 5",
            buyerZip = "10117",
            buyerCity = "Berlin",
            buyerCountry = "DE",
            serviceDate = "2026-08-16T10:00:00",
            paymentTermsText = "Zahlbar innerhalb von 14 Tagen",
            revenueAccountNo = "8400",
            debtorId = debtorId,
            lines =
                listOf(
                    SalesInvoiceDto.StandaloneLineInputDto(
                        itemName = "Raummiete",
                        quantity = "1",
                        grossUnitPrice = "119.00",
                        vatRate = "19.00",
                    ),
                ),
        )

    private fun numberSuffix(number: String): Int = number.substringAfterLast('-').toInt()

    @Test
    fun `a ZUGFERD debtor gets a hybrid e-invoice archived at issue`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val svc = service()
            val debtorId = debtorWith(EInvoiceFormatPreference.ZUGFERD)
            val created = runBlocking { svc.createStandaloneAsDto(fullAddressRequest(debtorId), createdBy = issuer()) }

            runBlocking { svc.issue(created.id, issuedBy = issuer()) }

            val number = transaction { SalesInvoice.findById(created.id)!!.invoiceNumber!! }
            val bytes = runBlocking { svc.reprint(created.id) }

            val result = DefaultEInvoiceParser().parse(bytes, "application/pdf")
            assertTrue(
                result is EInvoiceParseResult.Success,
                "the archived file must carry an embedded EN 16931 e-invoice, not be a plain PDF; got $result",
            )
            assertEquals(number, (result as EInvoiceParseResult.Success).invoice.invoiceNumber)
        }
    }

    @Test
    fun `a free-text shop country and four-decimal storage still produce a valid EN 16931 payload`() {
        // The setup wizard's default is the English word; sales_invoice stores money at DECIMAL(19,4).
        testApp(setupData = { TestData.createShopInfo(shopCountry = "Germany") }) {
            val svc = service()
            val debtorId = debtorWith(EInvoiceFormatPreference.ZUGFERD)
            val created = runBlocking { svc.createStandaloneAsDto(fullAddressRequest(debtorId), createdBy = issuer()) }

            runBlocking { svc.issue(created.id, issuedBy = issuer()) }

            val result = DefaultEInvoiceParser().parse(runBlocking { svc.reprint(created.id) }, "application/pdf")
            assertTrue(result is EInvoiceParseResult.Success, "got $result")
            val parsed = (result as EInvoiceParseResult.Success).invoice
            assertEquals("DE", parsed.seller.country, "BT-40 must be the ISO 3166-1 alpha-2 code, not 'Germany'")
            // The parser keeps the written scale, so these read the XML's lexical form (BR-DEC).
            val amounts =
                listOfNotNull(parsed.lineNetTotal, parsed.taxExclusiveTotal, parsed.taxTotal, parsed.taxInclusiveTotal, parsed.amountDue) +
                    parsed.lines.map { it.lineNetAmount } + parsed.vatBreakdown.flatMap { listOf(it.taxableAmount, it.vatAmount) }
            assertTrue(amounts.all { it.scale() == 2 }, "every BR-DEC amount must carry two decimals, got $amounts")
            assertEquals(0, java.math.BigDecimal("119.00").compareTo(parsed.taxInclusiveTotal), "the value is unchanged")
            assertNull(
                org.example.project.einvoice.check.En16931AmountRules
                    .check(parsed),
            )
        }
    }

    @Test
    fun `an unrecognised shop country blocks the ZUGFeRD issue and consumes no number`() {
        testApp(setupData = { TestData.createShopInfo(shopCountry = "Atlantis") }) {
            val svc = service()
            val debtorId = debtorWith(EInvoiceFormatPreference.ZUGFERD)
            val created = runBlocking { svc.createStandaloneAsDto(fullAddressRequest(debtorId), createdBy = issuer()) }

            val error = assertFailsWith<PosException> { runBlocking { svc.issue(created.id, issuedBy = issuer()) } }

            assertEquals(PosErrorCode.SALES_INVOICE_EINVOICE_INVALID, error.code)
            assertTrue(error.message!!.contains("BT-40") && error.message!!.contains("Atlantis"), "message was: ${error.message}")
            transaction { assertNull(SalesInvoice.findById(created.id)!!.invoiceNumber, "a blocked issue consumes no number") }
        }
    }

    @Test
    fun `a NONE debtor keeps a plain PDF with no embedded e-invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val svc = service()
            val debtorId = debtorWith(EInvoiceFormatPreference.NONE)
            val created = runBlocking { svc.createStandaloneAsDto(fullAddressRequest(debtorId), createdBy = issuer()) }

            runBlocking { svc.issue(created.id, issuedBy = issuer()) }

            val bytes = runBlocking { svc.reprint(created.id) }
            val result = DefaultEInvoiceParser().parse(bytes, "application/pdf")
            assertTrue(
                result is EInvoiceParseResult.NoEInvoiceFound,
                "a NONE debtor's invoice is a plain PDF with no embedded payload; got $result",
            )
        }
    }

    @Test
    fun `a ZUGFERD invoice missing a required EN 16931 field is blocked and consumes no number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val svc = service()
            val zugferdDebtor = debtorWith(EInvoiceFormatPreference.ZUGFERD)

            // A valid invoice first, so we can prove the sequence is consecutive across the block.
            val valid1 = runBlocking { svc.createStandaloneAsDto(fullAddressRequest(zugferdDebtor), createdBy = issuer()) }
            runBlocking { svc.issue(valid1.id, issuedBy = issuer()) }
            val n1 = transaction { SalesInvoice.findById(valid1.id)!!.invoiceNumber!! }

            // A ZUGFeRD invoice with no buyer address — an EN 16931 required field (BG-8) is missing.
            val noAddress =
                fullAddressRequest(zugferdDebtor).copy(
                    buyerStreet = null,
                    buyerZip = null,
                    buyerCity = null,
                )
            val invalid = runBlocking { svc.createStandaloneAsDto(noAddress, createdBy = issuer()) }

            val error =
                assertFailsWith<PosException> {
                    runBlocking { svc.issue(invalid.id, issuedBy = issuer()) }
                }
            assertEquals(PosErrorCode.SALES_INVOICE_EINVOICE_INVALID, error.code)
            assertTrue(
                error.message!!.contains("buyer address", ignoreCase = true),
                "the 422 must name the missing field; message was: ${error.message}",
            )

            // The blocked invoice never became ISSUED and took no number.
            transaction {
                val stillDraft = SalesInvoice.findById(invalid.id)!!
                assertEquals(SalesInvoiceStatus.DRAFT, stillDraft.status, "a blocked issue must leave the draft a draft")
                assertNull(stillDraft.invoiceNumber, "a blocked issue must consume no §14 number")
            }

            // A subsequent valid issue is consecutive to n1 — the failed one left no gap.
            val valid2 = runBlocking { svc.createStandaloneAsDto(fullAddressRequest(zugferdDebtor), createdBy = issuer()) }
            runBlocking { svc.issue(valid2.id, issuedBy = issuer()) }
            val n2 = transaction { SalesInvoice.findById(valid2.id)!!.invoiceNumber!! }

            assertEquals(
                numberSuffix(n1) + 1,
                numberSuffix(n2),
                "the number after the block ($n2) must follow the last valid one ($n1) with no gap",
            )
        }
    }
}
