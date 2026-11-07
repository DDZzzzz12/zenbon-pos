package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.models.Product
import org.example.project.core.network.error.NetworkApiException
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
 * The grid the order screen opens on. It used to open on favourites when there were some, and on
 * nothing at all otherwise: no tab selected, an empty grid, and a cashier hunting for the tabs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderViewModelDefaultCategoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private val products = FakeProductDataProvider()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        TableOrderViewModel(
            rTableDataProvider = StubRTableDataProvider(),
            productDataProvider = products,
            categoryDataProvider = FakeProductCategoryDataProvider(),
            orderService = StubClientOrderService(),
            choiceGroupDataProvider = StubChoiceGroupDataProvider(),
            overlayEventBus = FakeOverlayEventBus(),
            languageManager = LanguageManager(),
            rotationNextStore = RotationNextStore(),
        )

    private fun product(id: Long) =
        Product(
            id = id,
            productNumber = "P00$id",
            name = "Product $id",
            description = null,
            price = Decimal.parse("1.00"),
            productType = "FOOD",
            categoryId = 1L,
            isActive = true,
        )

    @Test
    fun `with no favourites the order screen opens on all products`() =
        runTest(testDispatcher) {
            products.getAllProductsResult = listOf(product(1L), product(2L))

            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(TableOrderViewModel.ALL_PRODUCTS, state.selectedCategoryId)
            assertEquals(listOf(1L, 2L), state.products.map { it.id })
        }

    @Test
    fun `when favourites fail to load the order screen opens on all products`() =
        runTest(testDispatcher) {
            products.getFavouriteProductIdsThrows = NetworkApiException(IllegalStateException("offline"))
            products.getAllProductsResult = listOf(product(1L))

            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(TableOrderViewModel.ALL_PRODUCTS, state.selectedCategoryId)
            assertEquals(listOf(1L), state.products.map { it.id })
        }

    @Test
    fun `favourites still open first when there are some`() =
        runTest(testDispatcher) {
            products.getFavouriteProductIdsResult = listOf(2L)
            products.getAllProductsResult = listOf(product(1L), product(2L))

            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(TableOrderViewModel.FAVOURITES_CATEGORY_ID, state.selectedCategoryId)
            assertEquals(listOf(2L), state.products.map { it.id })
        }

    @Test
    fun `a category picked while favourites are still loading is kept`() =
        runTest(testDispatcher) {
            products.getProductsByCategoryResult = listOf(product(7L))

            val vm = viewModel()
            vm.selectCategory(5L)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(5L, state.selectedCategoryId)
            assertEquals(listOf(7L), state.products.map { it.id })
        }
}
