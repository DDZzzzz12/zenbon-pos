package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.models.Items
import org.example.project.core.data.models.Product
import org.example.project.core.data.models.ProductExtra
import org.example.project.core.data.models.SelectedOption
import org.example.project.core.data.models.lineTotalAt
import org.example.project.core.data.models.orderLineTotal
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.TableOrderViewModel
import org.example.project.features.rotation.RotationNextStore
import org.example.project.money.Decimal
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeProductCategoryDataProvider
import org.example.project.testutils.fakes.FakeProductDataProvider
import org.example.project.testutils.fakes.StubChoiceGroupDataProvider
import org.example.project.testutils.fakes.StubClientOrderService
import org.example.project.testutils.fakes.StubRTableDataProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pins the cart list's line totals — the numbers the cashier actually looks at — the way
 * `orderLineTotal` is pinned.
 *
 * [TableOrderViewModel] used to carry its own private `Decimal.timesQuantity(qty)` and set
 * `Items.totalPrice` from it in five places. That was a second copy of the OLD whole-line rounding
 * order, so the per-unit mirror landed on the edit panel while the list stayed a cent adrift. Worse,
 * three of those sites recomputed the total from `unitPrice` alone and dropped the discount
 * entirely: pressing "+" on a 15 %-off line reverted the shown total to the undiscounted amount
 * while `Items.discount` still read `0.85`.
 *
 * Those same three sites then dropped the extras and choice-option surcharges for the same reason —
 * `Items.unitPrice` is the base EXCLUDING them, mirroring the server's `OrderItemTable.unitPrice`,
 * so a recompute has to re-add [org.example.project.core.data.models.extrasDelta] the way
 * `OrderServiceImpl.createOrder` re-derives `extrasDelta` from the submitted selections. The last
 * group of tests pins that; [org.example.project.core.data.models.lineTotalAt] is the one helper
 * all three sites now go through.
 *
 * Every assertion drives the real ViewModel methods the screen calls, so re-introducing the
 * whole-line order, the dropped discount or the dropped extras makes them fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderViewModelLineTotalTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: TableOrderViewModel

    /** Scale 2, the common case: 16.90 x 0.85 = 14.365 -> HALF_UP -> 14.37 per unit. */
    private val listedProduct = product(id = 1L, price = "16.90")

    /** Scale 4, what DECIMAL(19,4) can send: round(16.8950, 2) = 16.90 per unit. */
    private val subCentProduct = product(id = 2L, price = "16.8950")

    /** A round list price, so the option surcharge is the only thing moving the arithmetic. */
    private val tenEuroProduct = product(id = 3L, price = "10.00")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel =
            TableOrderViewModel(
                rTableDataProvider = StubRTableDataProvider(),
                productDataProvider = FakeProductDataProvider(),
                categoryDataProvider = FakeProductCategoryDataProvider(),
                orderService = StubClientOrderService(),
                choiceGroupDataProvider = StubChoiceGroupDataProvider(),
                overlayEventBus = FakeOverlayEventBus(),
                languageManager = LanguageManager(),
                rotationNextStore = RotationNextStore(),
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ----- the discount must survive a quantity change -------------------------------------

    @Test
    fun `increasing the quantity of a discounted line keeps the discount`() =
        runTest {
            viewModel.confirmConfiguredItem(discountedLine(quantity = 1))
            advanceUntilIdle()

            viewModel.increaseQuantity(LINE_ID, quantity = 2)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(3, line.quantity)
            assertEquals(
                "43.11",
                line.totalPrice.toPlainString(),
                "round(16.90 x 0.85, 2) = 14.37 per unit x 3. Recomputing from unitPrice alone gives " +
                    "50.70 — the undiscounted total — while discount still reads 0.85",
            )
            assertEquals(Decimal.parse("0.85"), line.discount, "the multiplier itself must not move")
        }

    @Test
    fun `decreasing the quantity of a discounted line keeps the discount`() =
        runTest {
            viewModel.confirmConfiguredItem(discountedLine(quantity = 3))
            advanceUntilIdle()

            viewModel.decreaseQuantity(LINE_ID)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(2, line.quantity)
            assertEquals("28.74", line.totalPrice.toPlainString(), "14.37 x 2, not 16.90 x 2")
        }

    @Test
    fun `merging a configured item into a discounted line keeps the discount`() =
        runTest {
            // sameConfig() only merges lines whose discount already matches, so the merged total has
            // to carry that discount too.
            viewModel.confirmConfiguredItem(discountedLine(quantity = 1))
            advanceUntilIdle()

            viewModel.confirmConfiguredItem(discountedLine(quantity = 2))
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(3, line.quantity)
            assertEquals("43.11", line.totalPrice.toPlainString(), "14.37 x 3, not 16.90 x 3")
        }

    // ----- the per-unit rounding order ------------------------------------------------------

    @Test
    fun `a fresh line rounds a scale-4 price per unit, as the receipt does`() =
        runTest {
            viewModel.addProduct(subCentProduct)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals("16.90", line.totalPrice.toPlainString())

            // Two units is where the two rounding orders part: per unit 16.90 x 2 = 33.80,
            // whole line 16.8950 x 2 = 33.7900 -> 33.79.
            viewModel.increaseQuantity(line.orderItemIds!!.first())
            advanceUntilIdle()

            assertEquals(
                "33.80",
                viewModel.uiState.value.orderItems
                    .single()
                    .totalPrice
                    .toPlainString(),
            )
        }

    @Test
    fun `the discount total reported to the order summary uses the same rounding order`() =
        runTest {
            // Baseline is the same line at list price and no discount: round(16.8950, 2) x 3 = 50.70.
            // Rounding the whole line instead gives 50.6850 -> 50.69 and understates the discount by
            // a cent. Charged: round(16.8950 x 0.85, 2) = 14.36 per unit x 3 = 43.08.
            viewModel.confirmConfiguredItem(
                discountedLine(product = subCentProduct, quantity = 3, perUnitCharged = "14.36"),
            )
            advanceUntilIdle()

            assertEquals(
                "43.08",
                viewModel.uiState.value.total
                    .toPlainString(),
            )
            assertEquals(
                "7.62",
                viewModel.uiState.value.discount
                    .toPlainString(),
                "50.70 - 43.08",
            )
        }

    @Test
    fun `an undiscounted line reports no discount at all, even at a scale-4 price`() =
        runTest {
            viewModel.addProduct(subCentProduct)
            advanceUntilIdle()
            viewModel.increaseQuantity(
                viewModel.uiState.value.orderItems
                    .single()
                    .orderItemIds!!
                    .first(),
            )
            advanceUntilIdle()

            assertEquals(
                "33.80",
                viewModel.uiState.value.total
                    .toPlainString(),
            )
            assertEquals(
                Decimal.ZERO,
                viewModel.uiState.value.discount,
                "a line at list price must not manufacture a discount out of a rounding mismatch",
            )
        }

    // ----- extras and choice options must survive a quantity change too ----------------------

    @Test
    fun `increasing the quantity of a custom-priced line keeps its choice option`() =
        runTest {
            viewModel.confirmConfiguredItem(customPricedLineWithOption(quantity = 1))
            advanceUntilIdle()

            viewModel.increaseQuantity(LINE_ID, quantity = 2)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(3, line.quantity)
            assertEquals(
                "31.89",
                line.totalPrice.toPlainString(),
                "round((10.00 + 2.50) x 0.85, 2) = 10.63 per unit x 3. Recomputing from unitPrice " +
                    "alone drops the option and gives 25.50",
            )
            assertEquals(
                Decimal.parse("10.00"),
                line.unitPrice,
                "unitPrice stays the extras-EXCLUSIVE base — the server adds extrasDelta to the " +
                    "custom price it is sent, so folding the option in here would charge it twice",
            )
        }

    @Test
    fun `decreasing the quantity of a custom-priced line keeps its choice option`() =
        runTest {
            viewModel.confirmConfiguredItem(customPricedLineWithOption(quantity = 3))
            advanceUntilIdle()

            viewModel.decreaseQuantity(LINE_ID)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(2, line.quantity)
            assertEquals("21.26", line.totalPrice.toPlainString(), "10.63 x 2, not 8.50 x 2")
        }

    @Test
    fun `merging a configured item into a line with a choice option keeps the option`() =
        runTest {
            viewModel.confirmConfiguredItem(customPricedLineWithOption(quantity = 1))
            advanceUntilIdle()

            viewModel.confirmConfiguredItem(customPricedLineWithOption(quantity = 2))
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(3, line.quantity)
            assertEquals("31.89", line.totalPrice.toPlainString(), "10.63 x 3, not 8.50 x 3")
        }

    @Test
    fun `a captured legacy option is priced once, through selectedOptions`() =
        runTest {
            // Flat extras are retired from the order UI, but the server still returns options captured
            // by the old path — as snapshots with no group — and `toItems` maps those into
            // selectedOptions while `Items.extras` keeps the display list. Both therefore describe the
            // SAME 2.50 surcharge on a reloaded line, so extrasDelta must read exactly one of them.
            viewModel.confirmConfiguredItem(lineWithCapturedLegacyOption(quantity = 1))
            advanceUntilIdle()

            viewModel.increaseQuantity(LINE_ID)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals(
                "25.00",
                line.totalPrice.toPlainString(),
                "(10.00 + 2.50) x 2. Counting Items.extras as well would charge the one option " +
                    "twice and give 30.00",
            )
        }

    @Test
    fun `a multi-quantity option is counted per unit, as the server counts it`() =
        runTest {
            // resolveSelections does `total += delta * sel.quantity`; the response snapshot can carry
            // a quantity > 1, so summing the deltas alone would under-charge the preview.
            viewModel.confirmConfiguredItem(
                customPricedLineWithOption(quantity = 1, optionQuantity = 2, discount = Decimal.ONE),
            )
            advanceUntilIdle()

            viewModel.increaseQuantity(LINE_ID)
            advanceUntilIdle()

            val line =
                viewModel.uiState.value.orderItems
                    .single()
            assertEquals("30.00", line.totalPrice.toPlainString(), "(10.00 + 2 x 2.50) x 2")
        }

    @Test
    fun `the order summary's discount counts the option surcharge in its baseline`() =
        runTest {
            viewModel.confirmConfiguredItem(discountedLineWithOption(quantity = 1))
            advanceUntilIdle()

            assertEquals(
                "10.63",
                viewModel.uiState.value.total
                    .toPlainString(),
                "round((10.00 + 2.50) x 0.85, 2)",
            )
            assertEquals(
                "1.87",
                viewModel.uiState.value.discount
                    .toPlainString(),
                "12.50 - 10.63. The baseline has to include the option the charged total includes; " +
                    "product.price alone gives 10.00 - 10.63 = -63, a NEGATIVE saving",
            )
        }

    @Test
    fun `the recompute reproduces exactly what the edit panel stored for the same line`() {
        // The fixture builds `totalPrice` the way PosEditItemPanel does — from its
        // `unitPriceWithExtras` — while `lineTotalAt` rebuilds it from `unitPrice + extrasDelta`.
        // The two must coincide, or the cart drifts the moment anything is edited.
        val saved = customPricedLineWithOption(quantity = 3)

        assertEquals(
            saved.totalPrice,
            saved.lineTotalAt(saved.quantity),
            "unitPrice + extrasDelta must reconstruct the panel's unitPriceWithExtras",
        )
    }

    // ----- fixtures --------------------------------------------------------------------------

    /**
     * A line exactly as `PosEditItemPanel` saves one: [Items.unitPrice] is the undiscounted base
     * and [Items.totalPrice] the charged whole-line amount, with the multiplier carried separately.
     */
    private fun discountedLine(
        product: Product = listedProduct,
        quantity: Int,
        perUnitCharged: String = "14.37",
    ) = Items(
        id = LINE_ID,
        orderItemIds = listOf(LINE_ID),
        product = product,
        unitPrice = product.price,
        // Exact: a 2dp per-unit amount times an integer cannot need rounding.
        totalPrice = Decimal.parse(perUnitCharged) * Decimal.of(quantity),
        discount = Decimal.parse("0.85"),
        quantity = quantity,
    )

    /**
     * A custom-priced line carrying one choice option, in exactly the shape `PosEditItemPanel`
     * saves it: [Items.unitPrice] is the typed custom price with the option NOT folded in — the
     * shape the wire requires, since the server adds its own `extrasDelta` on top of the custom
     * price it is sent — and [Items.totalPrice] is built from the panel's `unitPriceWithExtras`.
     */
    private fun customPricedLineWithOption(
        quantity: Int,
        optionQuantity: Int = 1,
        discount: Decimal = Decimal.parse("0.85"),
    ): Items {
        val base = Decimal.parse("10.00")
        val optionDelta = Decimal.of(OPTION_DELTA_CENTS * optionQuantity, 2)
        return Items(
            id = LINE_ID,
            orderItemIds = listOf(LINE_ID),
            product = listedProduct,
            unitPrice = base,
            // The panel's `unitPriceWithExtras`, not `unitPrice` — deliberately the other route to
            // the same number, so the recompute cannot be checked against its own formula.
            totalPrice = orderLineTotal(base + optionDelta, quantity, discount),
            customPrice = true,
            discount = discount,
            quantity = quantity,
            selectedOptions =
                listOf(
                    SelectedOption(
                        optionId = 7,
                        groupId = 3,
                        name = "Extra shot",
                        priceDelta = Decimal.of(OPTION_DELTA_CENTS, 2),
                        groupName = "Add-ons",
                        quantity = optionQuantity,
                    ),
                ),
        )
    }

    /**
     * A LIST-priced (not custom) discounted line carrying one choice option — the shape whose
     * "you saved" figure the order summary used to measure against a baseline that omitted the
     * option, and so reported as negative.
     */
    private fun discountedLineWithOption(quantity: Int): Items {
        val discount = Decimal.parse("0.85")
        return Items(
            id = LINE_ID,
            orderItemIds = listOf(LINE_ID),
            product = tenEuroProduct,
            unitPrice = tenEuroProduct.price,
            totalPrice =
                orderLineTotal(
                    tenEuroProduct.price + Decimal.of(OPTION_DELTA_CENTS, 2),
                    quantity,
                    discount,
                ),
            discount = discount,
            quantity = quantity,
            selectedOptions =
                listOf(
                    SelectedOption(
                        optionId = 7,
                        groupId = 3,
                        name = "Extra shot",
                        priceDelta = Decimal.of(OPTION_DELTA_CENTS, 2),
                        groupName = "Add-ons",
                    ),
                ),
        )
    }

    /**
     * A reloaded line whose single surcharge was captured by the retired flat-extras path: it
     * arrives in [Items.selectedOptions] as a groupless snapshot (that is what `toItems` builds
     * from the response) AND in [Items.extras] as the display list. One option, described twice.
     */
    private fun lineWithCapturedLegacyOption(quantity: Int): Items {
        val base = tenEuroProduct.price
        return Items(
            id = LINE_ID,
            orderItemIds = listOf(LINE_ID),
            product = tenEuroProduct,
            unitPrice = base,
            totalPrice = orderLineTotal(base + Decimal.of(OPTION_DELTA_CENTS, 2), quantity, Decimal.ONE),
            quantity = quantity,
            selectedOptions =
                listOf(
                    SelectedOption(
                        optionId = 11,
                        // No group: the legacy path never had one, which is also why such a line
                        // cannot be re-submitted — see the groupId snapshot on ItemExtrasTable.
                        groupId = 0,
                        name = "Syrup",
                        priceDelta = Decimal.of(OPTION_DELTA_CENTS, 2),
                        groupName = "",
                    ),
                ),
            extras =
                listOf(
                    ProductExtra(
                        id = 11L,
                        name = "Syrup",
                        description = null,
                        price = Decimal.of(OPTION_DELTA_CENTS, 2),
                        isActive = true,
                    ),
                ),
        )
    }

    private fun product(
        id: Long,
        price: String,
    ) = Product(
        id = id,
        productNumber = "P00$id",
        name = "Product $id",
        description = null,
        price = Decimal.parse(price),
        productType = "FOOD",
        categoryId = 1L,
        isActive = true,
    )

    private companion object {
        const val LINE_ID = 4711L

        /** 2.50 — chosen so 12.50 x 0.85 = 10.625 lands on a HALF_UP tie-break. */
        const val OPTION_DELTA_CENTS = 250L
    }
}
