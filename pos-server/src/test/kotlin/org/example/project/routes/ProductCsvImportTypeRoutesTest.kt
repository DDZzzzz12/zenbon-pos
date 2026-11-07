package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A product CSV row must name its `product_type`: the type drives the VAT/DSFinV-K mapping, so a
 * missing one is refused per row instead of silently becoming FOOD (consistent with FO-54, where the
 * product editor requires an explicit type).
 */
@DisplayName("Product CSV import: product_type is required")
class ProductCsvImportTypeRoutesTest {
    private suspend fun importCsv(
        client: HttpClient,
        token: String,
        csv: String,
    ): String {
        val response =
            client.submitFormWithBinaryData(
                url = "/api/products/import-csv/false",
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
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAsText()
    }

    @Test
    fun `rows without a product_type or with an unknown one are refused, typed rows import`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                TestData.createCategory("Getränke")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val catId = categoryId("Getränke")
            val body =
                importCsv(
                    client,
                    token,
                    "product_number;name;price;category_id;product_type\n" +
                        "COLA;Cola;2.50;$catId;drinks\n" +
                        "WATER;Wasser;2.00;$catId;\n" +
                        "JUICE;Saft;3.00;$catId;JUICE\n",
                )

            assertTrue(body.contains("\"successCount\":1"), body)
            assertTrue(body.contains("\"errorCount\":2"), body)
            assertTrue(body.contains("Row 3: Missing 'product_type'"), body)
            assertTrue(body.contains("Row 4: Invalid 'product_type' value 'JUICE'"), body)
            assertEquals(ProductType.DRINKS, productType("COLA"))
            assertNull(productTypeOrNull("WATER"), "an untyped row must not be created as FOOD")
            assertNull(productTypeOrNull("JUICE"))
        }

    @Test
    fun `a file without the product_type column refuses every row`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                TestData.createCategory("Speisen")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val catId = categoryId("Speisen")
            val body =
                importCsv(
                    client,
                    token,
                    "product_number;name;price;category_id\nSOUP;Suppe;4.50;$catId\n",
                )

            assertTrue(body.contains("\"successCount\":0"), body)
            assertTrue(body.contains("\"errorCount\":1"), body)
            assertTrue(body.contains("Missing 'product_type'"), body)
            assertNull(productTypeOrNull("SOUP"))
        }

    private fun categoryId(name: String): Int =
        transaction {
            ProductCategory
                .find { ProductCategoryTable.name eq name }
                .first()
                .id.value
        }

    private fun productType(number: String): ProductType = productTypeOrNull(number)!!

    private fun productTypeOrNull(number: String): ProductType? =
        transaction {
            Product
                .find { ProductTable.productNumber eq number }
                .firstOrNull()
                ?.productType
        }
}
