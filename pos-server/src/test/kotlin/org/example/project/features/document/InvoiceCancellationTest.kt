package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.AllocationFixtures
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.NewAllocation
import org.example.project.features.allocation.domain.service.AllocationService
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.InvoiceCancellationService
import org.example.project.features.document.domain.service.InvoiceListService
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T9-19: a Beleg with an incoming invoice is never deleted — the invoice is cancelled by a correction
 * (Gegenbuchung / Storno). The original and its Beleg stay archived; the correction neutralises the
 * invoice everywhere "open" is asked: the supplier's open total, the Rechnungen OFFEN bucket, the
 * Zuordnung list the suggestions draw from, and the DATEV AP export.
 */
@DisplayName("Incoming invoice cancellation by correction (T9-19)")
class InvoiceCancellationTest {
    private val repo = DocumentRepositoryImpl()
    private val queue = AllocationQueueRepositoryImpl()

    private fun cancellation(): InvoiceCancellationService = GlobalContext.get().get()

    private fun cancel(
        invoiceId: Long,
        reason: String = "Doppelt erfasst",
        by: String? = null,
    ): DocumentDto.InvoiceDto = runBlocking { cancellation().cancel(invoiceId, DocumentDto.CancelInvoiceRequestDto(reason), by) }

    private fun setup() {
        TestData.createShopInfo()
        TestData.createAdmin()
    }

    private fun adminId(): UUID =
        org.example.project.features.employee.domain.models.Employee
            .all()
            .first()
            .id.value

    /** A Beleg with a 119.00 invoice from a registered supplier, VAT split 100 + 19. */
    private fun belegWithInvoice(number: String = "RE-T919-1"): Pair<Long, Long> =
        transaction {
            val documentId =
                repo
                    .create(
                        filename = "$number.pdf",
                        fileType = "INVOICE",
                        filePath = "/nonexistent/$number.pdf",
                        fileSize = 1024L,
                        uploadedAt = currentTimeStamp(),
                        uploadedBy = adminId(),
                        contentHash = "hash-${System.nanoTime()}",
                        mediaType = "application/pdf",
                    ).id.value
            val invoice =
                repo.createInvoice(
                    sourceDocumentId = documentId,
                    invoiceNumber = number,
                    issueDate = currentTimeStamp(),
                    sellerName = "Metro AG",
                    taxExclusiveTotal = BigDecimal("100.00"),
                    taxTotal = BigDecimal("19.00"),
                    taxInclusiveTotal = BigDecimal("119.00"),
                )
            invoice.supplier = SupplierRepositoryImpl().create(name = "Metro AG", vatId = "DE123456789")
            repo.replaceVatBreakdown(
                invoice.id.value,
                listOf(DocumentRepository.VatBreakdownRow("S", BigDecimal("19.00"), BigDecimal("100.00"), BigDecimal("19.00"))),
            )
            documentId to invoice.id.value
        }

    private fun supplierIdOf(invoiceId: Long): Long =
        transaction {
            Invoice
                .findById(invoiceId)!!
                .supplier!!
                .id.value
        }

    @Test
    fun `a correction takes the invoice out of every open list and keeps both records`() {
        testApp(setupData = { setup() }) { _ ->
            val (documentId, invoiceId) = belegWithInvoice()
            val supplierId = supplierIdOf(invoiceId)
            val offen = { runBlocking { GlobalContext.get().get<InvoiceListService>().list(InvoiceListDto.Bucket.OFFEN, 100, 0) } }

            // Open before: the premise the fix has to overturn.
            transaction {
                assertEquals(1L, queue.countOpenInvoices(null, null, null))
                assertEquals(0, BigDecimal("119.00").compareTo(SupplierRepositoryImpl().openTotalsFor(listOf(supplierId))[supplierId]))
            }
            assertEquals(1, offen().items.size)

            val correction = cancel(invoiceId)

            transaction {
                // Zuordnung (the editor's list and the suggestion ladder both read openInvoices).
                assertTrue(queue.openInvoices(null, null, null, 100, 0).isEmpty(), "the cancelled invoice is still offered for Zuordnung")
                assertEquals(0L, queue.countOpenInvoices(null, null, null))
                assertEquals(0, BigDecimal.ZERO.compareTo(queue.sumOpenInvoices(null, null, null)))
                // The negative correction must not surface as an overpayment either.
                assertEquals(0L, queue.countOverpaidInvoices(null))
                assertTrue(SupplierRepositoryImpl().openTotalsFor(listOf(supplierId))[supplierId] == null, "the supplier still owes")
            }
            val afterOffen = offen()
            assertTrue(afterOffen.items.isEmpty(), "Rechnungen OFFEN still lists the cancelled invoice")
            assertEquals("0.00", afterOffen.bucketSums[InvoiceListDto.Bucket.OFFEN.name]?.let { BigDecimal(it).setScale(2).toPlainString() })

            // Nothing was deleted: the Beleg is active, the original survives unchanged, and the
            // correction is a second row on the same Beleg pointing back at it.
            transaction {
                assertTrue(Document.findById(documentId)!!.isActive)
                val original = Invoice.findById(invoiceId)!!
                assertEquals(0, BigDecimal("119.00").compareTo(original.taxInclusiveTotal))
                assertEquals("RE-T919-1", original.invoiceNumber)
                val stored = Invoice.findById(correction.id)!!
                assertEquals(documentId, stored.sourceDocument?.id?.value)
                assertEquals("381", stored.invoiceTypeCode)
                assertEquals("RE-T919-1", stored.precedingInvoiceReference)
                assertEquals(0, BigDecimal("-119.00").compareTo(stored.taxInclusiveTotal))
                assertEquals(0, BigDecimal("-19.00").compareTo(stored.vatBreakdown.single().vatAmount))
                assertEquals(supplierId, stored.supplier?.id?.value)
            }

            // Both halves name the pair, the Beleg names it too, and the reason travels with it.
            assertEquals(invoiceId, correction.storno?.originalInvoiceId)
            assertEquals("Doppelt erfasst", correction.storno?.reason)
            val originalDto = runBlocking { GlobalContext.get().get<DocumentManagementService>().getInvoice(invoiceId) }
            assertEquals(correction.id, originalDto.storno?.correctionInvoiceId)
            val documentDto = runBlocking { GlobalContext.get().get<DocumentManagementService>().getDocumentById(documentId) }
            assertEquals(invoiceId, documentDto.invoiceId, "the Beleg still shows its original invoice")
            assertEquals(correction.id, documentDto.invoiceStorno?.correctionInvoiceId)
        }
    }

    @Test
    fun `a second correction and a correction of the correction are refused`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, invoiceId) = belegWithInvoice()
            val correction = cancel(invoiceId)

            val again = assertFailsWith<PosException> { cancel(invoiceId) }
            assertEquals(PosErrorCode.INVOICE_NOT_CANCELLABLE, again.code)
            val ofCorrection = assertFailsWith<PosException> { cancel(correction.id) }
            assertEquals(PosErrorCode.INVOICE_NOT_CANCELLABLE, ofCorrection.code)
            transaction { assertEquals(2L, Invoice.all().count(), "a refused correction must write nothing") }
        }
    }

    @Test
    fun `an allocated invoice is refused until its Zuordnung is undone`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, invoiceId) = belegWithInvoice()
            transaction {
                val tx = AllocationFixtures.newResolvedBankTransaction(BigDecimal("50.0000"))
                AllocationWriter(AllocationRepositoryImpl()).allocate(
                    NewAllocation(
                        sourceKind = AllocationSourceKind.BANK_TRANSACTION,
                        targetKind = AllocationTargetKind.INVOICE,
                        amount = BigDecimal("50.0000"),
                        matchedBy = AllocationMatchedBy.MANUAL,
                        bankTransactionId = tx.id.value,
                        invoiceId = invoiceId,
                    ),
                )
            }

            val refused = assertFailsWith<PosException> { cancel(invoiceId) }
            assertEquals(PosErrorCode.INVOICE_NOT_CANCELLABLE, refused.code)
        }
    }

    @Test
    fun `a cancelled invoice can be neither allocated nor edited`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, invoiceId) = belegWithInvoice()
            val correction = cancel(invoiceId)
            val txId = transaction { AllocationFixtures.newResolvedBankTransaction(BigDecimal("119.0000")).id.value }

            for (target in listOf(invoiceId, correction.id)) {
                val allocation =
                    assertFailsWith<PosException> {
                        runBlocking {
                            GlobalContext.get().get<AllocationService>().create(
                                AllocationDto.CreateRequestDto(
                                    sourceKind = "BANK_TRANSACTION",
                                    targetKind = "INVOICE",
                                    amount = "119.00",
                                    bankTransactionId = txId,
                                    invoiceId = target,
                                ),
                                createdBy = null,
                            )
                        }
                    }
                assertEquals(PosErrorCode.ALLOCATION_TARGET_NOT_OPEN, allocation.code)

                val edit =
                    assertFailsWith<PosException> {
                        runBlocking {
                            GlobalContext.get().get<DocumentManagementService>().updateInvoiceBooking(
                                target,
                                DocumentDto.UpdateInvoiceBookingRequestDto(bookingText = "umgebucht"),
                            )
                        }
                    }
                assertEquals(PosErrorCode.INVOICE_CANCELLED_READ_ONLY, edit.code)
            }
        }
    }

    @Test
    fun `neither half of the pair reaches the DATEV AP export`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, cancelledId) = belegWithInvoice("RE-T919-1")
            val (_, liveId) = belegWithInvoice("RE-T919-2")
            cancel(cancelledId)

            val today = currentTimeStamp().date.toString()
            val exported =
                transaction {
                    repo
                        .getInvoicesForDatevApExport(today, today, false, DocumentKassenbuchLinkResolver { null })
                        .map { it.id.value }
                }
            assertEquals(listOf(liveId), exported)
        }
    }

    @Test
    fun `a correction writes a CANCEL_INCOMING_INVOICE audit row, a refusal a failed one naming the invoice`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, invoiceId) = belegWithInvoice()
            val by = transaction { adminId().toString() }
            val correction = cancel(invoiceId, reason = "Falscher Lieferant", by = by)
            assertFailsWith<PosException> { cancel(invoiceId, by = by) }

            transaction {
                val rows = TransactionAuditLog.all().filter { it.operation == "CANCEL_INCOMING_INVOICE" }
                val success = rows.single { it.entityName != "Operation failed due to error" }
                assertEquals("INVOICE", success.entityType)
                assertEquals(invoiceId.toString(), success.entityId)
                assertEquals(by, success.employeeId)
                val name = assertNotNull(success.entityName)
                assertTrue(name.contains("Falscher Lieferant") && name.contains("${correction.id}"), name)
                // The refusal names the invoice it refused, not the literal "FAILED" (E2E 2026-09-27).
                val refused = rows.single { it.entityName == "Operation failed due to error" }
                assertEquals(invoiceId.toString(), refused.entityId)
                assertTrue(refused.reason.orEmpty().startsWith("ERROR: "), refused.reason)
            }
        }
    }

    @Test
    fun `POST cancel is routed, permitted for an admin and answers 201 with the correction`() {
        testApp(setupData = { setup() }) { client ->
            val (_, invoiceId) = belegWithInvoice()
            val token = TestAuthHelper.loginAsAdmin(client)

            val blank =
                client.post("/api/invoices/$invoiceId/cancel") {
                    bearerAuth(token)
                    setBody(TextContent("""{"reason":"  "}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.BadRequest, blank.status)

            val response =
                client.post("/api/invoices/$invoiceId/cancel") {
                    bearerAuth(token)
                    setBody(TextContent("""{"reason":"Doppelt erfasst"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("\"originalInvoiceId\":$invoiceId"))
        }
    }

    @Test
    fun `cancelling stamps the original reviewed so it leaves Zu pruefen, and the list marks it storniert`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, invoiceId) = belegWithInvoice()
            val list = { bucket: InvoiceListDto.Bucket ->
                runBlocking { GlobalContext.get().get<InvoiceListService>().list(bucket, 100, 0) }
            }
            assertTrue(list(InvoiceListDto.Bucket.ZU_PRUEFEN).items.any { it.invoiceId == invoiceId }, "premise: unreviewed")

            val correction = cancel(invoiceId)

            transaction { assertNotNull(Invoice.findById(invoiceId)!!.reviewedAt, "the original must be reviewed") }
            val pruefen = list(InvoiceListDto.Bucket.ZU_PRUEFEN)
            assertTrue(pruefen.items.none { it.invoiceId == invoiceId }, "the cancelled original is still in Zu prüfen")
            val fertig = list(InvoiceListDto.Bucket.FERTIG).items
            assertTrue(fertig.single { it.invoiceId == invoiceId }.cancelled, "the original is marked storniert")
            assertTrue(!fertig.single { it.invoiceId == correction.id }.cancelled, "the correction itself is not marked")
        }
    }

    @Test
    fun `cancelling keeps an earlier review stamp`() {
        testApp(setupData = { setup() }) { _ ->
            val (_, invoiceId) = belegWithInvoice()
            val earlier = kotlinx.datetime.LocalDateTime(2026, 1, 2, 3, 4)
            transaction { Invoice.findById(invoiceId)!!.reviewedAt = earlier }

            cancel(invoiceId)

            transaction { assertEquals(earlier, Invoice.findById(invoiceId)!!.reviewedAt) }
        }
    }
}
