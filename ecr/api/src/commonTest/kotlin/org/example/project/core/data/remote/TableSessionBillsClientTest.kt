package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorBillApiClient
import org.example.project.core.data.remote.impl.KtorTableSessionApiClient
import org.example.project.core.network.HttpCall
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * admin17 FAIL-2: `getBillsByTableSessionId(includeDeleted = true)` never put the flag on the
 * wire, so the session's receipt list lost every Storno'd original. admin17 FAIL-7: the session
 * list must send `limit`/`offset` explicitly, or the server stops at its default of 50.
 */
class TableSessionBillsClientTest {
    private val requested = mutableListOf<Url>()

    private fun httpCall(): HttpCall {
        val engine =
            MockEngine { request ->
                requested += request.url
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val http =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return object : HttpCall {
            override val httpClient: HttpClient = http

            override fun getBaseUrl(): String = "http://localhost/"
        }
    }

    @Test
    fun sessionBillsSendIncludeDeleted() =
        runTest {
            val client = KtorBillApiClient(httpCall())

            client.getBillsByTableSessionId(4786L, includeDeleted = true)
            client.getBillsByTableSessionId(4786L, includeDeleted = false)

            assertEquals("/api/v1/bills/table-session/4786", requested[0].encodedPath)
            assertEquals("true", requested[0].parameters["includeDeleted"])
            assertEquals("false", requested[1].parameters["includeDeleted"])
        }

    @Test
    fun sessionRangeSendsLimitAndOffset() =
        runTest {
            val client = KtorTableSessionApiClient(httpCall())

            client.getAllSessionsInRange(startDate = "2026-09-20T21:58:00", endDate = "2026-09-21T21:47:00")

            assertEquals(1, requested.size, "an empty first page ends the walk")
            assertEquals("/api/tables/sessions", requested[0].encodedPath)
            assertEquals(TABLE_SESSIONS_PAGE_SIZE.toString(), requested[0].parameters["limit"])
            assertEquals("0", requested[0].parameters["offset"])
            assertEquals("2026-09-20T21:58:00", requested[0].parameters["startDate"])
        }
}
