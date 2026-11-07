package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.NonTaxableTipCarrier
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E-2026-09-15 N1: an EMPLOYEE tip (TrinkgeldAN) is **non-taxable and not turnover**, so it must
 * not be modelled as a 0 % [org.example.project.features.bill.domain.models.BillVatDetail] row.
 * Such a row balanced the signed Beleg, but then leaked downstream into the Z-Bon VAT groups, a
 * "TE0%" Kassenbuch line, receipts and revenue figures.
 *
 * The tip now reaches the signature through `ProcessDataBuilder`'s explicit `nonTaxableExtra`
 * instead, which lands in the same 5th ("nicht steuerbar") brutto slot — so **the signed bytes do
 * not change at all**. The pair of assertions in
 * `employee tip - signed ProcessData is unchanged - tip still in the 0pct slot` is what pins that
 * claim; [BillSigningBalanceTest] pins the same payload from the other side.
 *
 * Each interaction case below is a path where the new invariant
 * (`Σ vatDetails == paidAmount − non-taxable tip`) could break.
 */
@DisplayName("Employee tip is not a VAT row (E2E-2026-09-15 N1)")
class BillTipNotAVatRowTest {
    private val deviceId = "tip-not-a-vat-row-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private data class ParsedProcessData(
        val vatSlots: List<BigDecimal>,
        val paymentTotal: BigDecimal,
        val raw: String,
    ) {
        val vatTotal: BigDecimal get() = vatSlots.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    }

    /** Parses `BonType^b19_b7_b105_b55_b0^amount:Bar_...` into its brutto slots and payment total. */
    private fun parse(processData: String): ParsedProcessData {
        val parts = processData.split("^")
        val vatSlots = parts[1].split("_").map { BigDecimal(it) }
        val paymentTotal =
            parts[2]
                .split("_")
                .filter { it.isNotEmpty() }
                .fold(BigDecimal.ZERO) { acc, tender -> acc + BigDecimal(tender.substringBefore(":")) }
        return ParsedProcessData(vatSlots, paymentTotal, processData)
    }

    /**
     * Rebuilds the signed Kassenbeleg-V1 payload for [billId] and parses it — through `buildForBill`
     * for a forward sale (BillServiceImpl.kt:378), and through the storno shape (reversed VAT rows +
     * the refund payments + [NonTaxableTipCarrier]) for a reverse bill (BillServiceImpl.kt:669).
     *
     * The forward leg calls production's own entry point, so it IS a witness to what gets signed.
     * The storno leg only **mirrors** the call site — it reassembles the same arguments rather than
     * invoking it — so it is NOT a gate on that call site: deleting the storno change would leave
     * these assertions green. A real gate would have to read the `processData` persisted to the
     * signing queue, which `deleteBill` writes only when `decision.enqueue` is true — i.e. only when
     * a sign attempt FAILS under `TseFailureBehavior.QUEUE` (see TseSigningOutcomeResolver), which
     * does not occur under `testApp`'s simulated TSE.
     */
    private fun parseProcessData(billId: Long): ParsedProcessData =
        transaction {
            val bill = Bill.findById(billId)!!
            val stornoedBill = bill.deletedBill
            val processData =
                if (stornoedBill == null) {
                    ProcessDataBuilder.buildForBill(bill)
                } else {
                    ProcessDataBuilder.build(
                        processType = ProcessType.KASSENBELEG_V1,
                        bonType = if (bill.isTraining) BonType.AV_TRAINING else BonType.BELEG,
                        vatDetail = bill.vatDetails.toList(),
                        payments = bill.refundPayments.toList(),
                        nonTaxableExtra = NonTaxableTipCarrier.outsideVatDetails(bill),
                    )
                }
            parse(processData)
        }

    private fun createTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillTipNotAVatRowTest.deviceId
                this.clientId = "TERM-TIP-NOT-VAT"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    /** The 0 %/key-5 "Nicht steuerbar" rate the pre-fix tip row used — seeded so its absence is a real finding. */
    private fun ensureZeroRateVat(): Vat =
        transaction {
            Vat.findById("C") ?: Vat.new("C") {
                this.vatClass = "Zero"
                this.vatRate = BigDecimal("0.0000")
            }
        }

    /** `BillRepositoryImpl` derives TrinkgeldAN for every non-ADMIN closer and TrinkgeldAG for an ADMIN. */
    private fun closerFor(
        recipient: TipRecipient,
        suffix: String,
    ): Employee =
        if (recipient == TipRecipient.OWNER) {
            TestData.createAdmin(employeeNumber = "TNV_A_$suffix", username = "tnv_a_$suffix")
        } else {
            TestData.createManager(employeeNumber = "TNV_M_$suffix", username = "tnv_m_$suffix")
        }

    private fun seedOpenItem(
        sessionId: Long,
        closer: Employee,
        product: org.example.project.features.product.domain.models.Product,
        vat: Vat,
        price: BigDecimal,
    ): Long =
        transaction {
            val session = TableSession.findById(sessionId)!!
            // Authorize the closer as the session owner (works for ADMIN too — isManager() is
            // MANAGER-only, so we cannot rely on it for the OWNER case).
            session.employeeId = closer
            OrderItem
                .new {
                    this.session = session
                    this.product = product
                    this.unitPrice = price
                    this.finalPrice = price
                    this.itemStatus = ItemStatus.OPEN
                    this.vat = vat
                    this.discount = BigDecimal.ONE
                    this.customPrice = false
                    this.takeAway = false
                    this.transferred = false
                    this.sideNotes = null
                }.id.value
        }

    private fun billService(): BillService = GlobalContext.get().get<BillService>()

    /**
     * Seeds a one-item (20.00 @ 19 %) session and closes it through the real service with [tip],
     * paid in full by cash. Returns the created bill id.
     */
    private fun closeBillWithTip(
        tip: BigDecimal,
        recipient: TipRecipient,
        suffix: String,
    ): Long {
        val vat19 = TestData.createDefaultVatRates().first()
        ensureZeroRateVat()
        val closer = closerFor(recipient, suffix)
        val cash = TestData.createPaymentType("Cash_$suffix", isCash = true)
        val tws = TestData.createTableWithSession("TIPNV_$suffix")
        val category = TestData.createCategory("Tip NV Cat $suffix")
        val product =
            TestData.createProduct(category.id.value, "Tip NV Product $suffix", "TNV_$suffix", BigDecimal("20.0000"))
        createTerminal()

        val itemId = seedOpenItem(tws.session.id.value, closer, product, vat19, BigDecimal("20.00"))

        return runBlocking {
            billService()
                .createBill(
                    employeeId = closer.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = "20.00",
                            tips = tip.toPlainString(),
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = (BigDecimal("20.00") + tip).toPlainString(),
                                        paymentTypeId = cash.id.value,
                                    ),
                                ),
                        ),
                ).billId
        }
    }

    /** One [item] @19 %, a BILL_WIDE FIXED_AMOUNT [promotion], an EMPLOYEE [tip], the rest in cash. */
    private fun closeBillWithPromotionAndTip(
        item: BigDecimal,
        promotion: BigDecimal,
        tip: BigDecimal,
    ): Long {
        val vat19 = TestData.createDefaultVatRates().first()
        ensureZeroRateVat()
        val closer = closerFor(TipRecipient.EMPLOYEE, "PRO")
        val cash = TestData.createPaymentType("Cash_PRO", isCash = true)
        val tws = TestData.createTableWithSession("TIPNV_PRO")
        val category = TestData.createCategory("Tip NV Cat PRO")
        val product = TestData.createProduct(category.id.value, "Tip NV Product PRO", "TNV_PRO", item)
        createTerminal()

        val itemId = seedOpenItem(tws.session.id.value, closer, product, vat19, item)
        val promotionId =
            transaction {
                Promotion
                    .new {
                        this.name = "${promotion.toPlainString()} off bill"
                        this.description = null
                        this.discountType = DiscountType.FIXED_AMOUNT
                        this.discountValue = promotion
                        this.scope = PromotionScope.BILL_WIDE
                        this.targetProduct = null
                        this.isMultiUse = false
                        this.validFrom = currentTimeStamp()
                        this.validUntil = null
                        this.isActive = true
                        this.createdBy = closer
                        this.createdAt = currentTimeStamp()
                    }.id.value
            }

        return runBlocking {
            billService()
                .createBill(
                    employeeId = closer.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = item.toPlainString(),
                            tips = tip.toPlainString(),
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = (item - promotion + tip).toPlainString(),
                                        paymentTypeId = cash.id.value,
                                    ),
                                ),
                            promotions =
                                listOf(
                                    PromotionDto.PromotionApplyRequestDto(promotionId = promotionId, orderItemId = null),
                                ),
                        ),
                ).billId
        }
    }

    /** One [item] @19 %, part-paid by a [voucher] Mehrzweckgutschein tender, an EMPLOYEE [tip], rest cash. */
    private fun closeBillWithVoucherAndTip(
        item: BigDecimal,
        voucher: BigDecimal,
        tip: BigDecimal,
    ): Long {
        val vat19 = TestData.createDefaultVatRates().first()
        ensureZeroRateVat()
        val closer = closerFor(TipRecipient.EMPLOYEE, "VOU")
        val cash = TestData.createPaymentType("Cash_VOU", isCash = true)
        val voucherTender = TestData.createPaymentType("Voucher_VOU", isCash = false, isVoucher = true)
        val tws = TestData.createTableWithSession("TIPNV_VOU")
        val category = TestData.createCategory("Tip NV Cat VOU")
        val product = TestData.createProduct(category.id.value, "Tip NV Product VOU", "TNV_VOU", item)
        createTerminal()

        val itemId = seedOpenItem(tws.session.id.value, closer, product, vat19, item)

        return runBlocking {
            billService()
                .createBill(
                    employeeId = closer.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = item.toPlainString(),
                            tips = tip.toPlainString(),
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = voucher.toPlainString(),
                                        paymentTypeId = voucherTender.id.value,
                                    ),
                                    PaymentDto.PaymentRequest(
                                        amount = (item - voucher + tip).toPlainString(),
                                        paymentTypeId = cash.id.value,
                                    ),
                                ),
                            // EXTERNAL source: no internal Voucher row lookup, so the fixture stays
                            // focused on the tender's effect on the non-taxable bucket.
                            voucherRedeem =
                                listOf(
                                    VoucherDto.RedeemVoucherRequestDto(
                                        voucherNumber = "EXT-TNV-1",
                                        redeemedAmount = voucher.toPlainString(),
                                        voucherSource = VoucherSourceType.EXTERNAL,
                                    ),
                                ),
                        ),
                ).billId
        }
    }

    /** One [item] at the 0 % rate plus an EMPLOYEE [tip] — item row and tip share the same slot. */
    private fun closeZeroRatedBillWithTip(
        item: BigDecimal,
        tip: BigDecimal,
    ): Long {
        TestData.createDefaultVatRates()
        val vat0 = ensureZeroRateVat()
        val closer = closerFor(TipRecipient.EMPLOYEE, "ZER")
        val cash = TestData.createPaymentType("Cash_ZER", isCash = true)
        val tws = TestData.createTableWithSession("TIPNV_ZER")
        val category = TestData.createCategory("Tip NV Cat ZER")
        val product = TestData.createProduct(category.id.value, "Tip NV Product ZER", "TNV_ZER", item)
        createTerminal()

        val itemId = seedOpenItem(tws.session.id.value, closer, product, vat0, item)

        return runBlocking {
            billService()
                .createBill(
                    employeeId = closer.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = item.toPlainString(),
                            tips = tip.toPlainString(),
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = (item + tip).toPlainString(),
                                        paymentTypeId = cash.id.value,
                                    ),
                                ),
                        ),
                ).billId
        }
    }

    /** Stornos [billId] through the real service (by its own closer) and returns the reverse bill id. */
    private fun stornoOf(billId: Long): Long {
        val closerId =
            transaction {
                val original = Bill.findById(billId)!!
                original.employeeId.id.value
            }
        runBlocking {
            billService().deleteBill(
                employeeId = closerId.toString(),
                deviceId = deviceId,
                billId = billId.toString(),
                deletingReason = "tip storno test",
            )
        }
        return transaction {
            val reversal = Bill.find { BillTable.deletedBillId eq billId }.single()
            reversal.id.value
        }
    }

    /**
     * Rewrites [billId] into the shape a bill closed BEFORE this change had: the employee tip stored
     * as a 0 % [BillVatDetail] row, so `Σ vatDetails == paidAmount`. `TsePendingSweeper` re-derives
     * exactly such a bill when it is stranded PENDING across a deploy.
     */
    private fun addLegacyZeroRateTipRow(billId: Long) {
        val zeroVat = ensureZeroRateVat()
        transaction {
            val target = Bill.findById(billId)!!
            BillVatDetail.new {
                this.bill = target
                this.totalAmount = target.tips
                this.netAmount = target.tips
                this.vatAmount = BigDecimal.ZERO
                this.vat = zeroVat
            }
        }
    }

    @Test
    fun `employee tip - no zero-rate BillVatDetail row is written`() {
        var billId = 0L
        testApp(
            setupData = {
                // 20.00 item @19%, 3.00 employee tip, 23.00 cash
                billId = closeBillWithTip(tip = BigDecimal("3.00"), recipient = TipRecipient.EMPLOYEE, suffix = "NZR")
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!
                assertTrue(
                    bill.vatDetails.none { it.vat.vatRate.compareTo(BigDecimal.ZERO) == 0 },
                    "employee tip must not be represented as a 0% VAT row; got " +
                        "${bill.vatDetails.map { it.vat.vatRate to it.totalAmount }}",
                )
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal("20.00")),
                    "vatDetails must sum to the taxable turnover only",
                )
            }
        }
    }

    @Test
    fun `employee tip - signed ProcessData is unchanged - tip still in the 0pct slot`() {
        var billId = 0L
        testApp(
            setupData = {
                billId = closeBillWithTip(tip = BigDecimal("3.00"), recipient = TipRecipient.EMPLOYEE, suffix = "SIG")
            },
        ) { _ ->
            val parsed = parseProcessData(billId)
            assertEquals("20.00", parsed.vatSlots[0].toPlainString(), "19% slot")
            assertEquals("3.00", parsed.vatSlots[4].toPlainString(), "non-taxable slot carries the tip")
            assertEquals(0, parsed.vatTotal.compareTo(parsed.paymentTotal), "Σ Umsatz must equal Σ Zahlung: ${parsed.raw}")
        }
    }

    /**
     * Pins the COUPLING between [NonTaxableTipCarrier] and `BillServiceImpl.createBillInternal`'s
     * "8. Validate VAT calculation" block (BillServiceImpl.kt:1892-1905).
     *
     * The carrier tells a post-change bill from a pre-upgrade one purely by whether
     * `Σ vatDetails == paidAmount`. That is only a discriminator because bill creation REJECTS any
     * bill whose rows do not sum to `paidAmount − non-taxable tip`. Relax that validation, or add a
     * bill-creation path that skips it, and the carrier silently mis-classifies — signing a wrong
     * payload into WORM, where an imbalance is only ever a warning, never an error.
     */
    @Test
    fun `employee tip via the service - vatDetails never equal paidAmount - pins the carrier's discriminator`() {
        var billId = 0L
        testApp(
            setupData = {
                billId = closeBillWithTip(tip = BigDecimal("3.00"), recipient = TipRecipient.EMPLOYEE, suffix = "CPL")
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!
                val vatSum = bill.vatDetails.sumOf { it.totalAmount }
                assertTrue(
                    vatSum.compareTo(bill.paidAmount) != 0,
                    "Σ vatDetails ($vatSum) must NEVER equal paidAmount (${bill.paidAmount}) on a bill closed " +
                        "through the service with a non-zero employee tip — NonTaxableTipCarrier reads that " +
                        "equality as 'pre-upgrade, the tip is already a row' and would drop the tip from the " +
                        "signed payload",
                )
                assertEquals(
                    0,
                    vatSum.compareTo(bill.paidAmount - bill.tips),
                    "the invariant createBillInternal enforces: Σ vatDetails == paidAmount − non-taxable tip",
                )
                assertEquals(
                    0,
                    NonTaxableTipCarrier.outsideVatDetails(bill).compareTo(bill.tips),
                    "with that invariant holding, the carrier must classify the bill as post-change and carry the whole tip",
                )
            }
        }
    }

    // --- Interaction cases: each one is a path where the new invariant could break ---

    @Test
    fun `bill-wide promotion plus employee tip - vatDetails sum to billAmount minus promotion`() {
        var billId = 0L
        testApp(
            setupData = {
                // 20.00 @19%, 5.00 bill-wide promotion, 3.00 employee tip, 18.00 cash.
                billId =
                    closeBillWithPromotionAndTip(
                        item = BigDecimal("20.00"),
                        promotion = BigDecimal("5.00"),
                        tip = BigDecimal("3.00"),
                    )
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal("15.00")),
                    "post-promotion turnover only",
                )
            }
            val parsed = parseProcessData(billId)
            assertEquals("15.00", parsed.vatSlots[0].toPlainString())
            assertEquals("3.00", parsed.vatSlots[4].toPlainString())
            assertEquals(0, parsed.vatTotal.compareTo(parsed.paymentTotal), parsed.raw)
        }
    }

    @Test
    fun `voucher redemption plus employee tip - both share the non-taxable slot`() {
        var billId = 0L
        testApp(
            setupData = {
                // 20.00 @19%, 10.00 Mehrzweckgutschein, 3.00 employee tip, 13.00 cash.
                billId =
                    closeBillWithVoucherAndTip(
                        item = BigDecimal("20.00"),
                        voucher = BigDecimal("10.00"),
                        tip = BigDecimal("3.00"),
                    )
            },
        ) { _ ->
            val parsed = parseProcessData(billId)
            assertEquals("20.00", parsed.vatSlots[0].toPlainString(), "19% slot unchanged")
            assertEquals(
                "-7.00",
                parsed.vatSlots[4].toPlainString(),
                "tip (+3.00) and voucher (-10.00) net in the same bucket: ${parsed.raw}",
            )
            assertEquals(0, parsed.vatTotal.compareTo(parsed.paymentTotal), parsed.raw)
        }
    }

    @Test
    fun `zero-rated item plus employee tip - one VAT row, tip added on top of the same slot`() {
        var billId = 0L
        testApp(
            setupData = {
                // 5.00 @0% item, 3.00 employee tip, 8.00 cash.
                billId = closeZeroRatedBillWithTip(item = BigDecimal("5.00"), tip = BigDecimal("3.00"))
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(1, bill.vatDetails.count(), "only the item's own 0% row remains")
            }
            val parsed = parseProcessData(billId)
            assertEquals("8.00", parsed.vatSlots[4].toPlainString(), "item + tip in the non-taxable slot")
            assertEquals(0, parsed.vatTotal.compareTo(parsed.paymentTotal), parsed.raw)
        }
    }

    @Test
    fun `pre-upgrade bill whose tip is still a 0pct row - the tip is not counted twice`() {
        var billId = 0L
        testApp(
            setupData = {
                billId = closeBillWithTip(tip = BigDecimal("3.00"), recipient = TipRecipient.EMPLOYEE, suffix = "LEG")
                addLegacyZeroRateTipRow(billId)
            },
        ) { _ ->
            // Prove the fixture really produced the legacy shape: an ineffective fixture would
            // leave a post-change bill, and the assertions below would then pass vacuously.
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(2, bill.vatDetails.count(), "legacy shape: the 19% row plus the 0% tip row")
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(bill.paidAmount),
                    "legacy shape: Σ vatDetails == paidAmount is exactly the discriminator the carrier keys on",
                )
            }
            val parsed = parseProcessData(billId)
            assertEquals(
                "3.00",
                parsed.vatSlots[4].toPlainString(),
                "a bill closed before the change already carries its tip as a 0% row, so re-deriving " +
                    "must NOT add it again — 6.00 here would be signed against 23.00 of tender: ${parsed.raw}",
            )
            assertEquals(0, parsed.vatTotal.compareTo(parsed.paymentTotal), "Σ Umsatz must equal Σ Zahlung: ${parsed.raw}")
        }
    }

    /**
     * The one configuration where the carrier's "cannot be confused with a genuine 0 %-rated item"
     * claim is load-bearing: a PRE-UPGRADE bill that also sold a real 0 %-rated item, so a 0 % item
     * row and a legacy 0 % tip row sit side by side and nothing distinguishes them by rate. The
     * discriminator is the SUM, not the presence of a 0 % row, so it must still return ZERO.
     */
    @Test
    fun `pre-upgrade bill with a 0pct item and a 0pct tip row - the tip is still not counted twice`() {
        var billId = 0L
        testApp(
            setupData = {
                // 5.00 @0% item, 3.00 employee tip, 8.00 cash — then rewritten to the legacy shape.
                billId = closeZeroRatedBillWithTip(item = BigDecimal("5.00"), tip = BigDecimal("3.00"))
                addLegacyZeroRateTipRow(billId)
            },
        ) { _ ->
            // Fixture shape first, as in the other pre-upgrade case: without it the assertions
            // below would pass vacuously on a post-change bill.
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(2, bill.vatDetails.count(), "legacy shape: the 0% item row plus the 0% tip row")
                assertTrue(
                    bill.vatDetails.all { it.vat.vatRate.compareTo(BigDecimal.ZERO) == 0 },
                    "both rows are 0%-rated, so no rate-based test could tell the item row from the tip row",
                )
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(bill.paidAmount),
                    "legacy shape: Σ vatDetails == paidAmount is exactly the discriminator the carrier keys on",
                )
                assertEquals(
                    0,
                    NonTaxableTipCarrier.outsideVatDetails(bill).compareTo(BigDecimal.ZERO),
                    "a genuine 0%-rated item must not stop the carrier recognising the pre-upgrade shape",
                )
            }
            val parsed = parseProcessData(billId)
            assertEquals(
                "8.00",
                parsed.vatSlots[4].toPlainString(),
                "the 0% item (5.00) plus the stored legacy tip row (3.00); adding the tip again would sign " +
                    "11.00 against 8.00 of tender: ${parsed.raw}",
            )
            assertEquals(0, parsed.vatTotal.compareTo(parsed.paymentTotal), "Σ Umsatz must equal Σ Zahlung: ${parsed.raw}")
        }
    }

    @Test
    fun `storno of a pre-upgrade tipped bill - the tip is reversed exactly once`() {
        var reversalId = 0L
        testApp(
            setupData = {
                val originalId = closeBillWithTip(tip = BigDecimal("3.00"), recipient = TipRecipient.EMPLOYEE, suffix = "LGS")
                addLegacyZeroRateTipRow(originalId)
                reversalId = stornoOf(originalId)
            },
        ) { _ ->
            val parsed = parseProcessData(reversalId)
            assertEquals("-20.00", parsed.vatSlots[0].toPlainString(), "19% slot negated")
            assertEquals(
                "-3.00",
                parsed.vatSlots[4].toPlainString(),
                "the copied 0% row already reverses the tip; adding the negated tip on top would " +
                    "sign -6.00: ${parsed.raw}",
            )
            assertEquals(
                0,
                parsed.vatTotal.compareTo(parsed.paymentTotal),
                "a double-counted tip unbalances the reversed Beleg: ${parsed.raw}",
            )
        }
    }

    @Test
    fun `storno of a tipped bill - the reversed Beleg still balances`() {
        var reversalId = 0L
        testApp(
            setupData = {
                val originalId = closeBillWithTip(tip = BigDecimal("3.00"), recipient = TipRecipient.EMPLOYEE, suffix = "STO")
                reversalId = stornoOf(originalId)
            },
        ) { _ ->
            val parsed = parseProcessData(reversalId)
            assertEquals("-20.00", parsed.vatSlots[0].toPlainString(), "19% slot negated")
            assertEquals("-3.00", parsed.vatSlots[4].toPlainString(), "the tip must be reversed too")
            assertEquals(
                0,
                parsed.vatTotal.compareTo(parsed.paymentTotal),
                "a storno that drops the tip signs an unbalanced Beleg: ${parsed.raw}",
            )
        }
    }
}
