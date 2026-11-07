package org.example.project.features.bank

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP surface for our own bank accounts. The 409 test matters for the same reason it did for
 * suppliers: the seeder and an operator can both produce the same IBAN, and an unguarded
 * unique-index violation surfaces as a bare 500.
 */
class BankAccountRoutesTest {
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
    fun `listing accounts requires authentication`() =
        testApp(setupData = { baseData() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bank-accounts").status)
        }

    @Test
    fun `a manager can create and list an account`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created =
                client.post("/api/bank-accounts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            BankAccountDto.CreateRequestDto(
                                iban = "DE02 1203 0000 0000 2020 51",
                                bankName = "DKB",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)

            val listed = client.get("/api/bank-accounts") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, listed.status)
            val body = json.decodeFromString<BankAccountDto.ListResponseDto>(listed.bodyAsText())
            // The shop seeded by TestData may carry an IBAN of its own, so match rather than count.
            val account = body.items.first { it.bankName == "DKB" }
            // Stored canonically: the spacing a bank prints must not create a second account.
            assertEquals("DE02120300000000202051", account.iban)
            assertEquals(0, account.statementCount)
            assertTrue(account.continuityBreaks.isEmpty())
        }

    @Test
    fun `a duplicate IBAN is rejected with 409`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val body =
                json.encodeToString(
                    BankAccountDto.CreateRequestDto(iban = "DE02120300000000202051", bankName = "DKB"),
                )
            assertEquals(
                HttpStatusCode.Created,
                client
                    .post("/api/bank-accounts") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status,
            )

            // Same account, printed the way a bank prints it. The unique index alone would surface
            // as a bare 500.
            val duplicate =
                client.post("/api/bank-accounts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            BankAccountDto.CreateRequestDto(
                                iban = "de02 1203 0000 0000 2020 51",
                                bankName = "Deutsche Kreditbank",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertTrue(duplicate.bodyAsText().contains("BANK_ACCOUNT_IBAN_TAKEN"))
        }

    @Test
    fun `a blank IBAN is rejected with 400`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/bank-accounts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(BankAccountDto.CreateRequestDto(iban = "   ")))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
