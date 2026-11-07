package org.example.project.features.document

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The service half of the provenance guard.
 *
 * Stage 1 stopped the *legacy* path silently overwriting a parsed invoice. The new supplier-field
 * endpoint is an explicit operator action, so it must **reject** rather than silently skip —
 * otherwise the operator retypes a supplier's data, presses Save, and is told nothing.
 *
 * The predicate is `extractionSource == "EINVOICE_XML"`, not `!= "MANUAL"`, matching
 * `LegacyInvoiceSync`: `OCR_LLM` is documented on `InvoiceTable` but has never been written by
 * anything, so guarding it would guard a row that cannot exist.
 */
@DisplayName("DocumentManagementService — supplier content on a parsed invoice is read-only")
class InvoiceSupplierContentServiceTest {
    private val repo = DocumentRepositoryImpl()

    private fun service(): DocumentManagementService = GlobalContext.get().get()

    /** uploadedBy is a real FK: a random UUID fails the write with "Employee not found". */
    private fun uploaderId(): UUID =
        Employee
            .all()
            .first()
            .id.value

    private fun createDocument(): Long =
        repo
            .create(
                filename = "scan.pdf",
                fileType = "INVOICE",
                filePath = "documents/scan.pdf",
                fileSize = 1_024L,
                uploadedAt = LocalDateTime(2026, 3, 1, 9, 0),
                uploadedBy = uploaderId(),
                contentHash = "hash-${System.nanoTime()}",
                metadataJson = null,
                ocrText = null,
                mediaType = "application/pdf",
            ).id.value

    @Test
    fun `a supplier-field write on a parsed invoice is rejected and changes nothing`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    repo
                        .createInvoice(
                            sourceDocumentId = createDocument(),
                            eInvoiceFormat = "ubl",
                            extractionSource = "EINVOICE_XML",
                            invoiceNumber = "RE-XML-1",
                            issueDate = LocalDateTime(2026, 3, 1, 0, 0),
                            currency = "EUR",
                            sellerName = "Lieferant aus XML GmbH",
                            taxInclusiveTotal = BigDecimal("238.00"),
                        ).id.value
                }

            val thrown =
                assertFailsWith<PosException> {
                    runBlocking {
                        service().updateInvoiceSupplierContent(
                            invoiceId,
                            DocumentDto.InvoiceContentRequestDto(
                                invoiceNumber = "TIPPFEHLER-9",
                                sellerName = "Von Hand Getippt GmbH",
                                taxInclusiveTotal = "1.19",
                            ),
                        )
                    }
                }
            assertEquals(PosErrorCode.INVOICE_SUPPLIER_CONTENT_READ_ONLY, thrown.code)

            transaction {
                val invoice = assertNotNull(repo.getInvoice(invoiceId))
                assertEquals("EINVOICE_XML", invoice.extractionSource, "provenance must not be relabelled")
                assertEquals("RE-XML-1", invoice.invoiceNumber, "a rejected write must change nothing")
                assertEquals("Lieferant aus XML GmbH", invoice.sellerName)
                assertEquals(0, BigDecimal("238.00").compareTo(invoice.taxInclusiveTotal))
            }
        }
    }

    @Test
    fun `a manual invoice accepts a supplier-field write and keeps its provenance`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    repo
                        .createInvoice(
                            sourceDocumentId = createDocument(),
                            invoiceNumber = "RE-MANUAL-1",
                            sellerName = "Handerfasst GmbH",
                        ).id.value
                }

            val updated =
                runBlocking {
                    service().updateInvoiceSupplierContent(
                        invoiceId,
                        DocumentDto.InvoiceContentRequestDto(
                            invoiceNumber = "RE-MANUAL-1",
                            issueDate = "2026-03-03",
                            sellerName = "Korrigiert GmbH",
                            taxInclusiveTotal = "238.00",
                            invoiceTypeCode = "381",
                            precedingInvoiceReference = "RE-2024-0815",
                        ),
                    )
                }

            assertEquals("Korrigiert GmbH", updated.sellerName)
            assertEquals("381", updated.invoiceTypeCode, "a Gutschrift must be recordable by hand")
            assertEquals("RE-2024-0815", updated.precedingInvoiceReference)
            assertEquals("MANUAL", updated.extractionSource, "a content edit must not change provenance")
        }
    }

    /**
     * A four-decimal amount must survive the boundary. `DecimalFormatter` carries a scale-2-pinning
     * parse (`toBigDecimal(value)` / `toBigDecimalOrNull(value)`, both top-level functions taking a
     * parameter); the service must use the Kotlin stdlib `String.toBigDecimalOrNull()` extension
     * instead, or a BT-146 unit price is silently rounded on the way in.
     */
    @Test
    fun `a four-decimal amount is not rounded on the way in`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    repo.createInvoice(sourceDocumentId = createDocument(), invoiceNumber = "RE-SCALE").id.value
                }

            runBlocking {
                service().updateInvoiceSupplierContent(
                    invoiceId,
                    DocumentDto.InvoiceContentRequestDto(
                        invoiceNumber = "RE-SCALE",
                        lineNetTotal = "123.4567",
                    ),
                )
            }

            transaction {
                val stored = assertNotNull(repo.getInvoice(invoiceId)?.lineNetTotal)
                assertEquals(0, BigDecimal("123.4567").compareTo(stored), "the stored value must keep 4 decimals")
            }
        }
    }

    @Test
    fun `creating an invoice by hand stamps MANUAL and stores the VAT breakdown`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val documentId = transaction { createDocument() }

            val created =
                runBlocking {
                    service().createInvoiceForDocument(
                        documentId,
                        DocumentDto.InvoiceContentRequestDto(
                            invoiceNumber = "RE-NEU-1",
                            issueDate = "2026-03-04",
                            sellerName = "Neu Erfasst GmbH",
                            taxInclusiveTotal = "238.00",
                            vatBreakdown =
                                listOf(
                                    DocumentDto.VatBreakdownDto(
                                        vatCategoryCode = "S",
                                        vatRate = "19.00",
                                        taxableAmount = "200.00",
                                        vatAmount = "38.00",
                                    ),
                                ),
                        ),
                    )
                }

            assertEquals("MANUAL", created.extractionSource)
            assertEquals(documentId, created.sourceDocumentId)
            assertEquals(1, created.vatBreakdown.size)

            transaction {
                val invoice = assertNotNull(repo.getInvoiceBySourceDocument(documentId))
                assertEquals("RE-NEU-1", invoice.invoiceNumber)
                assertEquals(1, invoice.vatBreakdown.count().toInt())
            }
        }
    }
}
