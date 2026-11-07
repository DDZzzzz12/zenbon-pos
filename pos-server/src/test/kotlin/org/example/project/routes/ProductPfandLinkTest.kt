package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductPfandLinkTest {
    @Test
    fun `product create with pfandTypeId echoes link in response`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                TestData.createCategory("Drinks")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val catBody = client.get("/api/product-categories") { bearerAuth(token) }.bodyAsText()
            val catId =
                (
                    Regex("\"id\":\\s*(\\d+)").find(catBody)
                        ?: error("no category id in: $catBody")
                ).groupValues[1]
                    .toInt()

            // Create a Pfand type (id needed for the link).
            val typeBody =
                client
                    .post("/api/pfand-types") {
                        bearerAuth(token)
                        setBody(
                            TextContent("""{"name":"Einweg 0,25","depositValue":"0.25","vatClass":"A"}""", ContentType.Application.Json),
                        )
                    }.bodyAsText()
            val typeId = Regex("\"id\":\\s*(\\d+)").find(typeBody)!!.groupValues[1].toLong()

            // Create a drink linked to that Pfand type.
            val created =
                client.post("/api/products") {
                    bearerAuth(token)
                    setBody(
                        TextContent(
                            """{"productNumber":"COLA","name":"Cola","description":null,"price":"2.50","categoryId":$catId,"productType":"DRINKS","pfandTypeId":$typeId}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status, "body: ${created.bodyAsText()}")
            val body = created.bodyAsText().replace(" ", "").replace("\n", "")
            assertTrue(body.contains("\"pfandTypeId\":$typeId"), "expected pfandTypeId in $body")
            assertTrue(body.contains("\"pfandTypeName\":\"Einweg0,25\""), "expected pfandTypeName in $body")
            assertTrue(body.contains("\"isDeposit\":false"), "drink itself is not a deposit product: $body")
        }

    @Test
    fun `product create with unknown pfandTypeId is 400`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createCategory("Drinks")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val catId =
                Regex("\"id\":\\s*(\\d+)")
                    .find(client.get("/api/product-categories") { bearerAuth(token) }.bodyAsText())!!
                    .groupValues[1]
                    .toInt()
            val res =
                client.post("/api/products") {
                    bearerAuth(token)
                    setBody(
                        TextContent(
                            """{"productNumber":"COLA","name":"Cola","description":null,"price":"2.50","categoryId":$catId,"productType":"DRINKS","pfandTypeId":9999}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }

    private val idRegex = Regex("\"id\":\\s*(\\d+)")

    private fun idIn(body: String): Long = idRegex.find(body)!!.groupValues[1].toLong()

    /** Creates a Pfand type over the API and returns its id. */
    private suspend fun createPfandType(
        client: HttpClient,
        token: String,
        name: String,
    ): Long =
        idIn(
            client
                .post("/api/pfand-types") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"$name","depositValue":"0.25","vatClass":"A"}""", ContentType.Application.Json))
                }.bodyAsText(),
        )

    private fun productBody(
        number: String,
        catId: Int,
        pfandTypeId: Long?,
        productType: String = "DRINKS",
    ): String =
        """{"productNumber":"$number","name":"$number","description":null,"price":"0.25","categoryId":$catId,""" +
            """"productType":"$productType","isActive":true,"pfandTypeId":${pfandTypeId ?: "null"}}"""

    /**
     * A Pfand type's own deposit product must stay linked to it.
     *
     * The admin product form used to send no `pfandTypeId` at all, so saving the backing product
     * cleared its link; `PfandTypeServiceImpl` then no longer found it to reprice or deactivate.
     */
    @Test
    fun `backing deposit product cannot be unlinked from its Pfand type`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                TestData.createCategory("Drinks")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val typeId = createPfandType(client, token, "Mehrweg")
            val (backingId, backingCat) =
                transaction {
                    Product
                        .find { ProductTable.isDeposit eq true }
                        .first()
                        .let { it.id.value to it.category.id.value }
                }

            val unlinked =
                client.put("/api/products/$backingId") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("PFAND-$typeId", backingCat, null, "OTHERS"), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.BadRequest, unlinked.status, unlinked.bodyAsText())
            val linkAfter = transaction { Product.findById(backingId)?.pfandType }?.id?.value
            assertEquals(typeId, linkAfter, "the link must survive")

            val kept =
                client.put("/api/products/$backingId") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("PFAND-$typeId", backingCat, typeId, "OTHERS"), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, kept.status, kept.bodyAsText())
        }

    /**
     * Keeping a link is not assigning one: a product linked to a since-deactivated type stays
     * editable, while newly pointing a product at an inactive type is still refused.
     */
    @Test
    fun `unchanged link to a deactivated Pfand type is kept, a new one is refused`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createShopInfo()
                TestData.createDefaultVatRates()
                TestData.createCategory("Drinks")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val catId = idIn(client.get("/api/product-categories") { bearerAuth(token) }.bodyAsText()).toInt()
            val typeId = createPfandType(client, token, "Einweg")
            val created =
                client.post("/api/products") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("COLA", catId, typeId), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val colaId = idIn(created.bodyAsText())
            val waterId =
                idIn(
                    client
                        .post("/api/products") {
                            bearerAuth(token)
                            setBody(TextContent(productBody("WATER", catId, null), ContentType.Application.Json))
                        }.bodyAsText(),
                )

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/pfand-types/$typeId") { bearerAuth(token) }.status)

            val kept =
                client.put("/api/products/$colaId") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("COLA", catId, typeId), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, kept.status, kept.bodyAsText())

            val newLink =
                client.put("/api/products/$waterId") {
                    bearerAuth(token)
                    setBody(TextContent(productBody("WATER", catId, typeId), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.BadRequest, newLink.status, newLink.bodyAsText())
        }
}
