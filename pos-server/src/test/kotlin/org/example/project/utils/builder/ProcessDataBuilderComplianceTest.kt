package org.example.project.utils.builder

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.order.mapper.negativeItemQuantity
import org.example.project.features.payment.domain.models.Payment
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
import kotlin.test.assertTrue

/**
 * Anhang I processData conventions (audit C3, H14, C1, TSER-15/16):
 * - Bestellung-V1: line separator `\r`, `<Preis>` = Brutto-EINZELpreis.
 * - Kassenbeleg-V1 Zahlungen: accumulated per (Art, Währung); 0.00 entries omitted.
 * - Amounts: exactly two decimals, further places TRUNCATED (not rounded).
 * - MZ-voucher redemption: negative 0%-bucket line, never a payment entry.
 * - VAT buckets accept current AND historical §12/§24 rates.
 */
@DisplayName("ProcessDataBuilder — Anhang I conventions")
class ProcessDataBuilderComplianceTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // ---------- Bestellung-V1 ----------

    /**
     * @param unit  base unitPrice (UI-facing; must NOT be what Bestellung-V1 signs)
     * @param total line total (finalPrice × quantity; UI-facing)
     * @param charged per-unit CHARGED gross incl. priced options and line discount, un-rounded
     *        (the Anhang I p. 117 „Brutto-Einzelpreis der Bestellzeile" the line must sign);
     *        defaults to [unit] for the no-extras/no-discount case.
     */
    private fun orderItem(
        qty: Int,
        unit: String,
        total: String,
        name: String,
        charged: String = unit,
    ) = OrderItemDto.OrderItemResponseDto(
        orderItemIds = listOf(1L),
        ordersId = setOf(1L),
        product =
            ProductDto.OrderItemProductDto(
                productId = 1L,
                productNumber = "P1",
                productName = name,
                productType = "FOOD",
            ),
        unitPrice = unit,
        finalPrice = total,
        chargedUnitPrice = charged,
        itemStatus = "OPEN",
        vatClass = "A",
        vatRate = "0.19",
        customPrice = false,
        discount = 1.0,
        takeAway = false,
        transferred = false,
        quantity = qty,
    )

    @Test
    fun `order processData signs the charged unit price and CR separators`() {
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items =
                    listOf(
                        orderItem(2, "3.99", "7.98", "Eisbecher \"Himbeere\""),
                        orderItem(1, "2.99", "2.99", "Eiskaffee"),
                    ),
            )
        // No extras/discount: charged == base unit price.
        assertEquals("2;\"Eisbecher \"\"Himbeere\"\"\";3.99\r1;\"Eiskaffee\";2.99", pd)
    }

    /**
     * m20: a CR or LF inside a product name must not reach the signed payload intact.
     *
     * Anhang I p. 116 defines the Bestellung payload as CSV with „\r" as the Zeilentrennzeichen,
     * and specifies exactly ONE escape: „Jedes Anführungszeichen in der Beschreibung selbst, ist
     * durch zwei Anführungszeichen zu ersetzen." It says nothing about embedded CR — so unlike
     * RFC 4180 CSV, it never establishes that encapsulation protects the row separator. A verifier
     * that implements the spec's own wording literally (split on \r, then on ;) reads
     * `"Cola\rPepsi"` as a phantom second Bestellzeile with a missing price column.
     *
     * Nothing stops such a name existing: `ProductDto.validate()` only checks `isNotBlank()` and a
     * 255-char limit, and names arrive as JSON strings and via CSV import, both of which carry
     * control characters happily.
     *
     * This one is worse than the CSV-side equivalent it mirrors. `lines.csv` can be regenerated
     * with a fixed exporter; a signed payload cannot — it is in the WORM permanently, ambiguous
     * forever.
     *
     * The substitution deliberately matches `DsfinvkCsvUtils.escapeString` (CONT-7) character for
     * character. The same product name is exported as ARTIKELTEXT in lines.csv, and a
     * TAR-vs-DSFinV-K reconciliation compares them: diverging on whitespace would trade a parsing
     * bug for a mismatch bug.
     */
    @Test
    fun `a newline in a product name cannot forge a Bestellzeile`() {
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items =
                    listOf(
                        orderItem(1, "3.00", "3.00", "Cola\rPepsi"),
                        orderItem(1, "4.00", "4.00", "Wasser\r\nStill"),
                        orderItem(1, "5.00", "5.00", "Saft\nApfel"),
                    ),
            )

        assertEquals(
            "1;\"Cola Pepsi\";3.00\r1;\"Wasser Still\";4.00\r1;\"Saft Apfel\";5.00",
            pd,
            "each item must contribute exactly one Bestellzeile; a CR/LF in the name must be " +
                "neutralised BEFORE signing, because the WORM cannot be corrected afterwards",
        )
        assertEquals(
            2,
            pd.count { it == '\r' },
            "three items = two row separators. Any more means a product name forged a row.",
        )
    }

    @Test
    fun `order processData signs the charged price incl priced extras, not the bare base`() {
        // Pizza base 12.00 + priced extra +2.00 → charged per-unit 14.00 (TSER-6).
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items = listOf(orderItem(1, "12.00", "14.00", "Pizza", charged = "14.00")),
            )
        assertEquals("1;\"Pizza\";14.00", pd)
    }

    @Test
    fun `order processData signs the charged price after line discount, not the bare base`() {
        // Pizza base 12.00, 0.95 line discount → charged per-unit 11.40 (TSER-6).
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items = listOf(orderItem(1, "12.00", "11.40", "Pizza", charged = "11.40")),
            )
        assertEquals("1;\"Pizza\";11.40", pd)
    }

    @Test
    fun `order processData truncates the charged price to two decimals, never rounds`() {
        // Charged per-unit 12.005 → TRUNCATE to 12.00 (Anhang I), not HALF_UP 12.01.
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items = listOf(orderItem(1, "12.00", "12.00", "Pizza", charged = "12.005")),
            )
        assertEquals("1;\"Pizza\";12.00", pd)
    }

    @Test
    fun `cancellation negates Menge only, price stays the positive Brutto-Einzelpreis`() {
        // Cancelling 2×12.00: reversal sign lives in MENGE (−2); Preis stays +12.00 (TSER-7).
        // The old .negativeItemPrice() double-negation signed -2;"X";-12.00 (= +24.00 total),
        // misrepresenting the reversal as a positive order.
        val cancelled =
            listOf(orderItem(2, "12.00", "24.00", "Pizza", charged = "12.00")).negativeItemQuantity()
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items = cancelled,
            )
        assertEquals("-2;\"Pizza\";12.00", pd)
    }

    @Test
    fun `deposit-return cancellation keeps the single negative price, never double-signs`() {
        // Original deposit-return line: Menge +1 / chargedUnitPrice −0.25. Its cancellation
        // negates the Menge → −1, price stays −0.25 → "-1;"Leergut";-0.25" (valid single sign).
        // The old .negativeItemPrice() string-prefixed to "--0.25" → malformed/unparseable (TSER-7).
        val cancelled =
            listOf(orderItem(1, "-0.25", "-0.25", "Leergut", charged = "-0.25")).negativeItemQuantity()
        val pd =
            ProcessDataBuilder.build(
                processType = ProcessType.BESTELLUNG_V1,
                items = cancelled,
            )
        assertEquals("-1;\"Leergut\";-0.25", pd)
        assertTrue(!pd.contains("--"), "must not contain a double-negation token, got $pd")
    }

    // ---------- Kassenbeleg-V1 ----------

    private class Seed(
        val bill: Bill,
        val cash: (String) -> Payment,
        val unbar: (String) -> Payment,
        val voucher: (String) -> Payment,
        val vat: (String, String) -> BillVatDetail,
    )

    private fun <T> seeded(block: Seed.() -> T): T =
        transaction {
            val admin = TestData.createAdmin()
            TestData.createShopInfo()
            val cashType = TestData.createPaymentType("Cash", isCash = true)
            val cardType = TestData.createPaymentType("Card", isCash = false)
            val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
            val vats = TestData.createDefaultVatRates()
            val tws = TestData.createTableWithSession("PDB1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "PDB-1"
                    this.billAmount = BigDecimal("100.00")
                    this.paidAmount = BigDecimal("100.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }

            fun pay(
                type: org.example.project.features.payment.domain.models.PaymentType,
                amount: String,
            ) = Payment.new {
                this.bill = bill
                this.paymentType = type
                this.amount = BigDecimal(amount)
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            fun vatDetail(
                rate: String,
                gross: String,
            ): BillVatDetail {
                val vat =
                    vats.firstOrNull { it.vatRate.compareTo(BigDecimal(rate)) == 0 }
                        ?: run {
                            val id = "H" + rate.replace("0.", "").take(4)
                            org.example.project.features.payment.domain.models.Vat
                                .findById(id)
                                ?: org.example.project.features.payment.domain.models.Vat.new(id) {
                                    this.vatClass = "Hist-$rate"
                                    this.vatRate = BigDecimal(rate)
                                }
                        }
                val grossBd = BigDecimal(gross)
                val net = grossBd.divide(BigDecimal.ONE + BigDecimal(rate), 4, java.math.RoundingMode.HALF_UP)
                return BillVatDetail.new {
                    this.bill = bill
                    this.vat = vat
                    this.totalAmount = grossBd
                    this.netAmount = net
                    this.vatAmount = grossBd - net
                }
            }
            Seed(
                bill = bill,
                cash = { pay(cashType, it) },
                unbar = { pay(cardType, it) },
                voucher = { pay(voucherType, it) },
                vat = { rate, gross -> vatDetail(rate, gross) },
            ).block()
        }

    @Test
    fun `same-kind payments accumulate and zero entries are omitted`() {
        val pd =
            seeded {
                // Zahlartwechsel Bar → Bar+Unbar: −100 Bar, +60 Bar, +40 Unbar
                val payments = listOf(cash("-100.00"), cash("60.00"), unbar("40.00"))
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = emptyList(),
                    payments = payments,
                )
            }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_0.00^-40.00:Bar_40.00:Unbar", pd)
    }

    @Test
    fun `card-to-card change omits the accumulated zero unbar entry`() {
        val pd =
            seeded {
                val payments = listOf(unbar("-100.00"), unbar("100.00"))
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = emptyList(),
                    payments = payments,
                )
            }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_0.00^", pd)
    }

    @Test
    fun `amounts truncate instead of rounding half-up`() {
        val pd =
            seeded {
                vat("0.19", "3.4950")
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = bill.vatDetails.toList(),
                    payments = listOf(cash("3.49")),
                )
            }
        assertTrue(pd.startsWith("Beleg^3.49_"), "3.4950 must truncate to 3.49, got $pd")
    }

    @Test
    fun `voucher redemption signs as negative 0-percent bucket, not as Unbar`() {
        val pd =
            seeded {
                vat("0.19", "60.00")
                vat("0.07", "60.00")
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = bill.vatDetails.toList(),
                    payments = listOf(cash("20.00"), voucher("100.00")),
                )
            }
        assertEquals("Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar", pd)
    }

    @Test
    fun `historical and average rates land in their Anhang I buckets`() {
        val pd =
            seeded {
                vat("0.16", "10.00") // historical general → bucket 1
                vat("0.05", "20.00") // historical reduced → bucket 2
                vat("0.084", "30.00") // §24(1) Nr.3 → bucket 3
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = bill.vatDetails.toList(),
                    payments = listOf(cash("60.00")),
                )
            }
        assertEquals("Beleg^10.00_20.00_30.00_0.00_0.00^60.00:Bar", pd)
    }

    /**
     * Anhang B p. 46: an `AVBelegabbruch` covers „alle Vorgänge, die nach Transaktionsbeginn
     * abgebrochen werden", and „Eine tatsächliche Bezahlung darf im Zusammenhang mit diesem
     * Vorgangstyp nicht erfolgen." Anhang I p. 120 pins the payload verbatim.
     *
     * The empty `<Zahlungen>` section (a bare trailing `^`) is the load-bearing part: any tender
     * here would assert a payment on an aborted Vorgang. The method has no production caller — it
     * is the sanctioned entry point for a future feature that needs `startTransaction` — so this
     * test is the only thing keeping the payload honest.
     */
    @Test
    fun `buildBelegabbruch emits the Anhang I p120 payload with an empty payment section`() {
        assertEquals("AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^", ProcessDataBuilder.buildBelegabbruch())
    }
}
