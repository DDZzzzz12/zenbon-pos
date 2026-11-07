package org.example.project.features.product

import kotlinx.coroutines.runBlocking
import org.example.project.features.product.data.repository.ProductCategoryRepositoryImpl
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `product_category.sort_position` is numeric(8,5): the PFAND system category sits at 999.98, so the
 * auto-assigned `max + 1` must ignore system categories and explicit values must stay in range.
 */
class ProductCategorySortPositionTest {
    private lateinit var repo: ProductCategoryRepositoryImpl

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repo = ProductCategoryRepositoryImpl()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun create(
        name: String,
        sortOrder: BigDecimal?,
    ) = repo.createCategory(null, name, null, null, null, true, sortOrder, "ALL")

    @Test
    fun `auto sort position ignores PFAND and starts at 1`() =
        runBlocking {
            val created =
                dbQuery {
                    create("PFAND", BigDecimal("999.98"))
                    create("Drinks", null)
                }
            assertEquals(0, BigDecimal(1).compareTo(created.sortPosition))
            Unit
        }

    @Test
    fun `auto sort position is max of user categories plus one`() =
        runBlocking {
            val created =
                dbQuery {
                    create("PFAND", BigDecimal("999.98"))
                    create("Food", BigDecimal("5"))
                    create("Drinks", null)
                }
            assertEquals(0, BigDecimal(6).compareTo(created.sortPosition))
            Unit
        }

    @Test
    fun `creating a category succeeds with PFAND and VOUCHER present and follows the last real one`() =
        runBlocking {
            val created =
                dbQuery {
                    create("PFAND", BigDecimal("999.98"))
                    create("VOUCHER", BigDecimal("999.99"))
                    create("Food", BigDecimal(5))
                    create("Drinks", null)
                }
            assertEquals(0, BigDecimal(6).compareTo(created.sortPosition))
            Unit
        }

    @Test
    fun `the automatic position never reaches the system categories`() =
        runBlocking {
            val created =
                dbQuery {
                    create("PFAND", BigDecimal("999.98"))
                    create("Late", BigDecimal("998.5"))
                    create("Later", null)
                }
            assertEquals(0, BigDecimal(998).compareTo(created.sortPosition))
            Unit
        }

    @Test
    fun `explicit out of range sort order is rejected on create`() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { dbQuery { create("Big", BigDecimal(1000)) } }
            Unit
        }

    @Test
    fun `drag reorder rejects an out of range or non finite position`() =
        runBlocking {
            val id = dbQuery { create("Small", BigDecimal(1)) }.id.value.toLong()
            assertFailsWith<IllegalArgumentException> { dbQuery { repo.updateCategorySortPosition(id, 1000.0) } }
            assertFailsWith<IllegalArgumentException> { dbQuery { repo.updateCategorySortPosition(id, Double.NaN) } }
            val moved = dbQuery { repo.updateCategorySortPosition(id, 2.5) }!!
            assertEquals(0, BigDecimal("2.5").compareTo(moved.sortPosition))
            Unit
        }

    @Test
    fun `explicit out of range sort order is rejected on update`() =
        runBlocking {
            val id = dbQuery { create("Small", BigDecimal(1)) }.id.value
            assertFailsWith<IllegalArgumentException> {
                dbQuery { repo.updateCategory(id, "Small", null, true, null, null, BigDecimal(1000), null) }
            }
            Unit
        }
}
