package org.example.project.features.voucher

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.voucher.data.service.VoucherProvisioning
import org.example.project.features.voucher.domain.service.VoucherService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the counter voucher sale (`sessionId = null`) after the quick-sale removal.
 *
 * The sale used to run through `startQuickSaleTse` → TSE StartTransaction →
 * `createQuickSaleBill` → TSE FinishTransaction. That was the ONLY caller of the TSE two-step
 * API and therefore the only way a Vorgang could be abandoned after Transaktionsbeginn — the
 * `AVBelegabbruch` case of DSFinV-K Anhang B p. 46, which the DSFinV-K export has no producer
 * for. It now settles through `BillService.createBill`, which signs atomically via quickSign.
 *
 * Three properties are pinned, because all three are silent if they regress:
 *
 * 1. **Exactly ONE Kassenbeleg-V1 Vorgang.** Routing the sale through `OrderService.createOrder`
 *    would have been the smaller diff but would emit a second, spurious `Bestellung-V1`
 *    transaction per voucher — burning TSE signature counter for a Vorgang that never existed
 *    before. The line is therefore attached straight to the session with `order = null`.
 * 2. **`finalPrice` leaves the price computation at 2dp** (carried finding C3). A voucher value
 *    is operator-supplied and `OrderItemTable.finalPrice` is `decimal(19,4)`, so an unrounded
 *    sub-cent would reach `BillVatDetail` verbatim, where `ZBonServiceImpl` converts each VAT
 *    group independently and can round up against an exact-cent payment — a permanent Anhang I
 *    p. 116 violation in the WORM record.
 * 3. **The virtual VOUCHER session does not leak.** It is completed by the bill, so the next
 *    counter sale opens a fresh one instead of inheriting an OPEN item.
 */
@DisplayName("Counter voucher sale (post quick-sale removal)")
class CounterVoucherSaleTest {
    private val deviceId = "counter-voucher-device"
    private val configRepository = ConfigurationRepository(listOf(VoucherProvisioning()))

    /**
     * The counter sale needs `VOUCHER_CONFIG.enabled == true`, and this test must say so itself.
     *
     * [ShopConfigurationManager] is a process-wide singleton whose cache lives 30 minutes and is
     * NOT reset between tests: `TestDatabase.setup()` mints a fresh H2 database per test, but
     * `loadConfigurationFromDatabase()` returns null when the new database holds no shop yet, and
     * `initialize()` then leaves the PREVIOUS test's cached settings in place. A neighbour that
     * stores `enabled = false` (`VoucherFeatureGateTest`) therefore made this test fail with
     * `VOUCHER_FEATURE_DISABLED` in a full-suite run while passing in isolation.
     *
     * Seeding a shop, writing the setting and forcing a refresh makes the precondition explicit
     * instead of inherited, so the test no longer depends on execution order.
     */
    private fun enableVoucherFeature() {
        val shop = TestData.createShopInfo()
        runBlocking {
            dbQuery {
                configRepository.setSetting(
                    shop.id.value,
                    ConfigKey.VOUCHER_CONFIG,
                    """{"enabled":true,"sources":[{"type":"INTERNAL","name":"Internal System Vouchers",""" +
                        """"enabled":true,"description":"POS vouchers"}]}""",
                )
            }
            ShopConfigurationManager.refreshConfiguration()
        }
    }

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun createTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@CounterVoucherSaleTest.deviceId
                this.clientId = "TERM-COUNTER-VOUCHER"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    private data class CounterFixture(
        val employeeId: String,
        val cashPaymentTypeId: Long,
    )

    /** Admin, VAT rates incl. the 0 % class "C", a cash tender, the VOUCHER table + article, feature on. */
    private fun seedCounterFixture(): CounterFixture {
        val admin = TestData.createAdmin(employeeNumber = "CV_ADM", username = "cv_adm")
        TestData.createDefaultVatRates() // A = 19 %, B = 7 %
        // ProductType.VOUCHER maps to VAT class "C" (0 %) — a Mehrzweckgutschein is taxed at
        // redemption, not at issue (DSFinV-K Anhang C / §3 Abs. 15 UStG).
        transaction {
            if (Vat.findById("C") == null) {
                Vat.new("C") {
                    this.vatClass = "Zero"
                    this.vatRate = BigDecimal("0.0000")
                }
            }
        }
        val cash = TestData.createPaymentType("Cash_CV", isCash = true)
        // Named "VOUCHER" on purpose: `enableVoucherFeature()` below provisions the backing
        // article through `ConfigurationRepository.setSetting`, which looks the product up by
        // (category "VOUCHER", type VOUCHER, number "VOU"). A differently named category would
        // make it create a SECOND "VOU" product and the service lookup would then be ambiguous.
        val category = TestData.createCategory("VOUCHER")
        createTerminal()

        // The virtual VOUCHER table the counter sale opens its session on.
        TestData.createTable(
            tableNumber = "VOUCHER",
            seats = 0,
            locationTypeId = WellKnownLocationTypes.VOUCHER,
        )

        // VoucherServiceImpl resolves the backing article by productNumber "VOU".
        TestData.createProduct(
            categoryId = category.id.value,
            productName = "Gutschein",
            productNumber = "VOU",
            price = BigDecimal("0.0000"),
            productType = ProductType.VOUCHER,
        )

        // Switch the feature on only now: the provisioning `setSetting` performs is
        // idempotent against the fixtures seeded above.
        enableVoucherFeature()
        return CounterFixture(admin.id.value.toString(), cash.id.value)
    }

    @Test
    fun `counter voucher signs exactly one Kassenbeleg and leaves no open session`() =
        testApp(
            setupData = {
                val fixture = seedCounterFixture()
                val voucherService = GlobalContext.get().get<VoucherService>()

                // A value with a sub-cent tail: 25.4950 must be charged as 25.49/25.50, never stored raw.
                val response =
                    runBlocking {
                        voucherService.createVoucher(
                            employeeId = fixture.employeeId,
                            deviceId = deviceId,
                            request =
                                VoucherDto.CreateVoucherRequestDto(
                                    sessionId = null,
                                    originalValue = "25.4950",
                                    paymentTypeId = fixture.cashPaymentTypeId,
                                ),
                            actorCapabilities = emptySet(),
                        )
                    }

                transaction {
                    val orderItem =
                        OrderItem
                            .all()
                            .single { it.sideNotes == "VOUCHER: ${response.voucherNumber}" }

                    // (1) No parent Order -> no AVBestellung Vorgang for a counter voucher.
                    assertNull(
                        orderItem.order,
                        "the counter voucher line must hang off the session directly; a parent Order " +
                            "would emit a second Bestellung-V1 TSE transaction per voucher",
                    )

                    // (2) finalPrice is rounded at SOURCE (C3). The column is decimal(19,4), so the
                    // read-back always has scale 4 — the invariant is that the VALUE is cent-exact,
                    // i.e. no significant digit below the cent. 25.4950 -> 25.50 passes; an unrounded
                    // 25.4950 would strip to scale 3 and fail.
                    assertTrue(
                        orderItem.finalPrice.stripTrailingZeros().scale() <= 2,
                        "finalPrice must leave the price computation cent-exact; got ${orderItem.finalPrice}",
                    )

                    val session = TableSession.findById(orderItem.session!!.id)!!
                    val bill = session.bills.single()

                    // (F38) The response names the bill, so the till can print the customer's receipt.
                    assertEquals(bill.id.value, response.billId, "the counter sale must return the bill it was settled on")

                    // Every signed VAT bucket must be cent-exact for the same reason: ZBonServiceImpl
                    // converts each group independently and would round up against an exact-cent
                    // payment, permanently unbalancing the WORM record (Anhang I p. 116).
                    bill.vatDetails.forEach {
                        assertTrue(
                            it.totalAmount.stripTrailingZeros().scale() <= 2,
                            "no VAT bucket may carry a sub-cent residue; got ${it.totalAmount}",
                        )
                    }

                    // (3) The bill signs as a Kassenbeleg, and the session is closed behind it.
                    val tseTx = bill.tseTransaction
                    if (tseTx != null) {
                        assertEquals(
                            ProcessType.KASSENBELEG_V1.value,
                            tseTx.processType,
                            "a counter voucher is a Kassenbeleg, never a Bestellung",
                        )
                    }

                    assertTrue(
                        session.completed,
                        "the virtual VOUCHER session must be completed by the bill, otherwise the next " +
                            "counter sale inherits this sale's OPEN item",
                    )
                }
            },
        ) { }

    /**
     * T5-3: the till's voucher dialog shows *Gesamtbetrag = value + tip* and sends `tipAmount`, and the
     * service used to bill only the value — the cashier collected the tip, no bill recorded it, and the
     * drawer came up over by the tip at the cash count. The tip must ride on the bill like a table
     * bill's: `tips` set, one payment covering value + tip, the voucher itself still worth its value.
     */
    @Test
    fun `counter voucher with a tip records the tip on the bill and collects value plus tip`() =
        testApp(
            setupData = {
                val fixture = seedCounterFixture()
                val voucherService = GlobalContext.get().get<VoucherService>()

                val response =
                    runBlocking {
                        voucherService.createVoucher(
                            employeeId = fixture.employeeId,
                            deviceId = deviceId,
                            request =
                                VoucherDto.CreateVoucherRequestDto(
                                    sessionId = null,
                                    originalValue = "50.00",
                                    paymentTypeId = fixture.cashPaymentTypeId,
                                    tipAmount = "5.00",
                                ),
                            actorCapabilities = emptySet(),
                        )
                    }

                assertEquals(0, BigDecimal("50.00").compareTo(BigDecimal(response.originalValue)), "the voucher is worth its value, not value + tip")

                transaction {
                    val orderItem = OrderItem.all().single { it.sideNotes == "VOUCHER: ${response.voucherNumber}" }
                    val bill = TableSession.findById(orderItem.session!!.id)!!.bills.single()

                    assertEquals(0, BigDecimal("50.00").compareTo(bill.billAmount), "billAmount = the voucher value; got ${bill.billAmount}")
                    assertEquals(0, BigDecimal("5.00").compareTo(bill.tips), "the tip must be recorded on the bill; got ${bill.tips}")
                    assertEquals(0, BigDecimal("55.00").compareTo(bill.paidAmount), "paid = value + tip; got ${bill.paidAmount}")
                    val payment = bill.payments.single()
                    assertEquals(0, BigDecimal("55.00").compareTo(payment.amount), "the one cash payment covers value + tip; got ${payment.amount}")
                }
            },
        ) { }
}
