package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
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
 * Pins the H3c fix: the signed `Kassenbeleg-V1` ProcessData must ALWAYS balance
 * (`Σ Umsatz` over the 5 brutto slots == `Σ Zahlung`), for both an EMPLOYEE tip
 * (TrinkgeldAN, modelled non-taxable in the 0%/key-5 slot) and an OWNER tip
 * (TrinkgeldAG, taxed and folded proportionally into the item VAT rates).
 *
 * An EMPLOYEE tip is deliberately NOT a `bill.vatDetails` row — it is non-taxable and is not
 * turnover. It is carried outside the VAT breakdown and reaches the signed payload through
 * `ProcessDataBuilder`'s `nonTaxableExtra`, which puts it in that same 5th ("nicht steuerbar")
 * brutto slot, so the Beleg balances exactly as before (Σ Umsatz 23.00 == Σ Zahlung 23.00).
 *
 * What this class guards is the tip going missing from BOTH — absent from `vatDetails` and never
 * handed to the builder — while payments still include it. That signs an unbalanced Beleg
 * (`Beleg^20.00_..._0.00^23.00:Bar` — Σ Umsatz 20.00 ≠ Σ Zahlung 23.00), which an auditor's
 * verification tool flags. The OWNER case balances by a different route (the tip is taxed into the
 * item rates) and is guarded here against regression.
 */
@DisplayName("Bill signing balance — tip modelling (H3c)")
class BillSigningBalanceTest {
    private val deviceId = "tip-balance-device"

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
                .fold(BigDecimal.ZERO) { acc, tender -> acc + BigDecimal(tender.substringBefore(":")) }
        return ParsedProcessData(vatSlots, paymentTotal, processData)
    }

    private fun createTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillSigningBalanceTest.deviceId
                this.clientId = "TERM-TIP-BALANCE"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    /**
     * Seeds a one-item (20.00 @ 19%) session, has [billingEmployee] close it with a 3.00 tip
     * paid 23.00 cash via the real service, then rebuilds the signed Kassenbeleg-V1 ProcessData
     * exactly as production does and returns it parsed.
     */
    private fun runTipCase(
        billingEmployee: Employee,
        tableSuffix: String,
    ): ParsedProcessData {
        // Rates A(19%) + B(7%); add the non-taxable 0% rate (key-5) an employee tip needs.
        val vats = TestData.createDefaultVatRates()
        transaction {
            if (Vat.findById("C") == null) {
                Vat.new("C") {
                    this.vatClass = "Zero"
                    this.vatRate = BigDecimal("0.0000")
                }
            }
        }
        val vat19 = vats.first()
        val cash = TestData.createPaymentType("Cash_$tableSuffix", isCash = true)
        val tws = TestData.createTableWithSession("TIPBAL_$tableSuffix")
        val category = TestData.createCategory("Tip Balance Cat $tableSuffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "Tip Balance Product $tableSuffix",
                productNumber = "TB_$tableSuffix",
                price = BigDecimal("20.0000"),
            )
        createTerminal()

        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                // Authorize the closer as the session owner (works for ADMIN too — isManager() is
                // MANAGER-only, so we cannot rely on it for the OWNER/admin case).
                session.employeeId = billingEmployee
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("20.00")
                        this.finalPrice = BigDecimal("20.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        val billService = GlobalContext.get().get<BillService>()
        val response =
            runBlocking {
                billService.createBill(
                    employeeId = billingEmployee.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = "20.00",
                            tips = "3.00",
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = cash.id.value),
                                ),
                        ),
                )
            }

        return transaction {
            val bill = Bill.findById(response.billId)!!
            // Derive through the SAME entry point production signs with (BillServiceImpl:378), so
            // this stays a witness to the signed payload instead of a duplicate of how it is built.
            parse(ProcessDataBuilder.buildForBill(bill))
        }
    }

    @Test
    fun `employee tip - signed Beleg balances with the tip in the 0pct slot`() =
        testApp(
            setupData = {
                // MANAGER is non-ADMIN -> tipRecipient EMPLOYEE (TrinkgeldAN).
                val manager = TestData.createManager(employeeNumber = "TB_MGR", username = "tb_mgr")
                val parsed = runTipCase(manager, "EMP")

                assertEquals(
                    0,
                    parsed.vatTotal.compareTo(parsed.paymentTotal),
                    "signed Beleg must balance: Σ Umsatz (${parsed.vatTotal}) == Σ Zahlung " +
                        "(${parsed.paymentTotal}); processData=${parsed.raw}",
                )
                assertEquals(
                    0,
                    parsed.paymentTotal.compareTo(BigDecimal("23.00")),
                    "sanity: payments total 23.00 (20 item + 3 tip); got ${parsed.paymentTotal}",
                )
                // Tip is non-taxable -> lands in the 5th (0%/key-5) brutto slot.
                assertEquals(
                    0,
                    parsed.vatSlots[4].compareTo(BigDecimal("3.00")),
                    "employee tip must occupy the 0% slot (=3.00); slots=${parsed.vatSlots}",
                )
                // Item revenue stays in the 19% slot, untaxed-tip-free.
                assertEquals(
                    0,
                    parsed.vatSlots[0].compareTo(BigDecimal("20.00")),
                    "item revenue must stay 20.00 in the 19% slot; slots=${parsed.vatSlots}",
                )
            },
        ) { _ -> }

    @Test
    fun `owner tip - signed Beleg balances with the tip taxed into the item rate`() =
        testApp(
            setupData = {
                // ADMIN -> tipRecipient OWNER (TrinkgeldAG): taxed, folded into the item VAT rate.
                val admin = TestData.createAdmin(employeeNumber = "TB_ADM", username = "tb_adm")
                val parsed = runTipCase(admin, "OWN")

                assertEquals(
                    0,
                    parsed.vatTotal.compareTo(parsed.paymentTotal),
                    "signed Beleg must balance: Σ Umsatz (${parsed.vatTotal}) == Σ Zahlung " +
                        "(${parsed.paymentTotal}); processData=${parsed.raw}",
                )
                assertEquals(
                    0,
                    parsed.paymentTotal.compareTo(BigDecimal("23.00")),
                    "sanity: payments total 23.00 (20 item + 3 tip); got ${parsed.paymentTotal}",
                )
                // Owner tip is taxed and folded into the 19% item slot; the 0% slot stays empty.
                assertEquals(
                    0,
                    parsed.vatSlots[0].compareTo(BigDecimal("23.00")),
                    "owner tip must fold into the 19% slot (=23.00); slots=${parsed.vatSlots}",
                )
                assertEquals(
                    0,
                    parsed.vatSlots[4].compareTo(BigDecimal("0.00")),
                    "owner tip must NOT occupy the 0% slot; slots=${parsed.vatSlots}",
                )
            },
        ) { _ -> }

    /**
     * Seeds a one-item (20.00 @ 19%) session, bills it via the real service (single 20.00 cash
     * payment, no tip — keeps the balance check unambiguous), then stornos it via
     * [BillService.deleteBill] and rebuilds the signed Kassenbeleg-V1 ProcessData exactly as
     * BillServiceImpl.kt:540 does for the storno path, from the REAL negated `BillVatDetail` rows
     * and the REAL refundedBillId-stamped payments that `deleteBill` wrote to the DB (not
     * hand-crafted). Returns it parsed.
     *
     * The `stornoBonType` selection below mirrors BillServiceImpl.kt:540's ternary — for this
     * non-training fixture that resolves to [BonType.BELEG] (DSFinV-K Tz. 4.2.1/4.2.2: a
     * TSE-secured storno must be a "Beleg" with negated amounts, not "AVBelegstorno" — see the
     * `determineBonTyp` comment in DsfinvkExportServiceImpl, which already emits BON_TYP="Beleg"
     * for stornos and would otherwise disagree with what gets signed).
     */
    private fun runStornoCase(
        billingEmployee: Employee,
        tableSuffix: String,
    ): ParsedProcessData {
        val vats = TestData.createDefaultVatRates()
        val vat19 = vats.first()
        val cash = TestData.createPaymentType("Cash_$tableSuffix", isCash = true)
        val tws = TestData.createTableWithSession("STORNO_$tableSuffix")
        val category = TestData.createCategory("Storno Cat $tableSuffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "Storno Product $tableSuffix",
                productNumber = "ST_$tableSuffix",
                price = BigDecimal("20.0000"),
            )
        createTerminal()

        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = billingEmployee
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("20.00")
                        this.finalPrice = BigDecimal("20.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        val billService = GlobalContext.get().get<BillService>()
        val billId =
            runBlocking {
                billService
                    .createBill(
                        employeeId = billingEmployee.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(itemId),
                                billAmount = "20.00",
                                tips = "0.00",
                                payments =
                                    listOf(
                                        PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = cash.id.value),
                                    ),
                            ),
                    ).billId
            }

        runBlocking {
            billService.deleteBill(
                employeeId = billingEmployee.id.value.toString(),
                deviceId = deviceId,
                billId = billId.toString(),
                deletingReason = "storno signing test",
            )
        }

        return transaction {
            val reverseBill = Bill.find { BillTable.deletedBillId eq billId }.single()
            val stornoBonType = if (reverseBill.isTraining) BonType.AV_TRAINING else BonType.BELEG
            val processData =
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = stornoBonType,
                    vatDetail = reverseBill.vatDetails.toList(),
                    payments = reverseBill.refundPayments.toList(),
                )
            parse(processData)
        }
    }

    /**
     * An OWNER tip (TrinkgeldAG) is taxable at the rate of the underlying supply — Anhang I p. 120
     * signs it in the 19 % container. That does not change when a promotion discounts the items to
     * zero: the supply still happened at 19 %, only its price did not. The signed Beleg must therefore
     * carry the tip in the item's rate slot, never in the 0 % (nicht steuerbar) slot that belongs to
     * an EMPLOYEE tip.
     *
     * Fixture: one 20.00 @19% item, a 20.00 FIXED_AMOUNT bill-wide promotion, the ADMIN (OWNER)
     * closes with a 3.00 tip, paid 3.00 cash.
     */
    @Test
    fun `owner tip on a fully discounted bill - taxed at the item rate not the 0pct slot`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "TB_FD", username = "tb_fd")
                val vats = TestData.createDefaultVatRates()
                val vat19 = vats[0]
                transaction {
                    if (Vat.findById("C") == null) {
                        Vat.new("C") {
                            this.vatClass = "Zero"
                            this.vatRate = BigDecimal("0.0000")
                        }
                    }
                }
                val cash = TestData.createPaymentType("Cash_FD", isCash = true)
                val tws = TestData.createTableWithSession("FDPROMO")
                val category = TestData.createCategory("FD Cat")
                val product = TestData.createProduct(category.id.value, "FD A", "FD_A", BigDecimal("20.0000"))
                createTerminal()

                val ids =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        val now = currentTimeStamp()
                        val item =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = product
                                    this.unitPrice = BigDecimal("20.00")
                                    this.finalPrice = BigDecimal("20.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat19
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        val promo =
                            Promotion
                                .new {
                                    this.name = "20.00 off bill"
                                    this.description = null
                                    this.discountType = DiscountType.FIXED_AMOUNT
                                    this.discountValue = BigDecimal("20.00")
                                    this.scope = PromotionScope.BILL_WIDE
                                    this.targetProduct = null
                                    this.isMultiUse = false
                                    this.validFrom = now
                                    this.validUntil = null
                                    this.isActive = true
                                    this.createdBy = admin
                                    this.createdAt = now
                                }.id.value
                        item to promo
                    }

                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(ids.first),
                                    billAmount = "20.00",
                                    tips = "3.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "3.00", paymentTypeId = cash.id.value),
                                        ),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(promotionId = ids.second, orderItemId = null),
                                        ),
                                ),
                        )
                    }

                transaction {
                    val bill = Bill.findById(response.billId)!!
                    val parsed =
                        parse(
                            ProcessDataBuilder.build(
                                processType = ProcessType.KASSENBELEG_V1,
                                bonType = BonType.BELEG,
                                vatDetail = bill.vatDetails.toList(),
                                payments = bill.payments.toList(),
                            ),
                        )
                    assertEquals(
                        0,
                        parsed.vatTotal.compareTo(parsed.paymentTotal),
                        "signed Beleg must balance: Σ Umsatz (${parsed.vatTotal}) == Σ Zahlung (${parsed.paymentTotal}); " +
                            "processData=${parsed.raw}",
                    )
                    assertEquals(
                        0,
                        parsed.vatSlots[0].compareTo(BigDecimal("3.00")),
                        "owner tip on a fully discounted 19% bill must be taxed in the 19% slot (=3.00); slots=${parsed.vatSlots}",
                    )
                    assertEquals(
                        0,
                        parsed.vatSlots[4].compareTo(BigDecimal.ZERO),
                        "owner tip must NOT fall into the 0% (nicht steuerbar) slot; slots=${parsed.vatSlots}",
                    )
                    val tipRow = bill.vatDetails.single { it.vat.id.value == vat19.id.value }
                    assertEquals(0, tipRow.netAmount.compareTo(BigDecimal("2.52")), "tip net 3.00/1.19; got ${tipRow.netAmount}")
                    assertEquals(0, tipRow.vatAmount.compareTo(BigDecimal("0.48")), "tip VAT 3.00-2.52; got ${tipRow.vatAmount}")
                }
            },
        ) { _ -> }

    /**
     * RA-1 signing guard: a BILL_WIDE promotion across two VAT rates must still produce a balanced
     * signed Kassenbeleg. The bill-wide deduction is now split by a penny-exact largest-remainder
     * apportionment (was a scale-4 proportional split that re-rounded per group), so the per-rate
     * `BillVatDetail.totalAmount` sum equals `paidAmount` EXACTLY — createBill must NOT throw the
     * `Σ vatDetails == paidAmount` validation, and `Σ Umsatz == Σ Zahlung` on the signed Beleg.
     *
     * Fixture: 5.00@19% + 5.00@7% (billAmount 10.00), a 0.05 FIXED_AMOUNT bill-wide promo, paid 9.95.
     * The apportionment breaks the equal-remainder tie on the lower rate → 19%→4.98, 7%→4.97 (Σ 9.95).
     */
    @Test
    fun `bill-wide promotion two rates - signed Beleg balances and vatDetails sum to paid`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "TB_BW", username = "tb_bw")
                val vats = TestData.createDefaultVatRates()
                val vat19 = vats[0]
                val vat7 = vats[1]
                val cash = TestData.createPaymentType("Cash_BW", isCash = true)
                val tws = TestData.createTableWithSession("BWPROMO")
                val category = TestData.createCategory("BW Cat")
                val productA = TestData.createProduct(category.id.value, "BW A", "BW_A", BigDecimal("5.0000"))
                val productB = TestData.createProduct(category.id.value, "BW B", "BW_B", BigDecimal("5.0000"))
                createTerminal()

                val ids =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        val now = currentTimeStamp()
                        val a =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = productA
                                    this.unitPrice = BigDecimal("5.00")
                                    this.finalPrice = BigDecimal("5.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat19
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        val b =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = productB
                                    this.unitPrice = BigDecimal("5.00")
                                    this.finalPrice = BigDecimal("5.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat7
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        val promo =
                            Promotion
                                .new {
                                    this.name = "0.05 off bill"
                                    this.description = null
                                    this.discountType = DiscountType.FIXED_AMOUNT
                                    this.discountValue = BigDecimal("0.05")
                                    this.scope = PromotionScope.BILL_WIDE
                                    this.targetProduct = null
                                    this.isMultiUse = false
                                    this.validFrom = now
                                    this.validUntil = null
                                    this.isActive = true
                                    this.createdBy = admin
                                    this.createdAt = now
                                }.id.value
                        Triple(a, b, promo)
                    }

                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(ids.first, ids.second),
                                    billAmount = "10.00",
                                    tips = "0.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "9.95", paymentTypeId = cash.id.value),
                                        ),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(promotionId = ids.third, orderItemId = null),
                                        ),
                                ),
                        )
                    }

                transaction {
                    val bill = Bill.findById(response.billId)!!
                    val vatSum = bill.vatDetails.sumOf { it.totalAmount }
                    assertEquals(
                        0,
                        vatSum.compareTo(BigDecimal("9.95")),
                        "Σ BillVatDetail.totalAmount ($vatSum) must equal paidAmount (9.95) after the " +
                            "largest-remainder bill-wide split",
                    )
                    val parsed =
                        parse(
                            ProcessDataBuilder.build(
                                processType = ProcessType.KASSENBELEG_V1,
                                bonType = BonType.BELEG,
                                vatDetail = bill.vatDetails.toList(),
                                payments = bill.payments.toList(),
                            ),
                        )
                    assertEquals(
                        0,
                        parsed.vatTotal.compareTo(parsed.paymentTotal),
                        "signed bill-wide-promo Beleg must balance: Σ Umsatz (${parsed.vatTotal}) == " +
                            "Σ Zahlung (${parsed.paymentTotal}); processData=${parsed.raw}",
                    )
                    assertEquals(
                        0,
                        parsed.paymentTotal.compareTo(BigDecimal("9.95")),
                        "sanity: payments total 9.95 (10.00 items − 0.05 promo); got ${parsed.paymentTotal}",
                    )
                }
            },
        ) { _ -> }

    /**
     * RA-1 Finding 2 regression guard: two DISTINCT `Vat` entities are allowed to share the SAME
     * rate (`VatServiceImpl` only enforces uniqueness on `vatClass`, not `vatRate`). The bill-wide
     * apportionment used to key its weights straight off `vat.vatRate` via a single `associate`
     * call, which silently collapsed the two same-rate entities into one (last-write-wins),
     * dropping one entity's weight and double-subtracting the surviving share from BOTH VAT
     * groups — unbalancing `Σ vatDetails` and making `createBill` throw the payment/VAT mismatch
     * validation for an otherwise perfectly legitimate bill.
     *
     * Fixture: two 19% Vat entities ("A" and a second same-rate entity), groups 6.00 + 4.00
     * (billAmount 10.00), a 0.05 FIXED_AMOUNT bill-wide promo, paid 9.95. The fix apportions per
     * RATE first (both groups share rate 0.19, combined weight 10.00 -> the whole 0.05 share),
     * then splits that rate's share back across the two entities weighted 6:4 -> 0.03 / 0.02
     * (exact, no remainder tie), so 19%-A -> 5.97 and 19%-B -> 3.98 (Σ 9.95 == paid).
     */
    @Test
    fun `bill-wide promotion two Vat entities same rate - signed Beleg balances and vatDetails sum to paid`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "TB_BWSR", username = "tb_bwsr")
                val vat19a = TestData.createDefaultVatRates().first()
                val vat19b =
                    transaction {
                        Vat.new("A2") {
                            this.vatClass = "Normal2"
                            this.vatRate = BigDecimal("0.1900")
                        }
                    }
                val cash = TestData.createPaymentType("Cash_BWSR", isCash = true)
                val tws = TestData.createTableWithSession("BWSRPROMO")
                val category = TestData.createCategory("BWSR Cat")
                val productA = TestData.createProduct(category.id.value, "BWSR A", "BWSR_A", BigDecimal("6.0000"))
                val productB = TestData.createProduct(category.id.value, "BWSR B", "BWSR_B", BigDecimal("4.0000"))
                createTerminal()

                val ids =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        val now = currentTimeStamp()
                        val a =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = productA
                                    this.unitPrice = BigDecimal("6.00")
                                    this.finalPrice = BigDecimal("6.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat19a
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        val b =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = productB
                                    this.unitPrice = BigDecimal("4.00")
                                    this.finalPrice = BigDecimal("4.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat19b
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        val promo =
                            Promotion
                                .new {
                                    this.name = "0.05 off bill"
                                    this.description = null
                                    this.discountType = DiscountType.FIXED_AMOUNT
                                    this.discountValue = BigDecimal("0.05")
                                    this.scope = PromotionScope.BILL_WIDE
                                    this.targetProduct = null
                                    this.isMultiUse = false
                                    this.validFrom = now
                                    this.validUntil = null
                                    this.isActive = true
                                    this.createdBy = admin
                                    this.createdAt = now
                                }.id.value
                        Triple(a, b, promo)
                    }

                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(ids.first, ids.second),
                                    billAmount = "10.00",
                                    tips = "0.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "9.95", paymentTypeId = cash.id.value),
                                        ),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(promotionId = ids.third, orderItemId = null),
                                        ),
                                ),
                        )
                    }

                transaction {
                    val bill = Bill.findById(response.billId)!!
                    val vatSum = bill.vatDetails.sumOf { it.totalAmount }
                    assertEquals(
                        0,
                        vatSum.compareTo(BigDecimal("9.95")),
                        "Σ BillVatDetail.totalAmount ($vatSum) must equal paidAmount (9.95) even when " +
                            "two distinct same-rate Vat entities are involved",
                    )
                    assertEquals(2, bill.vatDetails.count(), "one BillVatDetail per Vat entity, not merged by rate")
                    val parsed =
                        parse(
                            ProcessDataBuilder.build(
                                processType = ProcessType.KASSENBELEG_V1,
                                bonType = BonType.BELEG,
                                vatDetail = bill.vatDetails.toList(),
                                payments = bill.payments.toList(),
                            ),
                        )
                    assertEquals(
                        0,
                        parsed.vatTotal.compareTo(parsed.paymentTotal),
                        "signed same-rate-collision Beleg must balance: Σ Umsatz (${parsed.vatTotal}) == " +
                            "Σ Zahlung (${parsed.paymentTotal}); processData=${parsed.raw}",
                    )
                    assertEquals(
                        0,
                        parsed.paymentTotal.compareTo(BigDecimal("9.95")),
                        "sanity: payments total 9.95 (10.00 items − 0.05 promo); got ${parsed.paymentTotal}",
                    )
                }
            },
        ) { _ -> }

    @Test
    fun `storno - signed processData is a Beleg with negated amounts, not AVBelegstorno`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "TB_STN", username = "tb_stn")
                val parsed = runStornoCase(admin, "STN")

                assertTrue(
                    parsed.raw.startsWith("Beleg^"),
                    "TSE-secured storno must sign as Beleg^... (DSFinV-K Tz. 4.2.1/4.2.2), matching " +
                        "DsfinvkExportServiceImpl.determineBonTyp()'s BON_TYP=Beleg for storno; " +
                        "got ${parsed.raw}",
                )
                assertTrue(
                    !parsed.raw.startsWith("AVBelegstorno"),
                    "storno must NOT sign as AVBelegstorno (TSE Tz. 4.2.1/4.2.2 forbids it); got ${parsed.raw}",
                )
                assertEquals(
                    0,
                    parsed.vatTotal.compareTo(parsed.paymentTotal),
                    "signed storno Beleg must balance: Σ Umsatz (${parsed.vatTotal}) == Σ Zahlung " +
                        "(${parsed.paymentTotal}); processData=${parsed.raw}",
                )
                assertEquals(
                    0,
                    parsed.vatTotal.compareTo(BigDecimal("-20.00")),
                    "storno brutto total must be the negated original sale (-20.00); got " +
                        "${parsed.vatTotal}; processData=${parsed.raw}",
                )
                assertEquals(
                    0,
                    parsed.paymentTotal.compareTo(BigDecimal("-20.00")),
                    "storno payment total must be the negated original payment (-20.00); got " +
                        "${parsed.paymentTotal}; processData=${parsed.raw}",
                )
            },
        ) { _ -> }
}
