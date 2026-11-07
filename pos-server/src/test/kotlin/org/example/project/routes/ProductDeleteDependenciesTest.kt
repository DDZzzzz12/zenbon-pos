package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.FavouriteProductTable
import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DELETE /api/products/{id}` against every table that points at a product (QA admin19 FAIL-B).
 *
 * The test schema comes from Exposed, like a fresh install: its foreign keys carry no
 * `ON DELETE CASCADE`, which is exactly where deleting a product with a choice group used to fail
 * with an FK violation and a 500. Configuration (placements, favourites, an option's product link)
 * is removed with the product; history (an order line, a promotion) turns the delete into a
 * deactivation.
 */
@DisplayName("DELETE /api/products/{id} — dependencies")
class ProductDeleteDependenciesTest {
    private var productId = 0L
    private var groupId = 0
    private var extraId = 0

    private fun seedProduct() {
        TestData.createManager()
        val category = TestData.createCategory()
        productId = TestData.createProduct(category.id.value).id.value
    }

    private fun attachChoiceGroup() =
        transaction {
            groupId =
                ChoiceGroupTable
                    .insertAndGetId {
                        it[name] = "Beilage"
                        it[updatedAt] = currentTimeStamp()
                    }.value
            ProductChoiceGroupTable.insert {
                it[ProductChoiceGroupTable.productId] = this@ProductDeleteDependenciesTest.productId
                it[ProductChoiceGroupTable.groupId] = this@ProductDeleteDependenciesTest.groupId
            }
        }

    private fun attachExtraFavouriteAndLink() =
        transaction {
            extraId =
                ProductExtrasTable
                    .insertAndGetId {
                        it[name] = "Extra Käse"
                        it[price] = BigDecimal("1.5000")
                        it[updatedAt] = currentTimeStamp()
                        it[linkedProductId] = this@ProductDeleteDependenciesTest.productId
                    }.value
            ProductExtrasAssignmentTable.insert {
                it[ProductExtrasAssignmentTable.productId] = this@ProductDeleteDependenciesTest.productId
                it[extrasId] = this@ProductDeleteDependenciesTest.extraId
            }
            FavouriteProductTable.insert {
                it[product] = this@ProductDeleteDependenciesTest.productId
                it[sortingPosition] = 1
            }
        }

    private fun targetWithPromotion(createdBy: UUID) =
        transaction {
            PromotionTable.insert {
                it[name] = "Aktion"
                it[discountType] = DiscountType.FIXED_AMOUNT
                it[discountValue] = BigDecimal("1.0000")
                it[scope] = PromotionScope.ITEM_SPECIFIC
                it[targetProductId] = this@ProductDeleteDependenciesTest.productId
                it[validFrom] = currentTimeStamp()
                it[isActive] = false
                it[PromotionTable.createdBy] = createdBy
                it[createdAt] = currentTimeStamp()
            }
        }

    private fun productRow(): Boolean? = transaction { Product.findById(productId)?.isActive }

    @Test
    fun `hard-deletes a product with a choice group and leaves the group in place`() =
        testApp(setupData = {
            seedProduct()
            attachChoiceGroup()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/products/$productId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            assertNull(productRow(), "product row should be gone")
            transaction {
                assertEquals(0, ProductChoiceGroupTable.selectAll().where { ProductChoiceGroupTable.groupId eq groupId }.count())
                assertNotNull(ChoiceGroupTable.selectAll().where { ChoiceGroupTable.id eq groupId }.singleOrNull())
            }
        }

    /** The PRODUCT/DELETE audit row of this test's product. */
    private fun deleteAuditRow() =
        transaction {
            EntryLogTable
                .selectAll()
                .where {
                    (EntryLogTable.type eq ChangeEntityType.PRODUCT) and
                        (EntryLogTable.operation eq LogOperation.DELETE) and
                        (EntryLogTable.entityId eq productId.toString())
                }.single()
                .let { it[EntryLogTable.entityName] to it[EntryLogTable.message] }
        }

    @Test
    fun `a hard delete is audited under the product's name, not the outcome`() =
        testApp(setupData = { seedProduct() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/products/$productId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertNull(productRow())

            // QA admin21 NOTE-b21: the row read "DELETED / Deleted product 'DELETED'".
            val (entityName, message) = deleteAuditRow()
            assertEquals("Test Product", entityName)
            assertEquals("Deleted product 'Test Product'", message)
        }

    @Test
    fun `a delete that only deactivates says so in the audit row`() =
        testApp(setupData = {
            TestData.createManager()
            val (_, session) = TestData.createTableWithSession()
            val item = TestData.createOrderItemInSession(session, ItemStatus.OPEN)
            productId = transaction { item.product.id.value }
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            client.delete("/api/products/$productId") { bearerAuth(token) }
            val expectedName = transaction { Product.findById(productId)!!.name }
            val (entityName, message) = deleteAuditRow()
            assertEquals(expectedName, entityName)
            assertTrue(message.orEmpty().startsWith("Deactivated product '$expectedName'"), message)
        }

    @Test
    fun `hard-deletes a product with extras, a favourite rank and a linked option`() =
        testApp(setupData = {
            seedProduct()
            attachExtraFavouriteAndLink()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/products/$productId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            assertNull(productRow())
            transaction {
                assertEquals(0, ProductExtrasAssignmentTable.selectAll().count())
                assertEquals(0, FavouriteProductTable.selectAll().count())
                val extra = ProductExtrasTable.selectAll().where { ProductExtrasTable.id eq extraId }.single()
                assertNull(extra[ProductExtrasTable.linkedProductId], "the option survives, unlinked")
            }
        }

    @Test
    fun `deactivates instead when a promotion targets the product`() =
        testApp(setupData = {
            seedProduct()
            attachChoiceGroup()
            val manager =
                transaction {
                    Employee
                        .all()
                        .first()
                        .id.value
                }
            targetWithPromotion(manager)
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/products/$productId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            assertEquals(false, productRow(), "product should be kept, inactive")
            transaction {
                assertEquals(
                    1,
                    ProductChoiceGroupTable.selectAll().where { ProductChoiceGroupTable.productId eq productId }.count(),
                    "a deactivated product keeps its configuration",
                )
            }
        }

    @Test
    fun `deactivates instead when an order line references the product`() =
        testApp(setupData = {
            TestData.createManager()
            val (_, session) = TestData.createTableWithSession()
            val item =
                TestData.createOrderItemInSession(
                    session,
                    ItemStatus.OPEN,
                )
            productId = transaction { item.product.id.value }
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/products/$productId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertFalse(productRow() ?: true, "product should be kept, inactive")
        }

    @Test
    fun `unknown product answers 404`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/products/999999") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        }
}
