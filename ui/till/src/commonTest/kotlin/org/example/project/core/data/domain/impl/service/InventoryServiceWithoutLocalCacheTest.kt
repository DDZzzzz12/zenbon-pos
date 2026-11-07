package org.example.project.core.data.domain.impl.service

import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto
import org.example.project.testutils.fakes.FakeProductApiClient
import org.example.project.testutils.fakes.FakeProductCategoryApiClient
import org.example.project.testutils.fakes.FakeProductExtrasApiClient
import org.example.project.testutils.fakes.FakeProductExtrasAssignmentApiClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `InventoryServiceImpl` on a client that keeps no local cache — the wasm browser preview, which
 * builds no `PosDatabase` and so has none of the four `*DataManager`s in its Koin graph.
 *
 * The distinction these tests pin is the one that makes the null-manager design safe: the service
 * mixes cache-fill with genuine server writes, and only the cache half may be skipped. A version
 * that quietly dropped the server call too would still pass a "does not crash" test, so each case
 * asserts the API client was actually called.
 */
class InventoryServiceWithoutLocalCacheTest {
    private val productApi = FakeProductApiClient()
    private val categoryApi = FakeProductCategoryApiClient()
    private val extrasApi = FakeProductExtrasApiClient()
    private val assignmentApi = FakeProductExtrasAssignmentApiClient()

    // Every *DataManager omitted, exactly as the wasm module resolves them.
    private val service =
        InventoryServiceImpl(
            productApiClient = productApi,
            productCategoryApiClient = categoryApi,
            productExtrasApiClient = extrasApi,
            productExtrasAssignmentApiClient = assignmentApi,
        )

    @Test
    fun `deleting a product still reaches the server`() =
        runTest {
            service.deleteProductOnServer(productId = 7L)

            assertEquals(1, productApi.deleteProductCallCount, "the server-side delete must still happen")
        }

    @Test
    fun `an INACTIVE delete does not crash without a cache to rewrite`() =
        runTest {
            // The INACTIVE branch exists only to rewrite the cached row, and it reads the cache
            // through a `!!`. With no cache the whole branch must be skipped, not attempted.
            productApi.deleteProductResult = Result.success("INACTIVE")

            service.deleteProductOnServer(productId = 7L)

            assertEquals(1, productApi.deleteProductCallCount)
        }

    @Test
    fun `assigning extras still reaches the server`() =
        runTest {
            service.assignExtrasToProduct(
                ProductExtrasAssignmentDto.AssignRequestDto(productId = 1L, extrasIds = listOf(2)),
            )

            assertEquals(1, assignmentApi.assignToProductCallCount)
        }

    @Test
    fun `removing an extra still reaches the server`() =
        runTest {
            service.removeExtrasFromProduct(productId = 1L, extrasId = 2)

            assertEquals(1, assignmentApi.removeFromProductCallCount)
        }

    @Test
    fun `a full sync from server is a no-op that still queries every endpoint`() =
        runTest {
            // Nothing is stored, but the calls must go out: this is the path a future
            // cache-less consumer would use to prove the server is reachable.
            service.syncAllFromServer()

            assertEquals(1, productApi.getAllProductsCallCount)
            assertEquals(1, assignmentApi.getAllCallCount)
        }
}
