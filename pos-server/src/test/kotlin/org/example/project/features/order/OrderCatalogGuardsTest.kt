package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.data.repository.ChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductExtrasRepositoryImpl
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Server half of two catalogue guards found by the emu19 QA pass, end to end through
 * `OrderService.createOrder`:
 *
 * - **MD-03** — a product with a REQUIRED choice group was booked at its base price when the order
 *   line omitted `selections` altogether (the legacy flat-extras fallback skipped the min check);
 *   `selections: []` was already refused.
 * - **MD-10** — the `VOU` voucher article could be ordered on a normal table and billed as a 0,00
 *   sale with no voucher behind it. Only the voucher sale may put it on a line.
 *
 * Each guard is paired with the path it must leave working.
 */
@DisplayName("Order create: required choice groups and voucher article (MD-03, MD-10)")
class OrderCatalogGuardsTest {
    private val deviceId = "catalog-guard-device"

    private data class Fixture(
        val employeeId: String,
        val tableId: Long,
        val sessionId: Long,
        val categoryId: Int,
    )

    private fun seed(): Fixture {
        val manager = TestData.createManager(employeeNumber = "CG_MGR", username = "cg_mgr")
        val category = TestData.createCategory(name = "CG_Cat")
        TestData.createDefaultVatRates()
        val tws = TestData.createTableWithSession(tableNumber = "CG_TBL")
        transaction {
            Terminal.new {
                this.deviceId = this@OrderCatalogGuardsTest.deviceId
                this.clientId = "CG-CLIENT"
                this.isActive = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }
        return Fixture(manager.id.value.toString(), tws.table.id.value, tws.session.id.value, category.id.value)
    }

    /** Attaches a pick-exactly-one group with one +2,50 option to [productId]; returns (groupId, optionId). */
    private fun attachRequiredGroup(productId: Long): Pair<Int, Int> =
        transaction {
            val groups = ChoiceGroupRepositoryImpl()
            val group = groups.create("CG_Size", null, null, minSelections = 1, maxSelections = 1, isActive = true)
            val option = ProductExtrasRepositoryImpl().createExtra("CG_Large", null, null, BigDecimal("0.0000"), true)
            groups.replaceOptions(
                group.id.value,
                listOf(ChoiceGroupRepository.OptionRow(option.id.value, BigDecimal("2.5000"), 0, false)),
            )
            ProductChoiceGroupRepositoryImpl().replaceForProduct(productId, listOf(group.id.value))
            group.id.value to option.id.value
        }

    private fun order(
        fixture: Fixture,
        item: OrderItemDto.CreateOrderItemRequestDto,
        allowVoucherProduct: Boolean = false,
    ): OrderDto.OrderResponseDto =
        runBlocking {
            GlobalContext.get().get<OrderService>().createOrder(
                request =
                    OrderDto.CreateOrderRequestDto(
                        tableInfoId = fixture.sessionId,
                        tableId = fixture.tableId,
                        orderItems = listOf(item),
                    ),
                employeeId = fixture.employeeId,
                deviceId = deviceId,
                actorCapabilities = emptySet(),
                allowVoucherProduct = allowVoucherProduct,
            )
        }

    @Test
    fun `MD-03 a line omitting selections for a product with a required group is refused`() =
        testApp(
            setupData = {
                val fixture = seed()
                val product =
                    TestData.createProduct(fixture.categoryId, productName = "CG_Pizza", productNumber = "CG_P1", price = BigDecimal("3.3000"))
                attachRequiredGroup(product.id.value)

                val noSelections = assertFailsWith<PosException> { order(fixture, line(product.id.value)) }
                assertEquals(PosErrorCode.CHOICE_SELECTION_INVALID, noSelections.code)
                assertEquals(400, noSelections.httpStatus.value)

                // Legacy flat extras carry no group, so they cannot satisfy the required group either.
                val legacyExtras =
                    assertFailsWith<PosException> {
                        order(fixture, line(product.id.value).copy(extras = listOf(attachRequiredGroupOptionOf(product.id.value))))
                    }
                assertEquals(PosErrorCode.CHOICE_SELECTION_INVALID, legacyExtras.code)
            },
        ) { _ -> }

    @Test
    fun `MD-03 a valid selection and a product without groups still order`() =
        testApp(
            setupData = {
                val fixture = seed()
                val pizza =
                    TestData.createProduct(fixture.categoryId, productName = "CG_Pizza", productNumber = "CG_P1", price = BigDecimal("3.3000"))
                val (groupId, optionId) = attachRequiredGroup(pizza.id.value)
                val plain =
                    TestData.createProduct(fixture.categoryId, productName = "CG_Water", productNumber = "CG_P2", price = BigDecimal("2.0000"))

                val withSelection =
                    order(
                        fixture,
                        line(pizza.id.value).copy(
                            selections = listOf(OrderItemDto.SelectedOptionRequestDto(optionId = optionId, groupId = groupId, quantity = 1)),
                        ),
                    )
                assertEquals(0, BigDecimal("5.80").compareTo(BigDecimal(withSelection.orderTotal)), "3,30 + 2,50")

                // No groups attached: omitting selections is the normal till request and must stay valid.
                val noGroups = order(fixture, line(plain.id.value, unitPrice = "2.00"))
                assertEquals(0, BigDecimal("2.00").compareTo(BigDecimal(noGroups.orderTotal)))
            },
        ) { _ -> }

    @Test
    fun `a line sending both extras and selections is refused, an empty extras list is not`() =
        testApp(
            setupData = {
                val fixture = seed()
                val pizza =
                    TestData.createProduct(fixture.categoryId, productName = "CG_Pizza", productNumber = "CG_P1", price = BigDecimal("3.3000"))
                val (groupId, optionId) = attachRequiredGroup(pizza.id.value)
                val selections = listOf(OrderItemDto.SelectedOptionRequestDto(optionId = optionId, groupId = groupId, quantity = 1))

                // The extras would be silently dropped (selections win), under-pricing the line.
                val both = assertFailsWith<PosException> { order(fixture, line(pizza.id.value).copy(selections = selections, extras = listOf(optionId))) }
                assertEquals(PosErrorCode.VALIDATION_FAILED, both.code)
                assertEquals(400, both.httpStatus.value)

                val emptyExtras = order(fixture, line(pizza.id.value).copy(selections = selections, extras = emptyList()))
                assertEquals(0, BigDecimal("5.80").compareTo(BigDecimal(emptyExtras.orderTotal)), "3,30 + 2,50")
            },
        ) { _ -> }

    @Test
    fun `MD-10 the voucher article is refused as a plain order line`() =
        testApp(
            setupData = {
                val fixture = seed()
                val voucher =
                    TestData.createProduct(
                        fixture.categoryId,
                        productName = "Gutschein",
                        productNumber = "VOU",
                        price = BigDecimal("0.0000"),
                        productType = ProductType.VOUCHER,
                    )

                val refused = assertFailsWith<PosException> { order(fixture, line(voucher.id.value, unitPrice = "0.00")) }
                assertEquals(PosErrorCode.VOUCHER_PRODUCT_NOT_ORDERABLE, refused.code)
                assertEquals(400, refused.httpStatus.value)
            },
        ) { _ -> }

    @Test
    fun `MD-10 the voucher sale's own order path may still book the voucher article`() =
        testApp(
            setupData = {
                val fixture = seed()
                val voucher =
                    TestData.createProduct(
                        fixture.categoryId,
                        productName = "Gutschein",
                        productNumber = "VOU",
                        price = BigDecimal("0.0000"),
                        productType = ProductType.VOUCHER,
                    )

                // ProductType.VOUCHER maps to the 0 % VAT class "C" (taxed at redemption).
                transaction {
                    if (Vat.findById("C") == null) {
                        Vat.new("C") {
                            this.vatClass = "Zero"
                            this.vatRate = BigDecimal("0.0000")
                        }
                    }
                }

                // Exactly what VoucherServiceImpl.createTableSessionVoucher sends.
                val booked =
                    order(
                        fixture,
                        line(voucher.id.value, unitPrice = "0.00").copy(sideNotes = "VOUCHER: V-1"),
                        allowVoucherProduct = true,
                    )
                assertEquals(1, booked.orderItems.size)
            },
        ) { _ -> }

    private fun line(
        productId: Long,
        unitPrice: String = "3.30",
    ) = OrderItemDto.CreateOrderItemRequestDto(productId = productId, unitPrice = unitPrice, quantity = 1)

    /** The option id of the (single) required group attached to [productId]. */
    private fun attachRequiredGroupOptionOf(productId: Long): Int =
        transaction {
            val groupId = ProductChoiceGroupRepositoryImpl().getGroupIdsForProduct(productId).single()
            ChoiceGroupRepositoryImpl()
                .getOptions(groupId)
                .single()[org.example.project.features.product.database.tables.ChoiceGroupOptionTable.optionId]
                .value
        }
}
