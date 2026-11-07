package org.example.project.features.tse.findings

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Route-level tests for GET /api/tse/findings and POST /api/tse/explanations. */
@DisplayName("TSE findings and explanations routes")
class TseExplanationRouteTest {
    private fun setup(): () -> Unit =
        {
            TestData.createShopInfo()
            TestData.createAdmin()
        }

    @Test
    fun `an admin can list findings and add an explanation`() =
        testApp(setupData = setup()) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val list = client.get("/api/tse/findings") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, list.status)

            val created =
                client.post("/api/tse/explanations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            TseExplanationDto.CreateRequestDto.serializer(),
                            TseExplanationDto.CreateRequestDto("AA", 3, 5, TseExplanationCategory.TSE_OUTAGE, "Stromausfall"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)

            // The audit row is written by a direct logTransaction call in the route, which no
            // whitelist covers — so nothing but this assertion would notice it disappearing.
            val audited =
                transaction {
                    TransactionAuditLogTable
                        .selectAll()
                        .where { TransactionAuditLogTable.operation eq "TSE_EXPLANATION_CREATED" }
                        .map { it[TransactionAuditLogTable.entityType] to it[TransactionAuditLogTable.entityName] }
                }
            assertEquals(1, audited.size, "creating an explanation is audited")
            assertEquals("TSE_EXPLANATION", audited.single().first)
            assertEquals("TANR 3-5", audited.single().second)
        }

    @Test
    fun `a malformed date is refused with a 400, not a 500`() =
        testApp(setupData = setup()) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/tse/findings?from=21.09.2026") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `an explanation without text is refused on the server`() =
        testApp(setupData = setup()) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val refused =
                client.post("/api/tse/explanations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            TseExplanationDto.CreateRequestDto.serializer(),
                            TseExplanationDto.CreateRequestDto("AA", 3, 5, TseExplanationCategory.OTHER, "  "),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, refused.status)
        }
}
