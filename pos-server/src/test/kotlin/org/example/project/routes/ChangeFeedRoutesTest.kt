package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private val json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

@DisplayName("Change Feed Routes")
class ChangeFeedRoutesTest {
    private suspend fun seedProductLogs() {
        dbQuery {
            EntryLogHelper.logOperation(
                type = ChangeEntityType.PRODUCT,
                operation = LogOperation.CREATE,
                entityId = "prod-1",
                entityName = "PRODUCT-prod-1",
            )
            EntryLogHelper.logOperation(
                type = ChangeEntityType.PRODUCT,
                operation = LogOperation.UPDATE,
                entityId = "prod-1",
                entityName = "PRODUCT-prod-1",
            )
        }
    }

    @Nested
    @DisplayName("GET /api/changes")
    inner class GetChanges {
        @Test
        fun `returns 200 with 2 entries and correct highWaterSeq after seeding 2 PRODUCT rows`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                seedProductLogs()
                val response = client.get("/api/changes?since=0&limit=100") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.decodeFromString<ChangeFeedDto.ResponseDto>(response.bodyAsText())
                assertEquals(2, body.entries.size)
                assertEquals(body.entries.last().seq, body.highWaterSeq)
            }

        @Test
        fun `returns 400 for since=-1`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/changes?since=-1") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for limit=0`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/changes?limit=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/changes?since=0")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns empty response with default params when feed is empty`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/changes") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json.decodeFromString<ChangeFeedDto.ResponseDto>(response.bodyAsText())
                assertEquals(0, body.entries.size)
                assertEquals(0L, body.highWaterSeq)
            }
    }
}
