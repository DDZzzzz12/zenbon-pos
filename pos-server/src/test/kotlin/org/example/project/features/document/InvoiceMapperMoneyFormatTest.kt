package org.example.project.features.document

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.mapper.toDto
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pins the mapper's money-formatting split for the normalized invoice model.
 *
 * Document-level totals and the booking `costAmount` are read as money and render at scale 2;
 * line-level values render with their stored scale. Formatting a four-decimal BT-146 unit price at
 * scale 2 would silently defeat the `DECIMAL(19,4)` columns the model was widened to.
 *
 * Deliberately split across two transactions: within one transaction Exposed's identity map can
 * serve back the `BigDecimal` just written rather than what the column actually stored.
 */
class InvoiceMapperMoneyFormatTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `line-level values keep four decimals while document totals render at scale 2`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice =
                        repo.createInvoice(
                            sourceDocumentId = null,
                            invoiceNumber = "RE-MONEY-1",
                            issueDate = LocalDateTime(2024, 1, 15, 0, 0, 0),
                            lineNetTotal = BigDecimal("100.1234"),
                            taxInclusiveTotal = BigDecimal("119.00"),
                        )
                    repo.replaceInvoiceLines(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.InvoiceLineRow(
                                lineId = "1",
                                itemName = "Kaffeebohnen",
                                quantity = BigDecimal("3.5"),
                                unitCode = "KGM",
                                netUnitPrice = BigDecimal("12.3456"),
                                lineNetAmount = BigDecimal("43.2096"),
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                            ),
                        ),
                    )
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
                    repo.upsertInvoiceBooking(invoice.id.value, costAmount = BigDecimal("10.5"))
                    invoice.id.value
                }

            transaction {
                val dto = repo.getInvoice(invoiceId)!!.toDto()

                // Document-level totals: scale 2, the amounts a human reads as money.
                assertEquals("100.12", dto.lineNetTotal)
                assertEquals("119.00", dto.taxInclusiveTotal)
                assertEquals("10.50", dto.booking?.costAmount)

                // Line-level values: stored scale preserved, no silent rounding.
                val line = dto.lines.single()
                assertEquals("12.3456", line.netUnitPrice)
                assertEquals("43.2096", line.lineNetAmount)
                assertEquals("3.5000", line.quantity)

                val breakdown = dto.vatBreakdown.single()
                assertEquals("100.1234", breakdown.taxableAmount)
                assertEquals("19.0234", breakdown.vatAmount)

                // Dates cross the wire as ISO-8601 local-date-time, this file's existing convention.
                assertEquals(LocalDateTime(2024, 1, 15, 0, 0, 0), LocalDateTime.parse(dto.issueDate!!))
                assertEquals("2024-01-15T00:00", dto.issueDate)
            }
        }
    }

    /**
     * Pins the mapper's allowance/charge field mapping and the exact-decimal formatter choice for
     * `amount`/`baseAmount` — both are `DECIMAL(19,4)` columns. Routing `amount` through the
     * scale-2 formatter would silently round `12.3456` to `12.35`, defeating the column's precision.
     *
     * Uses distinct, recognisable values in every field of the allowance and the charge so a
     * transposition between the two rows (or between fields) cannot accidentally match.
     */
    @Test
    fun `allowance and charge rows map every field with exact decimal amounts`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val invoiceId =
                transaction {
                    val invoice = repo.createInvoice(sourceDocumentId = null, invoiceNumber = "RE-AC-DTO-1")
                    repo.replaceAllowancesCharges(
                        invoice.id.value,
                        listOf(
                            DocumentRepository.AllowanceChargeRow(
                                isCharge = false,
                                amount = BigDecimal("12.3456"),
                                vatCategoryCode = "S",
                                vatRate = BigDecimal("19.00"),
                                baseAmount = BigDecimal("250.7500"),
                                percentage = BigDecimal("4.50"),
                                reasonCode = "95",
                                reasonText = "Skonto-Abzug",
                            ),
                            DocumentRepository.AllowanceChargeRow(
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
                    )
                    invoice.id.value
                }

            transaction {
                val dto = repo.getInvoice(invoiceId)!!.toDto()
                val allowance = dto.allowancesCharges.single { !it.isCharge }
                val charge = dto.allowancesCharges.single { it.isCharge }

                assertEquals(false, allowance.isCharge)
                assertEquals("12.3456", allowance.amount)
                assertEquals("S", allowance.vatCategoryCode)
                assertEquals("19.00", allowance.vatRate)
                assertEquals("250.7500", allowance.baseAmount)
                assertEquals("4.50", allowance.percentage)
                assertEquals("95", allowance.reasonCode)
                assertEquals("Skonto-Abzug", allowance.reasonText)

                assertEquals(true, charge.isCharge)
                assertEquals("7.8900", charge.amount)
                assertEquals("Z", charge.vatCategoryCode)
                assertEquals("0.00", charge.vatRate)
                assertEquals("300.1200", charge.baseAmount)
                assertEquals("2.63", charge.percentage)
                assertEquals("FC", charge.reasonCode)
                assertEquals("Frachtkosten", charge.reasonText)
            }
        }
    }
}
