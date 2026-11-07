package org.example.project.features.contract

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP contract for Verträge.
 *
 * Statuses are asserted exactly rather than as "not 403": a bare `assertNotEquals(Forbidden)` would
 * also be satisfied by a 500 from a broken route, while the exact status proves the request reached
 * the service. An unregistered route is denied by default, so a missing `RoutePermissions` entry
 * shows up here as a 403.
 *
 * There is no DELETE to test — deactivation rides on `PUT` via `isActive`.
 */
@DisplayName("Contract Routes")
class ContractRoutesTest {
    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
        TestData.createCashier()
    }

    private fun createBody(
        name: String = "Miete Ladenlokal",
        dueDay: Int = 1,
    ) = testJson.encodeToString(
        ContractDto.CreateRequestDto(
            name = name,
            interval = ContractInterval.MONTHLY.name,
            dueDay = dueDay,
            startDate = "2026-01-01",
            expectedAmount = "1250.0000",
        ),
    )

    private suspend fun createContract(
        client: HttpClient,
        token: String,
    ): ContractDto.ResponseDto {
        val response =
            client.post("/api/contracts") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(createBody())
            }
        assertEquals(HttpStatusCode.Created, response.status, "creating a contract is a 201")
        return testJson.decodeFromString<ContractDto.ResponseDto>(response.bodyAsText())
    }

    @Test
    fun `the list without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/contracts").status)
        }
    }

    @Test
    fun `the list as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/contracts") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `creating as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val response =
                client.post("/api/contracts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createBody())
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `a manager creates a contract and then reads it back`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created = createContract(client, token)

            val fetched = client.get("/api/contracts/${created.id}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, fetched.status)
            val body = testJson.decodeFromString<ContractDto.ResponseDto>(fetched.bodyAsText())
            assertEquals("Miete Ladenlokal", body.name)
            assertEquals("1250.0000", body.expectedAmount, "money crosses as a scale-preserving string")

            val listed = client.get("/api/contracts") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(1, testJson.decodeFromString<ContractDto.ListResponseDto>(listed.bodyAsText()).total)
        }
    }

    @Test
    fun `a deactivated contract is listed only with includeInactive`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created = createContract(client, token)

            val deactivated =
                client.put("/api/contracts/${created.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            ContractDto.UpdateRequestDto(
                                name = created.name,
                                interval = created.interval,
                                dueDay = created.dueDay,
                                startDate = created.startDate,
                                expectedAmount = created.expectedAmount,
                                isActive = false,
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, deactivated.status)

            val active = client.get("/api/contracts") { bearerAuth(token) }
            assertEquals(0, testJson.decodeFromString<ContractDto.ListResponseDto>(active.bodyAsText()).total)

            val all = client.get("/api/contracts?includeInactive=true") { bearerAuth(token) }
            assertEquals(1, testJson.decodeFromString<ContractDto.ListResponseDto>(all.bodyAsText()).total)
        }
    }

    @Test
    fun `the instalment list of a fresh contract is empty`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val created = createContract(client, token)

            val response = client.get("/api/contracts/${created.id}/instalments") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = testJson.decodeFromString<ContractDto.InstalmentListDto>(response.bodyAsText())
            assertEquals(0, body.total)
            assertTrue(body.items.isEmpty())
        }
    }

    @Test
    fun `the instalment list without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/contracts/1/instalments").status)
        }
    }

    @Test
    fun `a non-numeric id is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/contracts/abc") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/contracts/abc/instalments") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `a bad payload is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/contracts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(dueDay = 32))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a weekly contract without a weekday is rejected`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/contracts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":"Reinigung","interval":"WEEKLY","dueDay":1,"startDate":"2026-03-02"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "dueWeekday")
        }
    }

    @Test
    fun `a monthly contract carrying a weekday is rejected`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/contracts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":"Miete","interval":"MONTHLY","dueDay":1,"dueWeekday":3,"startDate":"2026-03-02"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a weekly contract with a weekday is accepted`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/contracts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":"Reinigung","interval":"WEEKLY","dueDay":1,"dueWeekday":3,"startDate":"2026-03-02"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `a lowercase weekly interval without a weekday is still rejected`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/contracts") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":"Reinigung","interval":"weekly","dueDay":1,"startDate":"2026-03-02"}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "dueWeekday")
        }
    }

    @Test
    fun `an unknown contract is 404`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/contracts/999999") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/contracts/999999/instalments") { bearerAuth(token) }.status,
            )
        }
    }
}
