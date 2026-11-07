package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.utils.SystemRecords
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The records the system depends on (PFAND/VOUCHER categories, the voucher article, the virtual
 * Gutschein/Schnellverkauf location types, the VOUCHER table) refuse update and delete with 409
 * `SYSTEM_RECORD_PROTECTED` (CLAUDE.md rule 8, server half); ordinary records stay editable.
 */
@DisplayName("System record guards")
class SystemRecordGuardsRoutesTest {
    private fun json(body: String) = TextContent(body, ContentType.Application.Json)

    private suspend fun assertProtected(response: HttpResponse) {
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("SYSTEM_RECORD_PROTECTED"))
    }

    private fun categoryBody(
        name: String,
        isActive: Boolean = true,
    ) = """{"name":"$name","description":null,"isActive":$isActive}"""

    private fun productBody(
        categoryId: Int,
        type: String,
    ) = """{"productNumber":"P1","name":"Renamed","description":null,"price":"1.00","categoryId":$categoryId,"productType":"$type","isActive":true}"""

    @Test
    fun `PFAND and VOUCHER categories refuse rename deactivate and delete`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCategory(SystemRecords.PFAND_CATEGORY_NAME)
                TestData.createCategory(SystemRecords.VOUCHER_CATEGORY_NAME)
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val pfandId = categoryId(SystemRecords.PFAND_CATEGORY_NAME)
            val voucherId = categoryId(SystemRecords.VOUCHER_CATEGORY_NAME)
            for (id in listOf(pfandId, voucherId)) {
                assertProtected(
                    client.put("/api/product-categories/$id") {
                        bearerAuth(token)
                        setBody(json(categoryBody("Renamed")))
                    },
                )
                assertProtected(
                    client.put("/api/product-categories/$id") {
                        bearerAuth(token)
                        setBody(json(categoryBody(if (id == pfandId) "PFAND" else "VOUCHER", isActive = false)))
                    },
                )
                assertProtected(client.delete("/api/product-categories/$id") { bearerAuth(token) })
            }
        }

    @Test
    fun `an ordinary category stays editable and deletable`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCategory("Desserts")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = categoryId("Desserts")
            val updated =
                client.put("/api/product-categories/$id") {
                    bearerAuth(token)
                    setBody(json(categoryBody("Sweets")))
                }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val deleted = client.delete("/api/product-categories/$id") { bearerAuth(token) }
            assertTrue(deleted.status.isSuccess(), deleted.bodyAsText())
        }

    @Test
    fun `an unknown category id still answers 404`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/product-categories/99999") {
                    bearerAuth(token)
                    setBody(json(categoryBody("X")))
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `the voucher product refuses update and delete while an ordinary product does not`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                val voucherCategory = TestData.createCategory(SystemRecords.VOUCHER_CATEGORY_NAME)
                TestData.createProduct(voucherCategory.id.value, "Voucher", "VOU", productType = ProductType.VOUCHER)
                val food = TestData.createCategory("Food")
                TestData.createProduct(food.id.value, "Soup", "SOUP", productType = ProductType.FOOD)
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val voucherCat = categoryId(SystemRecords.VOUCHER_CATEGORY_NAME)
            val foodCat = categoryId("Food")
            val voucherId = productId("VOU")
            val soupId = productId("SOUP")

            assertProtected(
                client.put("/api/products/$voucherId") {
                    bearerAuth(token)
                    setBody(json(productBody(voucherCat, "VOUCHER")))
                },
            )
            assertProtected(client.delete("/api/products/$voucherId") { bearerAuth(token) })

            val updated =
                client.put("/api/products/$soupId") {
                    bearerAuth(token)
                    setBody(json(productBody(foodCat, "FOOD")))
                }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val deleted = client.delete("/api/products/$soupId") { bearerAuth(token) }
            assertTrue(deleted.status.isSuccess(), deleted.bodyAsText())
        }

    @Test
    fun `the virtual location types refuse update and delete and report isSystem`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.ensureLocationTypes()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            for (id in listOf(WellKnownLocationTypes.VOUCHER, WellKnownLocationTypes.QUICK_SALE)) {
                val read = client.get("/api/location-types/$id") { bearerAuth(token) }.bodyAsText()
                assertTrue(read.contains("\"isSystem\":true"), read)
                assertProtected(
                    client.put("/api/location-types/$id") {
                        bearerAuth(token)
                        setBody(json("""{"englishName":"Renamed"}"""))
                    },
                )
                assertProtected(client.delete("/api/location-types/$id") { bearerAuth(token) })
            }
        }

    @Test
    fun `a user-created virtual location type stays editable and deletable`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.ensureLocationTypes()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created =
                client.post("/api/location-types") {
                    bearerAuth(token)
                    setBody(json("""{"name":"Lieferdienst","englishName":"Delivery","chineseName":"配送","isVirtual":true}"""))
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val body = created.bodyAsText()
            assertTrue(body.contains("\"isSystem\":false"), body)
            val id = Regex("\"id\":\\s*(\\d+)").find(body)!!.groupValues[1]
            val updated =
                client.put("/api/location-types/$id") {
                    bearerAuth(token)
                    setBody(json("""{"englishName":"Courier"}"""))
                }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val deleted = client.delete("/api/location-types/$id") { bearerAuth(token) }
            assertTrue(deleted.status.isSuccess(), deleted.bodyAsText())
        }

    @Test
    fun `the system VOUCHER table refuses update and delete while an ordinary table does not`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createTable(SystemRecords.VOUCHER_TABLE_NUMBER, null, WellKnownLocationTypes.VOUCHER, isSystem = true)
            TestData.createTable("T9", 4)
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val voucherTable = tableId(SystemRecords.VOUCHER_TABLE_NUMBER)
            val ordinary = tableId("T9")

            val read = client.get("/api/tables/$voucherTable") { bearerAuth(token) }.bodyAsText()
            assertTrue(read.contains("\"isSystem\":true"), read)
            assertProtected(
                client.put("/api/tables/$voucherTable") {
                    bearerAuth(token)
                    setBody(json("""{"seats":8}"""))
                },
            )
            assertProtected(client.delete("/api/tables/$voucherTable") { bearerAuth(token) })

            val updated =
                client.put("/api/tables/$ordinary") {
                    bearerAuth(token)
                    setBody(json("""{"seats":8}"""))
                }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val deleted = client.delete("/api/tables/$ordinary") { bearerAuth(token) }
            assertTrue(deleted.status.isSuccess(), deleted.bodyAsText())
        }

    @Test
    fun `creating a Pfand type still provisions the PFAND category the guard then protects`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val created =
                client.post("/api/pfand-types") {
                    bearerAuth(token)
                    setBody(json("""{"name":"Einweg 0,25","depositValue":"0.25","vatClass":"A"}"""))
                }
            assertTrue(created.status.isSuccess(), created.bodyAsText())
            val pfandId = categoryId(SystemRecords.PFAND_CATEGORY_NAME)
            assertProtected(client.delete("/api/product-categories/$pfandId") { bearerAuth(token) })
        }

    private suspend fun importCsv(
        client: io.ktor.client.HttpClient,
        token: String,
        path: String,
        csv: String,
    ): String {
        val response =
            client.submitFormWithBinaryData(
                url = path,
                formData =
                    formData {
                        append(
                            "file",
                            csv.toByteArray(),
                            Headers.build {
                                append(HttpHeaders.ContentType, "text/csv")
                                append(HttpHeaders.ContentDisposition, "filename=\"import.csv\"")
                            },
                        )
                    },
            ) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.bodyAsText()
    }

    @Test
    fun `a category CSV import with overwrite skips system categories but updates ordinary ones`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCategory(SystemRecords.PFAND_CATEGORY_NAME)
                TestData.createCategory("Desserts")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val pfandId = categoryId(SystemRecords.PFAND_CATEGORY_NAME)
            val dessertId = categoryId("Desserts")
            val body =
                importCsv(
                    client,
                    token,
                    "/api/product-categories/import-csv/true",
                    "id;name;is_active\n$pfandId;Hacked;false\n$dessertId;Sweets;true\n",
                )
            assertTrue(body.contains("\"skippedCount\":1"), body)
            assertTrue(body.contains("\"successCount\":1"), body)
            assertEquals(SystemRecords.PFAND_CATEGORY_NAME, categoryName(pfandId))
            assertEquals("Sweets", categoryName(dessertId))
        }

    @Test
    fun `a product CSV import with overwrite skips the voucher article but updates ordinary products`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                val voucherCategory = TestData.createCategory(SystemRecords.VOUCHER_CATEGORY_NAME)
                TestData.createProduct(voucherCategory.id.value, "Voucher", "VOU", productType = ProductType.VOUCHER)
                val food = TestData.createCategory("Food")
                TestData.createProduct(food.id.value, "Soup", "SOUP", productType = ProductType.FOOD)
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val voucherCat = categoryId(SystemRecords.VOUCHER_CATEGORY_NAME)
            val foodCat = categoryId("Food")
            val voucherId = productId("VOU")
            val soupId = productId("SOUP")
            val body =
                importCsv(
                    client,
                    token,
                    "/api/products/import-csv/true",
                    "id;product_number;name;price;category_id;product_type\n" +
                        "$voucherId;VOU;Hacked;1.00;$voucherCat;FOOD\n" +
                        "$soupId;SOUP;Stew;2.00;$foodCat;FOOD\n",
                )
            assertTrue(body.contains("\"skippedCount\":1"), body)
            assertTrue(body.contains("\"successCount\":1"), body)
            assertEquals("Voucher", productName(voucherId))
            assertEquals("Stew", productName(soupId))
        }

    @Test
    fun `reordering refuses a system category and reports it as failed`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCategory(SystemRecords.VOUCHER_CATEGORY_NAME)
                TestData.createCategory("Desserts")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = categoryId(SystemRecords.VOUCHER_CATEGORY_NAME)
            val ordinary =
                client.put("/api/product-categories/positions") {
                    bearerAuth(token)
                    setBody(json("""[{"categoryId":${categoryId("Desserts")},"sortOrder":2.0}]"""))
                }
            assertEquals(HttpStatusCode.OK, ordinary.status, ordinary.bodyAsText())
            val response =
                client.put("/api/product-categories/positions") {
                    bearerAuth(token)
                    setBody(json("""[{"categoryId":$id,"sortOrder":1.0}]"""))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

    private fun categoryName(id: Int): String = transaction { ProductCategory.findById(id)!!.name }

    private fun productName(id: Long): String = transaction { Product.findById(id)!!.name }

    private fun categoryId(name: String): Int =
        transaction {
            ProductCategory
                .find { ProductCategoryTable.name eq name }
                .first()
                .id.value
        }

    private fun productId(number: String): Long =
        transaction {
            Product
                .find { ProductTable.productNumber eq number }
                .first()
                .id.value
        }

    private fun tableId(number: String): Long =
        transaction {
            RTable
                .find { RTableTable.tableNumber eq number }
                .first()
                .id.value
        }
}
