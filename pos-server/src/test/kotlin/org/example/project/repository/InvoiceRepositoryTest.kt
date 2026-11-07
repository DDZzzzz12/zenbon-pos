package org.example.project.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.document.ClearableBookingField
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Repository test for the invoice side of [DocumentRepositoryImpl].
 *
 * The invoice aggregate deliberately splits supplier-asserted content ([InvoiceTable] and its
 * children) from our own bookkeeping decisions ([InvoiceBookingTable]). These tests pin that
 * split and the "replace, never merge" ownership of the child rows.
 */
class InvoiceRepositoryTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `an invoice can exist with no source document`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-1")
                assertNull(invoice.sourceDocument)
                assertEquals("RE-1", invoice.invoiceNumber)
            }
        }
    }

    // The whole point of the booking split: writing booking fields must not be able to touch
    // supplier-asserted content.
    @Test
    fun `writing booking fields leaves supplier content untouched`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-2", sellerName = "ACME")
                repo.upsertInvoiceBooking(invoice.id.value, accountNo = "3400", bookingText = "Wareneinkauf")
                assertEquals("ACME", repo.getInvoice(invoice.id.value)?.sellerName)
                assertEquals("RE-2", repo.getInvoice(invoice.id.value)?.invoiceNumber)
                assertEquals("3400", repo.getInvoice(invoice.id.value)?.booking?.accountNo)
            }
        }
    }

    /**
     * Null means "leave unchanged" on update: a partial booking edit must never wipe a field the
     * caller did not mention. On create a null argument simply leaves the column at its default.
     */
    @Test
    fun `a partial booking update leaves the unmentioned fields intact`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-4")
                repo.upsertInvoiceBooking(
                    invoice.id.value,
                    accountNo = "3400",
                    bookingText = "Wareneinkauf",
                    paymentDunningBlock = true,
                )
                repo.upsertInvoiceBooking(invoice.id.value, bpAccountNo = "70001")

                val booking = repo.getInvoice(invoice.id.value)!!.booking!!
                assertEquals("3400", booking.accountNo)
                assertEquals("Wareneinkauf", booking.bookingText)
                assertEquals("70001", booking.bpAccountNo)
                assertEquals(true, booking.paymentDunningBlock)
            }
        }
    }

    /**
     * The counterpart to null-means-unchanged: clearing has to be said out loud.
     *
     * `paidAt` is the sharp case the DTO KDoc names — a payment reversal cannot un-mark an invoice
     * as paid through a null, so without this mechanism it could not be un-marked at all.
     */
    @Test
    fun `a named field is cleared while the unnamed ones survive`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-CLEAR-1")
                    repo.upsertInvoiceBooking(
                        invoice.id.value,
                        accountNo = "3400",
                        bpAccountNo = "70001",
                        bookingText = "Wareneinkauf",
                        costAmount = BigDecimal("10.50"),
                        paidAt = LocalDateTime(2026, 2, 1, 9, 15, 30),
                    )
                    invoice.id.value
                }

            transaction {
                repo.upsertInvoiceBooking(
                    invoiceId,
                    clearFields = setOf(ClearableBookingField.PAID_AT, ClearableBookingField.COST_AMOUNT),
                )
            }

            transaction {
                val booking = assertNotNull(repo.getInvoice(invoiceId)?.booking)
                assertNull(booking.paidAt, "PAID_AT was named for clearing")
                assertNull(booking.costAmount, "COST_AMOUNT was named for clearing")
                assertEquals("3400", booking.accountNo, "an unnamed field must survive")
                assertEquals("70001", booking.bpAccountNo, "an unnamed field must survive")
                assertEquals("Wareneinkauf", booking.bookingText, "an unnamed field must survive")
            }
        }
    }

    /**
     * The regression anchor for the contract this mechanism could most easily weaken: an empty
     * clear-set must behave exactly like the old two-argument call. If this ever goes red, clearing
     * has leaked into the default path.
     */
    @Test
    fun `an empty clear set still means leave everything unchanged`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-CLEAR-2")
                    repo.upsertInvoiceBooking(
                        invoice.id.value,
                        accountNo = "3400",
                        bookingText = "Wareneinkauf",
                        paymentDunningBlock = true,
                    )
                    invoice.id.value
                }

            transaction {
                repo.upsertInvoiceBooking(invoiceId, bpAccountNo = "70001", clearFields = emptySet())
            }

            transaction {
                val booking = assertNotNull(repo.getInvoice(invoiceId)?.booking)
                assertEquals("3400", booking.accountNo)
                assertEquals("Wareneinkauf", booking.bookingText)
                assertEquals("70001", booking.bpAccountNo)
                assertEquals(true, booking.paymentDunningBlock)
            }
        }
    }

    /**
     * A field both given a value and named for clearing is ambiguous. The repository resolves it
     * deterministically — clears are applied last, so clearing wins — and the service layer rejects
     * the request outright so an operator never relies on this. Pinned here because "deterministic"
     * is worth nothing if it is not asserted.
     */
    @Test
    fun `clearing wins when a field is both set and named for clearing`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-CLEAR-3").id.value
                }

            transaction {
                repo.upsertInvoiceBooking(
                    invoiceId,
                    accountNo = "3400",
                    clearFields = setOf(ClearableBookingField.ACCOUNT_NO),
                )
            }

            transaction {
                assertNull(repo.getInvoice(invoiceId)?.booking?.accountNo)
            }
        }
    }

    @Test
    fun `an omitted dunning block defaults to false on create`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-5")
                val booking = repo.upsertInvoiceBooking(invoice.id.value, accountNo = "3400")
                assertFalse(booking.paymentDunningBlock)
            }
        }
    }

    /**
     * Deliberately split across two transactions. Within one transaction Exposed's identity map can
     * serve the entity straight back from memory, which would assert the `BigDecimal` we just wrote
     * rather than what `DECIMAL(19,4)` actually stored. Reopening forces a real read.
     */
    @Test
    fun `vat breakdown rows survive a round trip with four-decimal amounts`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-3")
                    repo.replaceVatBreakdown(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.VatBreakdownRow(
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                taxableAmount = BigDecimal("100.1234"),
                                vatAmount = BigDecimal("19.0234"),
                            ),
                        ),
                    )
                    invoice.id.value
                }

            transaction {
                val rows = repo.getInvoice(invoiceId)!!.vatBreakdown.toList()
                assertEquals(1, rows.size)
                assertEquals(BigDecimal("100.1234"), rows[0].taxableAmount)
                assertEquals(BigDecimal("19.0234"), rows[0].vatAmount)
            }
        }
    }

    @Test
    fun `replaceVatBreakdown replaces rather than merges`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-6")
                repo.replaceVatBreakdown(
                    invoice.id.value,
                    listOf(
                        DocumentRepository.VatBreakdownRow("S", BigDecimal("19.00"), BigDecimal("100.00"), BigDecimal("19.00")),
                        DocumentRepository.VatBreakdownRow("S", BigDecimal("7.00"), BigDecimal("50.00"), BigDecimal("3.50")),
                    ),
                )
                repo.replaceVatBreakdown(
                    invoice.id.value,
                    listOf(
                        DocumentRepository.VatBreakdownRow("Z", BigDecimal("0.00"), BigDecimal("10.00"), BigDecimal("0.00")),
                    ),
                )

                val rows = repo.getInvoice(invoice.id.value)!!.vatBreakdown.toList()
                assertEquals(1, rows.size)
                assertEquals("Z", rows[0].vatCategoryCode)
            }
        }
    }

    @Test
    fun `replaceInvoiceLines replaces the whole set`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-7")
                repo.replaceInvoiceLines(
                    invoice.id.value,
                    listOf(
                        DocumentRepository.InvoiceLineRow(
                            lineId = "1",
                            itemName = "Kaffee",
                            quantity = BigDecimal("2.0000"),
                            unitCode = "C62",
                            netUnitPrice = BigDecimal("1.2345"),
                            lineNetAmount = BigDecimal("2.4690"),
                            vatCategoryCode = "S",
                            vatRate = BigDecimal("19.00"),
                        ),
                        DocumentRepository.InvoiceLineRow(
                            lineId = "2",
                            itemName = "Tee",
                            quantity = BigDecimal("1.0000"),
                            unitCode = "C62",
                            netUnitPrice = BigDecimal("2.0000"),
                            lineNetAmount = BigDecimal("2.0000"),
                            vatCategoryCode = "S",
                            vatRate = BigDecimal("19.00"),
                        ),
                    ),
                )
                // Same lineId "1" again — a merge would collide on the (invoiceId, lineId) unique index.
                repo.replaceInvoiceLines(
                    invoice.id.value,
                    listOf(
                        DocumentRepository.InvoiceLineRow(
                            lineId = "1",
                            itemName = "Kakao",
                            quantity = BigDecimal("1.0000"),
                            unitCode = "C62",
                            netUnitPrice = BigDecimal("3.3333"),
                            lineNetAmount = BigDecimal("3.3333"),
                            vatCategoryCode = "S",
                            vatRate = BigDecimal("19.00"),
                        ),
                    ),
                )

                val lines = repo.getInvoice(invoice.id.value)!!.lines.toList()
                assertEquals(1, lines.size)
                assertEquals("Kakao", lines[0].itemName)
                assertEquals(BigDecimal("3.3333"), lines[0].netUnitPrice)
            }
        }
    }

    /**
     * Split across two transactions for the same reason as the VAT-breakdown round trip: within one
     * transaction Exposed's identity map can serve the entity from memory rather than the DB.
     */
    @Test
    fun `allowance and charge rows survive a round trip`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-AC-1")
                    repo.replaceAllowancesCharges(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.AllowanceChargeRow(
                                isCharge = false,
                                amount = BigDecimal("5.0000"),
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                baseAmount = BigDecimal("100.0000"),
                                percentage = BigDecimal("5.00"),
                                reasonCode = "95",
                                reasonText = "Skonto",
                            ),
                            DocumentRepository.AllowanceChargeRow(
                                isCharge = true,
                                amount = BigDecimal("9.9900"),
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                reasonText = "Versandkosten",
                            ),
                        ),
                    )
                    invoice.id.value
                }

            transaction {
                val rows = repo.getInvoice(invoiceId)!!.allowancesCharges.sortedBy { it.id.value }
                assertEquals(2, rows.size)

                assertEquals(false, rows[0].isCharge)
                assertEquals(BigDecimal("5.0000"), rows[0].amount)
                assertEquals(BigDecimal("100.0000"), rows[0].baseAmount)
                assertEquals(BigDecimal("5.00"), rows[0].percentage)
                assertEquals("95", rows[0].reasonCode)
                assertEquals("Skonto", rows[0].reasonText)
                assertEquals("S", rows[0].vatCategoryCode)
                assertEquals(BigDecimal("19.00"), rows[0].vatRate)

                assertEquals(true, rows[1].isCharge)
                assertEquals(BigDecimal("9.9900"), rows[1].amount)
                assertEquals("Versandkosten", rows[1].reasonText)
                assertNull(rows[1].baseAmount)
                assertNull(rows[1].percentage)
                assertNull(rows[1].reasonCode)
            }
        }
    }

    @Test
    fun `replaceAllowancesCharges replaces the whole set`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-AC-2")
                    repo.replaceAllowancesCharges(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.AllowanceChargeRow(
                                isCharge = false,
                                amount = BigDecimal("5.0000"),
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                reasonText = "Skonto",
                            ),
                            DocumentRepository.AllowanceChargeRow(
                                isCharge = true,
                                amount = BigDecimal("9.9900"),
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                reasonText = "Versandkosten",
                            ),
                        ),
                    )
                    repo.replaceAllowancesCharges(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.AllowanceChargeRow(
                                isCharge = true,
                                amount = BigDecimal("1.5000"),
                                vatCategoryCode = "Z",
                                vatRate = BigDecimal("0.00"),
                                reasonText = "Verpackung",
                            ),
                        ),
                    )
                    invoice.id.value
                }

            transaction {
                val rows = repo.getInvoice(invoiceId)!!.allowancesCharges.toList()
                assertEquals(1, rows.size)
                assertEquals(true, rows[0].isCharge)
                assertEquals(BigDecimal("1.5000"), rows[0].amount)
                assertEquals("Verpackung", rows[0].reasonText)
                assertEquals("Z", rows[0].vatCategoryCode)
            }
        }
    }

    @Test
    fun `getInvoiceBySourceDocument finds the invoice for its document`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            transaction {
                val admin = Employee.all().first()
                val document =
                    repo.create(
                        filename = "re-8.pdf",
                        fileType = "INVOICE",
                        filePath = "/tmp/re-8.pdf",
                        fileSize = 1024L,
                        uploadedAt = currentTimeStamp(),
                        uploadedBy = admin.id.value,
                        contentHash = "hash-re-8",
                    )
                val invoice = repo.createInvoice(sourceDocumentId = document.id.value, invoiceNumber = "RE-8")

                assertEquals(invoice.id.value, repo.getInvoiceBySourceDocument(document.id.value)?.id?.value)
                assertNull(repo.getInvoiceBySourceDocument(999_999L))
            }
        }
    }
}
