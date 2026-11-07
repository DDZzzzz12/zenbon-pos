package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("EmployeeInfo Routes")
class EmployeeInfoRoutesTest {
    /** Id of the employee created by `setupData`, read back when building request bodies. */
    private var createdEmployeeId: String = ""

    @Nested
    @DisplayName("POST /api/employee/info")
    inner class CreateEmployeeInfo {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/employee/info") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/employee/info") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 201 for valid creation`() =
            testApp(
                setupData = {
                    // The service resolves employeeId against a real employee row and 404s if it is
                    // unknown, so the request body must carry the id of an employee that exists.
                    // A hardcoded placeholder UUID never matches the generated admin id.
                    createdEmployeeId =
                        TestData
                            .createAdmin()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val body =
                    """
                    {
                        "employeeId": "$createdEmployeeId",
                        "firstName": "John",
                        "lastName": "Doe"
                    }
                    """.trimIndent()
                assertStatus(HttpStatusCode.Created) {
                    client.post("/api/employee/info") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                }
            }
    }

    @Nested
    @DisplayName("GET /api/employee/info/me")
    inner class GetMyInfo {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employee/info/me")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns response with auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employee/info/me") { bearerAuth(token) }
                // May return 404 if no employee info exists yet
                val validStatuses = setOf(HttpStatusCode.OK, HttpStatusCode.NotFound)
                assert(response.status in validStatuses) {
                    "Expected OK or NotFound but got ${response.status}"
                }
            }
    }

    @Nested
    @DisplayName("PUT /api/employee/info/me")
    inner class UpdateMyInfo {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/employee/info/me") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/employee/info/me") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        /** Creates the caller's own HR record through the API (both FK sides written). */
        private suspend fun createOwnRecord(
            client: io.ktor.client.HttpClient,
            token: String,
        ) {
            val created =
                client.post("/api/employee/info") {
                    bearerAuth(token)
                    setBody(
                        TextContent(
                            """{"employeeId":"$createdEmployeeId","firstName":"Anna","lastName":"Muster","salary":"40000"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }

        @Test
        fun `contact details can be changed by the employee`() =
            testApp(
                setupData = {
                    createdEmployeeId =
                        TestData
                            .createAdmin()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                createOwnRecord(client, token)

                val response =
                    client.put("/api/employee/info/me") {
                        bearerAuth(token)
                        setBody(TextContent("""{"phoneNumber":"+49 30 123456"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("+49 30 123456"))
            }

        /**
         * Self-service must never reach pay or other employment terms — not even for an ADMIN, whose
         * own pay is set through the manager route like anyone else's. The whole body is refused and
         * the stored salary is unchanged.
         */
        @Test
        fun `pay and employment terms are refused with 403 and nothing is written`() =
            testApp(
                setupData = {
                    createdEmployeeId =
                        TestData
                            .createAdmin()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                createOwnRecord(client, token)

                listOf(
                    """{"salary":"90000","phoneNumber":"+49 1"}""",
                    """{"hourlyRate":"50.00"}""",
                    """{"salary":""}""",
                    """{"contractedHours":"40"}""",
                    """{"contractedHoursPeriod":"WEEKLY"}""",
                    """{"jobTitle":"Chef"}""",
                    """{"hireDate":"2020-01-01"}""",
                ).forEach { body ->
                    val response =
                        client.put("/api/employee/info/me") {
                            bearerAuth(token)
                            setBody(TextContent(body, ContentType.Application.Json))
                        }
                    assertEquals(HttpStatusCode.Forbidden, response.status, "body $body: ${response.bodyAsText()}")
                    assertTrue(response.bodyAsText().contains("SELF_MODIFICATION_FORBIDDEN"), response.bodyAsText())
                }

                val reread = client.get("/api/employee/info/me") { bearerAuth(token) }
                val body = reread.bodyAsText()
                assertTrue(body.contains("40000"), body)
                assertTrue(!body.contains("+49 1"), body)
            }
    }

    @Nested
    @DisplayName("GET /api/employee/info/by-employee/{employeeId}")
    inner class GetByEmployeeId {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employee/info/by-employee/00000000-0000-0000-0000-000000000001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employee/info/by-employee/invalid-uuid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/employee/info/by-employee/{employeeId}")
    inner class UpdateByEmployeeId {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/employee/info/by-employee/00000000-0000-0000-0000-000000000001") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/employee/info/by-employee/invalid-uuid") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/employee/info/by-employee/{employeeId}")
    inner class DeleteByEmployeeId {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/employee/info/by-employee/00000000-0000-0000-0000-000000000001")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid UUID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/employee/info/by-employee/invalid-uuid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        /**
         * Regression for the FK ordering bug fixed in V90.
         *
         * Creation writes BOTH sides of the relationship — the service sets
         * `employee.employeeInfo = entity` — so the record must be created through the API here,
         * not hand-inserted, or `employees.info_id` stays null and the delete never touches the
         * constraint that used to reject it. Before the fix this answered 500 and deleted nothing.
         */
        @Test
        fun `returns 200 and removes the record`() =
            testApp(
                setupData = {
                    createdEmployeeId =
                        TestData
                            .createAdmin()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val created =
                    client.post("/api/employee/info") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"employeeId":"$createdEmployeeId","firstName":"Anna","lastName":"Muster"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.Created, created.status)

                val deleted = client.delete("/api/employee/info/by-employee/$createdEmployeeId") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, deleted.status)

                // Gone, and reported as absent rather than as an empty record: this is the state a
                // restored backup without employee_info rows also produces.
                val reread = client.get("/api/employee/info/by-employee/$createdEmployeeId") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, reread.status)
            }

        /**
         * SET NULL, not CASCADE: the route KDoc promises "the login account itself is untouched and
         * the employee can still sign in". A fresh login after the delete is the strongest available
         * proof the account row survived.
         */
        @Test
        fun `leaves the login account intact`() =
            testApp(
                setupData = {
                    createdEmployeeId =
                        TestData
                            .createAdmin()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                client.post("/api/employee/info") {
                    bearerAuth(token)
                    setBody(
                        TextContent(
                            """{"employeeId":"$createdEmployeeId","firstName":"Anna","lastName":"Muster"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
                client.delete("/api/employee/info/by-employee/$createdEmployeeId") { bearerAuth(token) }

                // Throws if the account is gone, so reaching the assertion is itself the check.
                val tokenAfter = TestAuthHelper.loginAsAdmin(client)
                assertTrue(tokenAfter.isNotBlank())
            }

        /** Absence is a supported state, so it is a 404 — not the bare 500 the raw exception gave. */
        @Test
        fun `returns 404 when the employee has no info record`() =
            testApp(
                setupData = {
                    createdEmployeeId =
                        TestData
                            .createAdmin()
                            .id.value
                            .toString()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/employee/info/by-employee/$createdEmployeeId") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/employee/info")
    inner class GetAll {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/employee/info")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employee/info") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports pagination params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/employee/info?limit=10&offset=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }
}
