package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorChangeFeedApiClient
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.network.LocalDbHttpCall
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeFeedApiClientTest {
    private fun buildClient(
        responseBody: String,
        capturedPath: StringBuilder,
        capturedSince: StringBuilder,
        capturedLimit: StringBuilder,
    ): ChangeFeedApiClient {
        val engine =
            MockEngine { request ->
                capturedPath.append(request.url.encodedPath)
                capturedSince.append(request.url.parameters["since"] ?: "")
                capturedLimit.append(request.url.parameters["limit"] ?: "")
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost:8080"))
        val httpCall = LocalDbHttpCall(httpClient, localDb)
        return KtorChangeFeedApiClient(httpCall)
    }

    @Test
    fun `getChanges sends GET to api-changes with since and limit params and decodes response`() =
        runTest {
            val capturedPath = StringBuilder()
            val capturedSince = StringBuilder()
            val capturedLimit = StringBuilder()
            val body =
                """
                {
                  "entries": [
                    {"seq": 101, "entityType": "PRODUCT", "entityId": "42", "operation": "UPDATE"}
                  ],
                  "highWaterSeq": 101,
                  "minAvailableSeq": 1
                }
                """.trimIndent()

            val client = buildClient(body, capturedPath, capturedSince, capturedLimit)
            val result = client.getChanges(since = 100L, limit = 500)

            assertEquals("/api/changes", capturedPath.toString())
            assertEquals("100", capturedSince.toString())
            assertEquals("500", capturedLimit.toString())

            assertEquals(1, result.entries.size)
            assertEquals(101L, result.highWaterSeq)
            assertEquals(1L, result.minAvailableSeq)
            assertEquals(101L, result.entries[0].seq)
            assertEquals(ChangeEntityType.PRODUCT, result.entries[0].entityType)
            assertEquals("42", result.entries[0].entityId)
            assertEquals(LogOperation.UPDATE, result.entries[0].operation)
        }

    @Test
    fun `getChanges with empty feed decodes to zero entries and highWaterSeq zero`() =
        runTest {
            val capturedPath = StringBuilder()
            val capturedSince = StringBuilder()
            val capturedLimit = StringBuilder()
            val body = """{"entries": [], "highWaterSeq": 0, "minAvailableSeq": 0}"""

            val client = buildClient(body, capturedPath, capturedSince, capturedLimit)
            val result = client.getChanges(since = 0L, limit = 500)

            assertEquals("/api/changes", capturedPath.toString())
            assertEquals("0", capturedSince.toString())

            assertEquals(0, result.entries.size)
            assertEquals(0L, result.highWaterSeq)
        }
}
