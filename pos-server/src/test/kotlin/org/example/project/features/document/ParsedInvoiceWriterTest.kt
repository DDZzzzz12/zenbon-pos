package org.example.project.features.document

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.ParsedInvoiceWriter
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
import kotlin.test.assertNotNull

/**
 * Pins the bridge between the `:accounting-einvoice` parser and the normalized invoice model.
 *
 * Deliberately split across two transactions: within one transaction Exposed's identity map can
 * serve back the entity just written rather than what the columns actually stored.
 */
class ParsedInvoiceWriterTest {
    private val repo = DocumentRepositoryImpl()
    private val writer = ParsedInvoiceWriter(repo)

    private fun seller() =
        EInvoiceParty(
            name = "Kaffeerösterei Nord GmbH",
            vatId = "DE123456789",
            taxNumber = "12/345/67890",
            street = "Hafenstraße 12",
            zip = "20457",
            city = "Hamburg",
            country = "DE",
            phone = "+49 40 123456",
            email = "rechnung@roesterei-nord.de",
        )

    /** A fully-populated parse result — every optional term filled so nothing can be silently dropped. */
    private fun parsedInvoice(
        invoiceNumber: String = "RE-2024-0815",
        lines: List<EInvoiceLine> = listOf(coffeeLine("1"), coffeeLine("2")),
        allowancesCharges: List<EInvoiceAllowanceCharge> = emptyList(),
    ) = EInvoice(
        format = EInvoiceFormat.FACTUR_X,
        invoiceNumber = invoiceNumber,
        issueDate = LocalDate(2024, 3, 4),
        currency = "EUR",
        seller = seller(),
        vatBreakdown =
            listOf(
                EInvoiceVatBreakdown(
                    categoryCode = "S",
                    rate = BigDecimal("19.00"),
                    taxableAmount = BigDecimal("100.0000"),
                    vatAmount = BigDecimal("19.0000"),
                ),
                EInvoiceVatBreakdown(
                    categoryCode = "E",
                    rate = BigDecimal("0.00"),
                    taxableAmount = BigDecimal("50.0000"),
                    vatAmount = BigDecimal("0.0000"),
                    exemptionReasonCode = "VATEX-EU-O",
                    exemptionReasonText = "Nicht steuerbarer Umsatz",
                ),
            ),
        lines = lines,
        allowancesCharges = allowancesCharges,
        dueDate = LocalDate(2024, 4, 3),
        deliveryDate = LocalDate(2024, 3, 1),
        buyerReference = "04011000-12345-67",
        paymentIban = "DE02120300000000202051",
        paymentReference = "RF18539007547034",
        paymentTermsText = "Zahlbar innerhalb 30 Tagen ohne Abzug",
        lineNetTotal = BigDecimal("150.0000"),
        taxExclusiveTotal = BigDecimal("150.0000"),
        taxTotal = BigDecimal("19.0000"),
        taxInclusiveTotal = BigDecimal("169.0000"),
        amountDue = BigDecimal("169.0000"),
    )

    private fun coffeeLine(lineId: String) =
        EInvoiceLine(
            lineId = lineId,
            itemName = "Espresso-Bohnen",
            quantity = BigDecimal("2.0000"),
            unitCode = "KGM",
            netUnitPrice = BigDecimal("50.0000"),
            lineNetAmount = BigDecimal("100.0000"),
            vatCategoryCode = "S",
            vatRate = BigDecimal("19.00"),
            itemDescription = "100 % Arabica, ganze Bohne",
            sellerItemId = "ART-4711",
            buyerItemId = "EK-99",
            itemStandardId = "4012345678901",
        )

    /** Creates a source document through the repository. Paths and hashes must be unique. */
    private fun sourceDocument(uploadedBy: UUID): Long {
        val document =
            repo.create(
                filename = "ZUGFeRD_Rechnung.pdf",
                fileType = "INVOICE",
                filePath = "/tmp/parsed-${System.nanoTime()}.pdf",
                fileSize = 2048L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = uploadedBy,
                contentHash = "hash-${System.nanoTime()}",
            )
        return document.id.value
    }

    @Test
    fun `a parsed invoice is written with its lines, VAT breakdown and EINVOICE_XML provenance`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId = transaction { writer.write(parsedInvoice(), sourceDocumentId = null) }

            transaction {
                val invoice = assertNotNull(repo.getInvoice(invoiceId))

                assertEquals("EINVOICE_XML", invoice.extractionSource)
                assertEquals("factur-x", invoice.eInvoiceFormat)
                assertEquals("RE-2024-0815", invoice.invoiceNumber)
                assertEquals(LocalDateTime(2024, 3, 4, 0, 0), invoice.issueDate)
                assertEquals(LocalDateTime(2024, 4, 3, 0, 0), invoice.dueDate)
                assertEquals(LocalDateTime(2024, 3, 1, 0, 0), invoice.deliveryDate)
                assertEquals("EUR", invoice.currency)
                assertEquals("04011000-12345-67", invoice.buyerReference)

                assertEquals("Kaffeerösterei Nord GmbH", invoice.sellerName)
                assertEquals("DE123456789", invoice.sellerVatId)
                assertEquals("12/345/67890", invoice.sellerTaxNumber)
                assertEquals("Hafenstraße 12", invoice.sellerStreet)
                assertEquals("20457", invoice.sellerZip)
                assertEquals("Hamburg", invoice.sellerCity)
                assertEquals("DE", invoice.sellerCountry)
                assertEquals("+49 40 123456", invoice.sellerPhone)
                assertEquals("rechnung@roesterei-nord.de", invoice.sellerEmail)

                assertEquals("DE02120300000000202051", invoice.paymentIban)
                assertEquals("RF18539007547034", invoice.paymentReference)
                assertEquals("Zahlbar innerhalb 30 Tagen ohne Abzug", invoice.paymentTermsText)

                assertEquals(BigDecimal("150.0000"), invoice.lineNetTotal)
                assertEquals(BigDecimal("150.0000"), invoice.taxExclusiveTotal)
                assertEquals(BigDecimal("19.0000"), invoice.taxTotal)
                assertEquals(BigDecimal("169.0000"), invoice.taxInclusiveTotal)
                assertEquals(BigDecimal("169.0000"), invoice.amountDue)

                assertEquals(2, invoice.lines.count().toInt())
                val line = invoice.lines.first { it.lineId == "1" }
                assertEquals("Espresso-Bohnen", line.itemName)
                assertEquals(BigDecimal("2.0000"), line.quantity)
                assertEquals("KGM", line.unitCode)
                // Four decimals survive — a scale-2 column or a rounding step would break this.
                assertEquals(BigDecimal("50.0000"), line.netUnitPrice)
                assertEquals(BigDecimal("100.0000"), line.lineNetAmount)
                assertEquals("S", line.vatCategoryCode)
                assertEquals(BigDecimal("19.00"), line.vatRate)
                assertEquals("100 % Arabica, ganze Bohne", line.itemDescription)
                assertEquals("ART-4711", line.sellerItemId)
                assertEquals("EK-99", line.buyerItemId)
                assertEquals("4012345678901", line.itemStandardId)

                assertEquals(2, invoice.vatBreakdown.count().toInt())
                val standard = invoice.vatBreakdown.first { it.vatCategoryCode == "S" }
                assertEquals(BigDecimal("19.00"), standard.vatRate)
                assertEquals(BigDecimal("100.0000"), standard.taxableAmount)
                assertEquals(BigDecimal("19.0000"), standard.vatAmount)

                val exempt = invoice.vatBreakdown.first { it.vatCategoryCode == "E" }
                assertEquals("VATEX-EU-O", exempt.exemptionReasonCode)
                assertEquals("Nicht steuerbarer Umsatz", exempt.exemptionReasonText)
            }
        }
    }

    @Test
    fun `re-parsing the same source document replaces the invoice instead of duplicating it`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            val documentId =
                transaction {
                    val docId = sourceDocument(admin!!)
                    writer.write(parsedInvoice(invoiceNumber = "RE-FIRST"), sourceDocumentId = docId)
                    docId
                }

            transaction {
                writer.write(
                    parsedInvoice(
                        invoiceNumber = "RE-SECOND",
                        lines = listOf(coffeeLine("1")),
                    ),
                    sourceDocumentId = documentId,
                )
            }

            transaction {
                assertEquals(
                    1L,
                    Invoice.find { InvoiceTable.sourceDocumentId eq documentId }.count(),
                )
                val invoice = assertNotNull(repo.getInvoiceBySourceDocument(documentId))
                assertEquals("RE-SECOND", invoice.invoiceNumber)
                assertEquals(1, invoice.lines.count().toInt())
                assertEquals(2, invoice.vatBreakdown.count().toInt())
                assertEquals("EINVOICE_XML", invoice.extractionSource)
            }
        }
    }

    @Test
    fun `BT-3 and BT-25 from a parsed credit note reach the invoice row`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            val creditNote =
                parsedInvoice().copy(
                    invoiceTypeCode = "381",
                    precedingInvoiceReference = "RE-2024-0815",
                )

            // Both legs of writeHeader: the create leg first, then the re-parse update leg on the
            // same source document. A term added to only one of the two would pass one and fail
            // the other, which is exactly what ParsedInvoiceWriter's KDoc asks this to catch.
            val documentId =
                transaction {
                    val docId = sourceDocument(admin!!)
                    writer.write(creditNote, sourceDocumentId = docId)
                    docId
                }

            transaction {
                val invoice = assertNotNull(repo.getInvoiceBySourceDocument(documentId))
                assertEquals("381", invoice.invoiceTypeCode, "a Gutschrift must be marked as one")
                assertEquals("RE-2024-0815", invoice.precedingInvoiceReference)
            }

            transaction { writer.write(creditNote, sourceDocumentId = documentId) }

            transaction {
                val invoice = assertNotNull(repo.getInvoiceBySourceDocument(documentId))
                assertEquals("381", invoice.invoiceTypeCode, "the update leg must carry BT-3 too")
                assertEquals("RE-2024-0815", invoice.precedingInvoiceReference)
            }
        }
    }

    @Test
    fun `document-level allowances and charges are written too`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    writer.write(
                        parsedInvoice(
                            allowancesCharges =
                                listOf(
                                    EInvoiceAllowanceCharge(
                                        isCharge = false,
                                        amount = BigDecimal("12.3456"),
                                        vatCategoryCode = "S",
                                        vatRate = BigDecimal("19.00"),
                                        baseAmount = BigDecimal("250.7500"),
                                        percentage = BigDecimal("4.50"),
                                        reasonCode = "95",
                                        reasonText = "Skonto-Abzug",
                                    ),
                                    EInvoiceAllowanceCharge(
                                        isCharge = true,
                                        amount = BigDecimal("7.8900"),
                                        vatCategoryCode = "Z",
                                        vatRate = BigDecimal("0.00"),
                                        baseAmount = BigDecimal("300.1200"),
                                        percentage = BigDecimal("2.63"),
                                        reasonCode = "FC",
                                        reasonText = "Frachtkosten",
                                    ),
                                ),
                        ),
                        sourceDocumentId = null,
                    )
                }

            transaction {
                val invoice = assertNotNull(repo.getInvoice(invoiceId))
                assertEquals(2, invoice.allowancesCharges.count().toInt())

                val allowance = invoice.allowancesCharges.first { !it.isCharge }
                assertEquals(BigDecimal("12.3456"), allowance.amount)
                assertEquals("S", allowance.vatCategoryCode)
                assertEquals(BigDecimal("19.00"), allowance.vatRate)
                assertEquals(BigDecimal("250.7500"), allowance.baseAmount)
                assertEquals(BigDecimal("4.50"), allowance.percentage)
                assertEquals("95", allowance.reasonCode)
                assertEquals("Skonto-Abzug", allowance.reasonText)

                val charge = invoice.allowancesCharges.first { it.isCharge }
                assertEquals(BigDecimal("7.8900"), charge.amount)
                assertEquals("Z", charge.vatCategoryCode)
                assertEquals(BigDecimal("0.00"), charge.vatRate)
                assertEquals(BigDecimal("300.1200"), charge.baseAmount)
                assertEquals(BigDecimal("2.63"), charge.percentage)
                assertEquals("FC", charge.reasonCode)
                assertEquals("Frachtkosten", charge.reasonText)
            }
        }
    }
}
