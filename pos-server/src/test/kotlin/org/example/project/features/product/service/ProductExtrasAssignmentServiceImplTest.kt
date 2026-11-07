package org.example.project.features.product.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.data.repository.ProductExtrasAssignmentRepositoryImpl
import org.example.project.features.product.data.service.ProductExtrasAssignmentServiceImpl
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.product.domain.models.ProductExtras
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.stopKoin
import java.math.BigDecimal
import kotlin.test.assertEquals

@DisplayName("ProductExtrasAssignmentServiceImpl")
class ProductExtrasAssignmentServiceImplTest {
    private lateinit var service: ProductExtrasAssignmentServiceImpl

    /**
     * Acting employee id for the service calls below.
     *
     * Must be a real employee UUID: the service routes through
     * `ExceptionConverter.catchingThrows(entryLogConfig = ...)`, and
     * `EntryLogHelper.logOperation` calls `UUID.fromString(userId)` unguarded — a
     * non-UUID placeholder makes every call throw `IllegalArgumentException`.
     */
    private lateinit var employeeId: String

    @BeforeEach
    fun setup() {
        stopKoin()
        TestDatabase.setup()
        employeeId =
            TestData
                .createAdmin()
                .id.value
                .toString()
        val repository = ProductExtrasAssignmentRepositoryImpl()
        service = ProductExtrasAssignmentServiceImpl(repository)
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun createExtras(
        name: String = "Extra 1",
        price: BigDecimal = BigDecimal("1.5000"),
    ): ProductExtras =
        transaction {
            ProductExtras.new {
                this.name = name
                this.price = price
                this.isActive = true
                this.updatedAt = currentTimeStamp()
            }
        }

    private fun createCategoryAndProduct(
        categoryName: String = "Test Category",
        productName: String = "Test Product",
        productNumber: String = "PRD001",
    ): Pair<ProductCategory, Product> {
        val cat = TestData.createCategory(name = categoryName)
        val product =
            TestData.createProduct(
                categoryId = cat.id.value,
                productName = productName,
                productNumber = productNumber,
            )
        return cat to product
    }

    @Nested
    @DisplayName("assignExtrasToProduct")
    inner class AssignExtrasToProduct {
        @Test
        fun `assign single extra to product creates row`() =
            runBlocking {
                val (_, product) = createCategoryAndProduct()
                val extras = createExtras()

                val data = service.assignExtrasToProduct(employeeId, product.id.value, listOf(extras.id.value))

                assertEquals(1, data.size)
                assertEquals(product.id.value, data[0].productId)
                assertEquals(extras.id.value, data[0].extrasId)
            }

        @Test
        fun `assign duplicate extra is idempotent`() =
            runBlocking {
                val (_, product) = createCategoryAndProduct()
                val extras = createExtras()

                service.assignExtrasToProduct(employeeId, product.id.value, listOf(extras.id.value))
                val data = service.assignExtrasToProduct(employeeId, product.id.value, listOf(extras.id.value))

                // Second assignment returns empty list since all already existed
                assertEquals(0, data.size)

                // Verify only one row in the table
                val count =
                    transaction {
                        ProductExtrasAssignmentTable.selectAll().count()
                    }
                assertEquals(1, count)
            }
    }

    @Nested
    @DisplayName("removeExtrasFromProduct")
    inner class RemoveExtrasFromProduct {
        @Test
        fun `remove extra from product deletes row`() =
            runBlocking {
                val (_, product) = createCategoryAndProduct()
                val extras = createExtras()

                service.assignExtrasToProduct(employeeId, product.id.value, listOf(extras.id.value))
                service.removeExtrasFromProduct(employeeId, product.id.value, extras.id.value)

                val count =
                    transaction {
                        ProductExtrasAssignmentTable.selectAll().count()
                    }
                assertEquals(0, count)
            }

        @Test
        fun `remove non-existent assignment is a 404`() {
            val (_, product) = createCategoryAndProduct()
            val extras = createExtras()

            // An unknown assignment is a not-found, never a 500 (emu20 FAIL-A).
            val error =
                assertThrows<PosException> {
                    runBlocking {
                        service.removeExtrasFromProduct(employeeId, product.id.value, extras.id.value)
                    }
                }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    @Nested
    @DisplayName("bulkAssignExtrasToCategory")
    inner class BulkAssignExtrasToCategory {
        @Test
        fun `bulk assign creates rows for all products in category`() =
            runBlocking {
                val cat = TestData.createCategory(name = "Bulk Cat")
                TestData.createProduct(categoryId = cat.id.value, productName = "P1", productNumber = "B001")
                TestData.createProduct(categoryId = cat.id.value, productName = "P2", productNumber = "B002")
                val extras = createExtras()

                val data = service.bulkAssignExtrasToCategory(employeeId, cat.id.value, listOf(extras.id.value))

                assertEquals(2, data.totalProducts)
                assertEquals(2, data.assigned)
                assertEquals(0, data.skipped)
            }

        @Test
        fun `bulk assign skips existing individual assignments`() =
            runBlocking {
                val cat = TestData.createCategory(name = "Bulk Cat")
                val p1 = TestData.createProduct(categoryId = cat.id.value, productName = "P1", productNumber = "B001")
                TestData.createProduct(categoryId = cat.id.value, productName = "P2", productNumber = "B002")
                val extras = createExtras()

                // Pre-assign to p1
                service.assignExtrasToProduct(employeeId, p1.id.value, listOf(extras.id.value))

                val data = service.bulkAssignExtrasToCategory(employeeId, cat.id.value, listOf(extras.id.value))

                assertEquals(2, data.totalProducts)
                assertEquals(1, data.assigned)
                assertEquals(1, data.skipped)
            }

        @Test
        fun `bulk assign to empty category succeeds with zero counts`() =
            runBlocking {
                val cat = TestData.createCategory(name = "Empty Cat")
                val extras = createExtras()

                val data = service.bulkAssignExtrasToCategory(employeeId, cat.id.value, listOf(extras.id.value))

                assertEquals(0, data.totalProducts)
                assertEquals(0, data.assigned)
                assertEquals(0, data.skipped)
            }
    }

    @Nested
    @DisplayName("bulkRemoveExtrasFromCategory")
    inner class BulkRemoveExtrasFromCategory {
        @Test
        fun `bulk remove deletes all matching rows`() =
            runBlocking {
                val cat = TestData.createCategory(name = "Bulk Cat")
                val p1 = TestData.createProduct(categoryId = cat.id.value, productName = "P1", productNumber = "B001")
                val p2 = TestData.createProduct(categoryId = cat.id.value, productName = "P2", productNumber = "B002")
                val extras = createExtras()

                service.assignExtrasToProduct(employeeId, p1.id.value, listOf(extras.id.value))
                service.assignExtrasToProduct(employeeId, p2.id.value, listOf(extras.id.value))

                service.bulkRemoveExtrasFromCategory(employeeId, cat.id.value, extras.id.value)

                val count =
                    transaction {
                        ProductExtrasAssignmentTable.selectAll().count()
                    }
                assertEquals(0, count)
            }

        @Test
        fun `bulk remove where some products lack the extra succeeds`() =
            runBlocking {
                val cat = TestData.createCategory(name = "Bulk Cat")
                val p1 = TestData.createProduct(categoryId = cat.id.value, productName = "P1", productNumber = "B001")
                TestData.createProduct(categoryId = cat.id.value, productName = "P2", productNumber = "B002")
                val extras = createExtras()

                // Only assign to p1
                service.assignExtrasToProduct(employeeId, p1.id.value, listOf(extras.id.value))

                service.bulkRemoveExtrasFromCategory(employeeId, cat.id.value, extras.id.value)

                val count =
                    transaction {
                        ProductExtrasAssignmentTable.selectAll().count()
                    }
                assertEquals(0, count)
            }
    }

    @Nested
    @DisplayName("getAssignmentsUpdatedAfter")
    inner class SyncEndpoint {
        @Test
        fun `returns only assignments updated after lastSyncTime`() =
            runBlocking {
                val (_, product) = createCategoryAndProduct()
                val extras1 = createExtras(name = "Extra 1")
                val extras2 = createExtras(name = "Extra 2")

                // Create assignments
                service.assignExtrasToProduct(employeeId, product.id.value, listOf(extras1.id.value, extras2.id.value))

                // Query with a time far in the past — should return all
                val data = service.getAssignmentsUpdatedAfter("2020-01-01T00:00:00")
                assertEquals(2, data.totalCount)

                // Query with a time in the future — should return none
                val futureData = service.getAssignmentsUpdatedAfter("2099-01-01T00:00:00")
                assertEquals(0, futureData.totalCount)
            }
    }
}
