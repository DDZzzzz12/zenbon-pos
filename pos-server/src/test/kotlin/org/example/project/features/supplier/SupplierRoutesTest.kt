package org.example.project.features.supplier

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.error.ProblemDetail
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP surface for creditor master data. `knownIbans` reads a lazily-loaded Exposed relation, so
 * the last test proves the entity→DTO mapping ran while the transaction was still open.
 */
class SupplierRoutesTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun baseData() {
        TestData.createShopInfo()
        TestData.createAdmin()
        TestData.createManager()
    }

    @Test
    fun `listing suppliers requires authentication`() =
        testApp(setupData = { baseData() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/suppliers").status)
        }

    @Test
    fun `a manager can create and then list a supplier`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.CreateRequestDto(name = "Metro AG", vatId = "DE123456789"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)

            val listed = client.get("/api/suppliers") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, listed.status)
            val body = json.decodeFromString<SupplierDto.ListResponseDto>(listed.bodyAsText())
            assertEquals(1, body.total)
            assertEquals("Metro AG", body.items.first().name)
        }

    @Test
    fun `a blank name is rejected with 400`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(SupplierDto.CreateRequestDto(name = "   ")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    /** NEW-20: a supplier's e-mail is the same `business_partner.email` a debtor's is, and gets the same check. */
    @Test
    fun `a malformed e-mail is rejected with 400 and an email field error`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(SupplierDto.CreateRequestDto(name = "Metro AG", email = "kaputt")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val problem = json.decodeFromString(ProblemDetail.serializer(), response.bodyAsText())
            assertEquals(listOf("email" to "INVALID_FORMAT"), problem.errors?.map { it.field to it.code })
            transaction { assertEquals(0L, Supplier.all().count(), "nothing was written") }
        }

    @Test
    fun `a duplicate VAT id is rejected with 409, not 500`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val body =
                json.encodeToString(
                    SupplierDto.CreateRequestDto(name = "Metro AG", vatId = "DE123456789"),
                )
            assertEquals(
                HttpStatusCode.Created,
                client
                    .post("/api/suppliers") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status,
            )

            // The parse path auto-creates creditors, so a manager typing in one that already exists
            // is routine. The unique index alone would surface as a bare 500.
            val duplicate =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.CreateRequestDto(name = "METRO Deutschland", vatId = "DE123456789"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertTrue(duplicate.bodyAsText().contains("SUPPLIER_VAT_ID_TAKEN"))
        }

    @Test
    fun `an update keeps its own VAT id but cannot steal another's`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            suspend fun create(
                name: String,
                vatId: String,
            ): Long =
                json
                    .decodeFromString<SupplierDto.ResponseDto>(
                        client
                            .post("/api/suppliers") {
                                bearerAuth(token)
                                contentType(ContentType.Application.Json)
                                setBody(json.encodeToString(SupplierDto.CreateRequestDto(name = name, vatId = vatId)))
                            }.bodyAsText(),
                    ).id

            val first = create("Metro AG", "DE123456789")
            create("Getränke Müller", "DE222222222")

            // Its own VAT id is unchanged, so the duplicate check must not fire on it.
            val renamed =
                client.put("/api/suppliers/$first") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.UpdateRequestDto(name = "Metro Cash & Carry", vatId = "DE123456789"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, renamed.status)

            val stealing =
                client.put("/api/suppliers/$first") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.UpdateRequestDto(name = "Metro AG", vatId = "DE222222222"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, stealing.status)
        }

    @Test
    fun `an unknown id is 404`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/suppliers/999999") { bearerAuth(token) }.status,
            )
        }

    @Test
    fun `a non-numeric id is 400`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/suppliers/abc") { bearerAuth(token) }.status,
            )
        }

    @Test
    fun `an update stamps updatedAt`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(SupplierDto.CreateRequestDto(name = "Metro AG")))
                }
            val id = json.decodeFromString<SupplierDto.ResponseDto>(created.bodyAsText()).id
            // A row that has never been updated shows null — that is what createdAt is for.
            assertEquals(null, transaction { Supplier.findById(id)!!.updatedAt })

            val updated =
                client.put("/api/suppliers/$id") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(SupplierDto.UpdateRequestDto(name = "Metro Cash & Carry")))
                }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertNotNull(transaction { Supplier.findById(id)!!.updatedAt })
        }

    @Test
    fun `the SEPA creditor id round-trips through the PUT, and an absent one stays null`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(SupplierDto.CreateRequestDto(name = "Metro AG")))
                }
            val body = json.decodeFromString<SupplierDto.ResponseDto>(created.bodyAsText())
            // Not sent, so not invented: an empty string would look like a recorded identifier and
            // would make signal 3 fire on a transaction carrying no creditor id at all.
            assertNull(body.sepaCreditorId, "an absent identifier must stay null, not become \"\"")

            val updated =
                client.put("/api/suppliers/${body.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.UpdateRequestDto(name = "Metro AG", sepaCreditorId = "DE98ZZZ09999999999"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals(
                "DE98ZZZ09999999999",
                json.decodeFromString<SupplierDto.ResponseDto>(updated.bodyAsText()).sepaCreditorId,
            )

            val fetched = client.get("/api/suppliers/${body.id}") { bearerAuth(token) }
            assertEquals(
                "DE98ZZZ09999999999",
                json.decodeFromString<SupplierDto.ResponseDto>(fetched.bodyAsText()).sepaCreditorId,
            )

            val cleared =
                client.put("/api/suppliers/${body.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.UpdateRequestDto(name = "Metro AG", sepaCreditorId = "   "),
                        ),
                    )
                }
            assertNull(
                json.decodeFromString<SupplierDto.ResponseDto>(cleared.bodyAsText()).sepaCreditorId,
                "a blank is the same statement as no identifier recorded",
            )
        }

    @Test
    fun `known IBANs survive the response mapping`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            SupplierDto.CreateRequestDto(name = "Metro AG", vatId = "DE123456789"),
                        ),
                    )
                }
            val id = json.decodeFromString<SupplierDto.ResponseDto>(created.bodyAsText()).id

            val fetched = client.get("/api/suppliers/$id") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, fetched.status)
            // The list is empty for a hand-created supplier, but reading it at all proves the
            // mapper ran inside the transaction — outside it, the lazy relation throws.
            assertTrue(
                json.decodeFromString<SupplierDto.ResponseDto>(fetched.bodyAsText()).knownIbans.isEmpty(),
            )
        }

    // FO-03: the adminApp hides the supplier save/confirm below CREDITOR_WRITE; the routes must refuse too.
    @Test
    fun `a cashier cannot create or update a supplier`() =
        testApp(setupData = {
            baseData()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            for (
            response in
            listOf(
                client.post("/api/suppliers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                },
                client.put("/api/suppliers/1") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                },
            )
            ) {
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }
}
