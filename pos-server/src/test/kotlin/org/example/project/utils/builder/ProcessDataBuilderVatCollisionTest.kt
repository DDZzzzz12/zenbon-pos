package org.example.project.utils.builder

import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pins the ProcessDataBuilder brutto-accumulation fix (H3c follow-up).
 *
 * `buildBillProcessData` groups `BillVatDetail` rows into the 5 signed brutto slots by VAT rate.
 * It previously ASSIGNED the slot (`vatMap[key] = formatDecimal(it.totalAmount)`), so two rows
 * sharing a rate — e.g. a 0%-rated ITEM group AND a non-taxable employee tip, both on the 0% slot —
 * collided last-write-wins, silently dropping one row from the SIGNED brutto and unbalancing the
 * Kassenbeleg (Σ Umsatz ≠ Σ Zahlung). The fix accumulates rows into their slot.
 */
@DisplayName("ProcessDataBuilder — brutto slot accumulation (H3c)")
class ProcessDataBuilderVatCollisionTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** Sums the 5 brutto slots and the payment tenders of a Kassenbeleg-V1 processData string. */
    private data class Parsed(
        val vatSlots: List<BigDecimal>,
        val paymentTotal: BigDecimal,
    ) {
        val vatTotal: BigDecimal get() = vatSlots.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    }

    private fun parse(processData: String): Parsed {
        val parts = processData.split("^")
        val vatSlots = parts[1].split("_").map { BigDecimal(it) }
        val paymentTotal =
            parts[2]
                .split("_")
                .fold(BigDecimal.ZERO) { acc, t -> acc + BigDecimal(t.substringBefore(":")) }
        return Parsed(vatSlots, paymentTotal)
    }

    @Test
    fun `two 0pct rows (item + tip) accumulate into the 0pct slot instead of colliding`() {
        val processData =
            transaction {
                val admin = TestData.createAdmin()
                val tws = TestData.createTableWithSession("PDBVC1")
                val session = TableSession.findById(tws.session.id)!!
                val now = currentTimeStamp()

                // A 0% ("Nicht steuerbar") VAT rate shared by BOTH a 0%-rated item and the employee tip.
                val vat0 =
                    Vat.findById("C") ?: Vat.new("C") {
                        this.vatClass = "Zero"
                        this.vatRate = BigDecimal("0.0000")
                    }
                val cash = TestData.createPaymentType(name = "PDBVC-Cash", isCash = true)

                // Item 10.00 @0% + a 3.00 non-taxable employee tip -> paid 13.00. BOTH BillVatDetail
                // rows carry the 0% rate, so they must SUM in the 0% slot (=13.00), not overwrite.
                val bill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "PDBVC-A"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("13.00")
                        this.tips = BigDecimal("3.00")
                        this.startTime = now
                        this.endTime = now
                    }
                // 0%-rated item group row.
                BillVatDetail.new {
                    this.bill = bill
                    this.vat = vat0
                    this.totalAmount = BigDecimal("10.00")
                    this.netAmount = BigDecimal("10.00")
                    this.vatAmount = BigDecimal.ZERO
                }
                // Non-taxable employee-tip row (also 0%).
                BillVatDetail.new {
                    this.bill = bill
                    this.vat = vat0
                    this.totalAmount = BigDecimal("3.00")
                    this.netAmount = BigDecimal("3.00")
                    this.vatAmount = BigDecimal.ZERO
                }
                Payment.new {
                    this.bill = bill
                    this.paymentType = cash
                    this.amount = BigDecimal("13.00")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }

                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = bill.vatDetails.toList(),
                    payments = bill.payments.toList(),
                )
            }

        val parsed = parse(processData)
        // The 0% slot (5th) must hold the SUM of both rows, not just the last one.
        assertEquals(
            0,
            parsed.vatSlots[4].compareTo(BigDecimal("13.00")),
            "0% slot must accumulate 10.00 item + 3.00 tip = 13.00; slots=${parsed.vatSlots}",
        )
        // And the signed Beleg must balance.
        assertEquals(
            0,
            parsed.vatTotal.compareTo(parsed.paymentTotal),
            "Σ Umsatz (${parsed.vatTotal}) must equal Σ Zahlung (${parsed.paymentTotal})",
        )
        assertEquals(
            0,
            parsed.paymentTotal.compareTo(BigDecimal("13.00")),
            "sanity: payment total is 13.00; got ${parsed.paymentTotal}",
        )
    }
}
