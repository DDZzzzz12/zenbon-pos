package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID

/**
 * Drives a sale through the REAL bill service — `createBill` → `deleteBill` — for the Zahlartwechsel
 * tests. Must be used inside a `testApp` block (it resolves [BillService] from Koin and needs the
 * test database).
 */
class RebookFixture(
    private val deviceId: String,
) {
    data class Sale(
        val billId: Long,
        val closerId: String,
        val cashTypeId: Long,
        val cardTypeId: Long,
        val voucherTypeId: Long,
        /** Suffix of this sale's payment-type names: `Bar<n>`, `EC<n>`, `Gutschein<n>`, `Rechnung<n>`. */
        val n: Int,
        /** An "auf Rechnung" type (`createsReceivable`). */
        val receivableTypeId: Long = 0,
    )

    /** Which of the sale's own payment types pays it. */
    enum class Tender { CARD, CASH, VOUCHER, RECEIVABLE }

    /**
     * A sale beyond [closeSale]'s defaults. [paid] defaults to items + tip (set it when a promotion
     * lowers the total); [voucherRedeem] must accompany [Tender.VOUCHER]; [closer] replaces the
     * fixture's own closer (its role still decides the tip recipient).
     */
    data class SaleSpec(
        val recipient: TipRecipient = TipRecipient.EMPLOYEE,
        val tender: Tender = Tender.CARD,
        val items: List<Pair<String, Int>> = listOf("23.00" to 0),
        val originalTip: String = "0.00",
        val paid: String? = null,
        val voucherRedeem: List<VoucherDto.RedeemVoucherRequestDto> = emptyList(),
        val promotionIds: List<Long> = emptyList(),
        val closer: Employee? = null,
        /**
         * ITEM_SPECIFIC promotions: item index into [items] to a factory that creates the promotion for
         * the sale's product (its id) and returns the promotion id.
         */
        val itemPromotions: List<Pair<Int, (productId: Long) -> Long>> = emptyList(),
        /** Deactivates the [tender]'s payment type before the bill is created (MD-06). */
        val tenderInactive: Boolean = false,
    )

    var seq = 0
        private set

    private fun billService(): BillService = GlobalContext.get().get<BillService>()

    /**
     * Closes one bill: [items] (price @ rate index into createDefaultVatRates: 0 = 19 %, 1 = 7 %),
     * an optional [originalTip], all paid by one tender. The tip recipient follows the closer's
     * role exactly as in production (ADMIN → OWNER, anyone else → EMPLOYEE).
     */
    fun closeSale(
        recipient: TipRecipient,
        payByCard: Boolean = true,
        items: List<Pair<String, Int>> = listOf("23.00" to 0),
        originalTip: String = "0.00",
    ): Sale =
        closeSale(
            SaleSpec(
                recipient = recipient,
                tender = if (payByCard) Tender.CARD else Tender.CASH,
                items = items,
                originalTip = originalTip,
            ),
        )

    /** Closes one bill as [spec] describes, through the real `createBill`. */
    fun closeSale(spec: SaleSpec): Sale {
        val n = ++seq
        // One shop per database — SIMULATION signing and the TSE config both read it.
        transaction { ShopInfo.all().firstOrNull() } ?: TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val closer: Employee =
            spec.closer ?: if (spec.recipient == TipRecipient.OWNER) {
                TestData.createAdmin(employeeNumber = "ZWT_A$n", username = "zwt_a$n")
            } else {
                TestData.createManager(employeeNumber = "ZWT_M$n", username = "zwt_m$n")
            }
        val cash = TestData.createPaymentType("Bar$n", isCash = true)
        val card = TestData.createPaymentType("EC$n", isCash = false)
        val voucher = TestData.createPaymentType("Gutschein$n", isCash = false, isVoucher = true)
        val receivable = TestData.createPaymentType("Rechnung$n", isCash = false)
        transaction { PaymentType.findById(receivable.id)!!.createsReceivable = true }
        val tws = TestData.createTableWithSession("ZWT$n")
        val category = TestData.createCategory("ZWT Cat $n")
        val product = TestData.createProduct(category.id.value, "ZWT Product $n", "ZWT_P$n", BigDecimal("1.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZWT")

        val itemIds =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = closer
                session.isTraining = closer.trainingMode
                spec.items.map { (price, vatIndex) ->
                    OrderItem
                        .new {
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal(price)
                            this.finalPrice = BigDecimal(price)
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = vats[vatIndex]
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }.id.value
                }
            }
        val billAmount = spec.items.fold(BigDecimal.ZERO) { acc, (price, _) -> acc + BigDecimal(price) }
        val paid = spec.paid?.let(::BigDecimal) ?: (billAmount + BigDecimal(spec.originalTip))
        val tenderTypeId =
            when (spec.tender) {
                Tender.CARD -> card.id.value
                Tender.CASH -> cash.id.value
                Tender.VOUCHER -> voucher.id.value
                Tender.RECEIVABLE -> receivable.id.value
            }
        if (spec.tenderInactive) transaction { PaymentType.findById(tenderTypeId)!!.isActive = false }
        val billId =
            runBlocking {
                billService()
                    .createBill(
                        employeeId = closer.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = itemIds,
                                billAmount = billAmount.toPlainString(),
                                tips = spec.originalTip,
                                payments = listOf(PaymentDto.PaymentRequest(amount = paid.toPlainString(), paymentTypeId = tenderTypeId)),
                                voucherRedeem = spec.voucherRedeem,
                                promotions =
                                    spec.itemPromotions.map { (index, create) ->
                                        PromotionDto.PromotionApplyRequestDto(promotionId = create(product.id.value), orderItemId = itemIds[index])
                                    } + spec.promotionIds.map { PromotionDto.PromotionApplyRequestDto(promotionId = it) },
                            ),
                    ).billId
            }
        return Sale(billId, closer.id.value.toString(), cash.id.value, card.id.value, voucher.id.value, n, receivable.id.value)
    }

    /** Runs [block] with the shop's TSE in SIMULATION mode, so every Beleg is signed; restores DISABLED after. */
    fun <T> withSimulationTse(block: () -> T): T {
        transaction { ShopInfo.all().firstOrNull() } ?: TestData.createShopInfo()
        runBlocking { ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420")) }
        try {
            return block()
        } finally {
            runBlocking { ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420")) }
        }
    }

    fun storno(sale: Sale) = runBlocking { billService().deleteBill(sale.closerId, deviceId, sale.billId.toString(), "Tip test storno") }

    /**
     * Zahlartwechsel of [sale] by [actorId] (the closer when omitted), through the real service.
     * [held] is what the route would resolve the actor to hold; by default their role's bundle.
     */
    fun rebook(
        sale: Sale,
        request: BillDto.RebookRequestDto,
        actorId: String = sale.closerId,
        held: Set<Capability> = capabilitiesOf(actorId),
    ): BillDto.BillResponseDto = runBlocking { billService().rebookBill(actorId, deviceId, sale.billId.toString(), request, held) }

    /** The capability bundle of [employeeId]'s role — what the route resolves for a seeded group. */
    fun capabilitiesOf(employeeId: String): Set<Capability> = CapabilityBundles.forRole(transaction { Employee.findById(UUID.fromString(employeeId))!!.role })
}
