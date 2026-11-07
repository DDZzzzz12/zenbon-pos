package org.example.project.features.document

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.core.error.PosErrorCode
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Service-level cover for the only write path into invoice booking data — what the DATEV
 * Eingangsrechnungen export reads.
 *
 * Exercises the pieces the repository tests cannot: the `costAmount` string parse, the `paidAt`
 * date parse, the booking's visibility on the returned [DocumentDto.InvoiceDto], and the
 * null-means-unchanged contract as seen from the service boundary.
 */
@DisplayName("DocumentManagementService.updateInvoiceBooking")
class InvoiceBookingServiceTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `a booking write is visible on the returned invoice and survives a partial second update`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            // PdfGenerationServiceImpl is built by a reflective singleOf and gained a constructor
            // parameter; resolving it here proves the Koin graph still satisfies it.
            assertNotNull(GlobalContext.get().get<PdfGenerationService>())

            val service = GlobalContext.get().get<DocumentManagementService>()

            val invoiceId =
                transaction {
                    repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-BOOK-1").id.value
                }

            val afterFirst =
                runBlocking {
                    service.updateInvoiceBooking(
                        invoiceId,
                        DocumentDto.UpdateInvoiceBookingRequestDto(
                            accountNo = "3400",
                            bookingText = "Wareneinkauf",
                            costAmount = "10.5",
                            paymentDunningBlock = true,
                            paidAt = "2024-02-01T09:15:30",
                        ),
                    )
                }

            // The booking must be visible on the invoice the write returns — not only on a re-read.
            val firstBooking = assertNotNull(afterFirst.booking)
            assertEquals("3400", firstBooking.accountNo)
            assertEquals("Wareneinkauf", firstBooking.bookingText)
            assertEquals("10.50", firstBooking.costAmount)
            assertEquals(true, firstBooking.paymentDunningBlock)
            assertEquals("2024-02-01T09:15:30", firstBooking.paidAt)
            assertEquals("RE-BOOK-1", afterFirst.invoiceNumber)

            // Null means "leave unchanged": a partial edit must not wipe what it did not mention.
            val afterSecond =
                runBlocking {
                    service.updateInvoiceBooking(
                        invoiceId,
                        DocumentDto.UpdateInvoiceBookingRequestDto(bpAccountNo = "70001"),
                    )
                }
            val secondBooking = assertNotNull(afterSecond.booking)
            assertEquals("70001", secondBooking.bpAccountNo)
            assertEquals("3400", secondBooking.accountNo)
            assertEquals("Wareneinkauf", secondBooking.bookingText)
            assertEquals("10.50", secondBooking.costAmount)
            assertEquals(true, secondBooking.paymentDunningBlock)
            assertEquals("2024-02-01T09:15:30", secondBooking.paidAt)

            // Read back in its own transaction so the assertion sees the stored columns, not the
            // entity the write left in Exposed's identity map.
            transaction {
                val stored = assertNotNull(repo.getInvoice(invoiceId)?.booking)
                assertEquals(0, BigDecimal("10.5").compareTo(stored.costAmount))
                assertEquals(LocalDateTime(2024, 2, 1, 9, 15, 30), stored.paidAt)
                assertEquals("70001", stored.bpAccountNo)
                assertEquals("3400", stored.accountNo)
            }

            // And the read path agrees with what the write returned.
            val fetched = runBlocking { service.getInvoice(invoiceId) }
            assertEquals(secondBooking, fetched.booking)
        }
    }

    @Test
    fun `a named clear field is nulled through the service`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val service = GlobalContext.get().get<DocumentManagementService>()

            val invoiceId =
                transaction {
                    repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-BOOK-CLEAR").id.value
                }

            runBlocking {
                service.updateInvoiceBooking(
                    invoiceId,
                    DocumentDto.UpdateInvoiceBookingRequestDto(
                        accountNo = "3400",
                        bookingText = "Wareneinkauf",
                        paidAt = "2026-02-01T09:15:30",
                    ),
                )
            }

            // A payment reversal: paidAt must be un-settable, which a null cannot express.
            val after =
                runBlocking {
                    service.updateInvoiceBooking(
                        invoiceId,
                        DocumentDto.UpdateInvoiceBookingRequestDto(
                            clearFields = listOf(ClearableBookingField.PAID_AT),
                        ),
                    )
                }

            val booking = assertNotNull(after.booking)
            assertNull(booking.paidAt, "PAID_AT was named for clearing")
            assertEquals("3400", booking.accountNo, "an unnamed field must survive")
            assertEquals("Wareneinkauf", booking.bookingText, "an unnamed field must survive")
        }
    }

    @Test
    fun `setting and clearing the same field is rejected rather than resolved silently`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val service = GlobalContext.get().get<DocumentManagementService>()

            val invoiceId =
                transaction {
                    repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-BOOK-CONFLICT").id.value
                }

            val thrown =
                assertFailsWith<PosException> {
                    runBlocking {
                        service.updateInvoiceBooking(
                            invoiceId,
                            DocumentDto.UpdateInvoiceBookingRequestDto(
                                accountNo = "3400",
                                clearFields = listOf(ClearableBookingField.ACCOUNT_NO),
                            ),
                        )
                    }
                }
            assertEquals(PosErrorCode.VALIDATION_FAILED, thrown.code)
        }
    }
}
