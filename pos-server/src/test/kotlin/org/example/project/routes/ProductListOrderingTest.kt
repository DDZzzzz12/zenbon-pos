package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.features.product.domain.service.ProductService
import org.example.project.testutils.QueryCounter
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `.sortedBy { it.categoryId }` ran AFTER the repository applied LIMIT/OFFSET, so each page was
 * sorted only inside itself while the page boundary came from an unordered scan. Two pages of a
 * category-interleaved product set therefore read A,B,A,B instead of A,A,B,B — and the boundary
 * was not stable, so a row could be duplicated or skipped between page queries.
 */
@DisplayName("Product list ordering is produced by SQL")
class ProductListOrderingTest {
    @Test
    fun `paging through the product list yields one globally category-ordered sequence`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val catA = TestData.createCategory("Cat A")
                val catB = TestData.createCategory("Cat B")
                // Interleaved insertion order on purpose: A, B, A, B.
                TestData.createProduct(catA.id.value, "Ord 1", "ORD_1")
                TestData.createProduct(catB.id.value, "Ord 2", "ORD_2")
                TestData.createProduct(catA.id.value, "Ord 3", "ORD_3")
                TestData.createProduct(catB.id.value, "Ord 4", "ORD_4")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            suspend fun page(offset: Int): List<ProductDto.ProductResponseDto> {
                val response = client.get("/api/products?limit=2&offset=$offset") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                return testJson.decodeFromString<List<ProductDto.ProductResponseDto>>(response.bodyAsText())
            }

            val paged = page(0) + page(2)

            assertEquals(4, paged.size, "the two pages must together cover all four products")
            assertEquals(
                setOf("ORD_1", "ORD_2", "ORD_3", "ORD_4"),
                paged.map { it.productNumber }.toSet(),
                "paging must neither skip nor duplicate a product; got ${paged.map { it.productNumber }}",
            )
            assertEquals(
                paged.map { it.categoryId }.sorted(),
                paged.map { it.categoryId },
                "categoryId must be ordered by SQL across the whole result set, not inside each page; " +
                    "got ${paged.map { "${it.productNumber}:${it.categoryId}" }}",
            )
            assertEquals(
                listOf("ORD_1", "ORD_3", "ORD_2", "ORD_4"),
                paged.map { it.productNumber },
                "with ORDER BY category_id, id the interleaved set must come back grouped by category " +
                    "and id-ascending inside each category",
            )
        }

    @Test
    fun `the category-visibility product list is category-ordered too`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val catA = TestData.createCategory("Vis A")
                val catB = TestData.createCategory("Vis B")
                TestData.createProduct(catA.id.value, "Vis 1", "VIS_1")
                TestData.createProduct(catB.id.value, "Vis 2", "VIS_2")
                TestData.createProduct(catA.id.value, "Vis 3", "VIS_3")
            },
        ) { client ->
            // The request boots the test application, which is what runs setupData and starts Koin.
            // Asserting inside setupData would be vacuous: nothing forces that block to execute.
            TestAuthHelper.loginAsAdmin(client)

            // No HTTP route reaches this service method yet, so exercise it directly.
            // ProductCategoryTable.visibility defaults to "EVERYWHERE".
            val (products, sql) =
                QueryCounter.record {
                    runBlocking {
                        GlobalContext
                            .get()
                            .get<ProductService>()
                            .getProductsByCategoryVisibility(listOf("EVERYWHERE"))
                    }
                }

            assertEquals(
                listOf("VIS_1", "VIS_3", "VIS_2"),
                products.map { it.productNumber },
                "visibility-scoped products must arrive ordered by category then id; got " +
                    products.map { "${it.productNumber}:${it.categoryId}" },
            )

            assertProductSelectsAreOrdered(sql)
        }

    @Test
    fun `paging one category yields one id-ordered sequence`() {
        var categoryId = 0
        testApp(
            setupData = {
                TestData.createAdmin()
                val cat = TestData.createCategory("Cat Page")
                categoryId = cat.id.value
                (1..4).forEach { TestData.createProduct(cat.id.value, "Cat $it", "CATP_$it") }
            },
        ) { client ->
            TestAuthHelper.loginAsAdmin(client)
            val service = GlobalContext.get().get<ProductService>()

            val (page1, sql) =
                QueryCounter.record {
                    runBlocking { service.getProductsByCategory(categoryId, limit = 2, offset = 0) }
                }
            val page2 = runBlocking { service.getProductsByCategory(categoryId, limit = 2, offset = 2) }

            assertEquals(
                listOf("CATP_1", "CATP_2", "CATP_3", "CATP_4"),
                (page1 + page2).map { it.productNumber },
                "paging one category must neither skip nor duplicate a product",
            )
            assertProductSelectsAreOrdered(sql)
        }
    }

    @Test
    fun `paging by product type yields one category-ordered sequence`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val catA = TestData.createCategory("Type A")
                val catB = TestData.createCategory("Type B")
                // Interleaved on purpose, so an unordered scan and a category-ordered one differ.
                TestData.createProduct(catA.id.value, "Type 1", "TYP_1", productType = ProductType.FOOD)
                TestData.createProduct(catB.id.value, "Type 2", "TYP_2", productType = ProductType.FOOD)
                TestData.createProduct(catA.id.value, "Type 3", "TYP_3", productType = ProductType.FOOD)
                TestData.createProduct(catB.id.value, "Type 4", "TYP_4", productType = ProductType.FOOD)
                // Must never appear: proves the type filter still applies alongside the ordering.
                TestData.createProduct(catA.id.value, "Drink", "TYP_DRINK", productType = ProductType.DRINKS)
            },
        ) { client ->
            TestAuthHelper.loginAsAdmin(client)
            val service = GlobalContext.get().get<ProductService>()

            val (page1, sql) =
                QueryCounter.record {
                    runBlocking { service.getProductsByType(ProductType.FOOD, limit = 2, offset = 0) }
                }
            val page2 = runBlocking { service.getProductsByType(ProductType.FOOD, limit = 2, offset = 2) }
            val paged = page1 + page2

            assertEquals(
                listOf("TYP_1", "TYP_3", "TYP_2", "TYP_4"),
                paged.map { it.productNumber },
                "with ORDER BY category_id, id the interleaved set must come back grouped by category",
            )
            assertEquals(
                paged.map { it.categoryId }.sorted(),
                paged.map { it.categoryId },
                "categoryId must be ordered across the whole result set, not inside each page",
            )
            assertProductSelectsAreOrdered(sql)
        }

    @Test
    fun `paging active products yields one category-ordered sequence`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val catA = TestData.createCategory("Act A")
                val catB = TestData.createCategory("Act B")
                TestData.createProduct(catA.id.value, "Act 1", "ACT_1")
                TestData.createProduct(catB.id.value, "Act 2", "ACT_2")
                TestData.createProduct(catA.id.value, "Act 3", "ACT_3")
                TestData.createProduct(catB.id.value, "Act 4", "ACT_4")
                // Must never appear: proves the isActive filter survives the ordering change.
                TestData.createProduct(catA.id.value, "Gone", "ACT_INACTIVE", isActive = false)
            },
        ) { client ->
            TestAuthHelper.loginAsAdmin(client)
            val service = GlobalContext.get().get<ProductService>()

            val (page1, sql) =
                QueryCounter.record {
                    runBlocking { service.getActiveProducts(limit = 2, offset = 0) }
                }
            val page2 = runBlocking { service.getActiveProducts(limit = 2, offset = 2) }
            val paged = page1 + page2

            assertEquals(
                listOf("ACT_1", "ACT_3", "ACT_2", "ACT_4"),
                paged.map { it.productNumber },
                "active products must page as one category-ordered sequence",
            )
            assertProductSelectsAreOrdered(sql)
        }

    @Test
    fun `paging active products of one category yields one id-ordered sequence`() {
        var categoryId = 0
        testApp(
            setupData = {
                TestData.createAdmin()
                val cat = TestData.createCategory("ActCat")
                categoryId = cat.id.value
                (1..4).forEach { TestData.createProduct(cat.id.value, "ActCat $it", "ACTCAT_$it") }
                TestData.createProduct(cat.id.value, "Gone", "ACTCAT_INACTIVE", isActive = false)
            },
        ) { client ->
            TestAuthHelper.loginAsAdmin(client)
            val service = GlobalContext.get().get<ProductService>()

            val (page1, sql) =
                QueryCounter.record {
                    runBlocking { service.getActiveProductsByCategory(categoryId, limit = 2, offset = 0) }
                }
            val page2 =
                runBlocking {
                    service.getActiveProductsByCategory(categoryId, limit = 2, offset = 2)
                }

            assertEquals(
                listOf("ACTCAT_1", "ACTCAT_2", "ACTCAT_3", "ACTCAT_4"),
                (page1 + page2).map { it.productNumber },
                "active products of one category must neither skip nor duplicate across pages",
            )
            assertProductSelectsAreOrdered(sql)
        }
    }

    /**
     * `offset` was applied as `results.drop(offset)` AFTER the fetch had already been capped at
     * `limit`, so page 2 of a 4-row match set was always empty: 4 number matches -> take(2) ->
     * drop(2) -> nothing. The fetch window has to cover `offset + limit` for the drop to have
     * anything left to hand back.
     */
    @Test
    fun `paging a search past the first page still returns the later matches`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val cat = TestData.createCategory("Search")
                // Numbers match the term; names deliberately do not, keeping the two tiers disjoint.
                (1..4).forEach { TestData.createProduct(cat.id.value, "Item $it", "SRCH_$it") }
            },
        ) { client ->
            TestAuthHelper.loginAsAdmin(client)
            val service = GlobalContext.get().get<ProductService>()

            val (page1, sql) =
                QueryCounter.record {
                    runBlocking { service.searchProductsByName("SRCH", limit = 2, offset = 0, includeInactive = false) }
                }
            val page2 =
                runBlocking {
                    service.searchProductsByName("SRCH", limit = 2, offset = 2, includeInactive = false)
                }

            assertEquals(listOf("SRCH_1", "SRCH_2"), page1.map { it.productNumber }, "first page")
            assertEquals(
                listOf("SRCH_3", "SRCH_4"),
                page2.map { it.productNumber },
                "the second page must hold the remaining matches, not be empty",
            )
            assertProductSelectsAreOrdered(sql)
        }

    @Test
    fun `a search orders both its number tier and its name tier in SQL`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val cat = TestData.createCategory("Tier")
                // Tier 1 — matched on productNumber.
                TestData.createProduct(cat.id.value, "Numbered One", "TIERNUM_1")
                TestData.createProduct(cat.id.value, "Numbered Two", "TIERNUM_2")
                // Tier 2 — matched on name only, so the second SELECT is exercised too.
                TestData.createProduct(cat.id.value, "Tier Alpha", "ZZ_1")
                TestData.createProduct(cat.id.value, "Tier Beta", "ZZ_2")
            },
        ) { client ->
            TestAuthHelper.loginAsAdmin(client)
            val service = GlobalContext.get().get<ProductService>()

            val (found, sql) =
                QueryCounter.record {
                    runBlocking { service.searchProductsByName("TIER", limit = 10, offset = 0, includeInactive = false) }
                }

            assertEquals(
                listOf("TIERNUM_1", "TIERNUM_2", "ZZ_1", "ZZ_2"),
                found.map { it.productNumber },
                "number matches come first, and each tier is id-ordered",
            )
            // Both tiers issue their own SELECT over product; assertProductSelectsAreOrdered requires
            // ORDER BY on every one of them, so a fix that orders only tier 1 still fails here.
            assertProductSelectsAreOrdered(sql)
        }
}

/**
 * The row order alone is NOT enough to prove any of this: H2 answers most of these filters from
 * idx_product_category_active (category_id, is_active) and hands back category-ordered rows even
 * with no ORDER BY at all, so an order-only assertion passes against the unfixed code. Assert the
 * ordering was actually ASKED FOR, or these tests cannot tell a guarantee from a lucky query plan.
 *
 * `\b` keeps this off the CATEGORY / PRODUCT_EXTRAS selects the DTO mapper fires: `_` is a
 * word character, so there is no boundary between PRODUCT and the underscore that follows it.
 */
private fun assertProductSelectsAreOrdered(sql: List<String>) {
    val fromProduct = Regex("""FROM\s+PRODUCT\b""", RegexOption.IGNORE_CASE)
    val productSelects = sql.filter { fromProduct.containsMatchIn(it) }
    assertTrue(
        productSelects.isNotEmpty(),
        "expected a SELECT over product to have been captured; got $sql",
    )
    assertTrue(
        productSelects.all { it.contains("ORDER BY", ignoreCase = true) },
        "the ordering must be in the SQL, not left to the query plan; got $productSelects",
    )
}
