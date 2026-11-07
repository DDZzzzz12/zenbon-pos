package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorKassenbuchApiClient
import org.example.project.core.network.HttpCall
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * admin16 NOTE-5: `GET /api/v1/kassenbuch/balance` answered German display text ("5.524,72"); it
 * answers a canonical decimal now. A client can still meet an older server, so the API client must
 * hand its callers the canonical form for both bodies.
 */
class KassenbuchBalanceClientTest {
    private fun clientAnswering(body: String): KtorKassenbuchApiClient {
        val engine =
            MockEngine { _ ->
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain; charset=UTF-8"),
                )
            }
        val http =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return KtorKassenbuchApiClient(
            object : HttpCall {
                override val httpClient: HttpClient = http

                override fun getBaseUrl(): String = "http://localhost/"
            },
        )
    }

    @Test
    fun aCurrentServersDecimalPassesThrough() =
        runTest {
            assertEquals("5524.72", clientAnswering("5524.72").getCurrentBalance())
            assertEquals("5524.72", clientAnswering("5524.72").getCurrentBalanceRaw())
        }

    @Test
    fun anOlderServersGermanDisplayTextIsNormalized() =
        runTest {
            assertEquals("5524.72", clientAnswering("5.524,72").getCurrentBalance(cashBookId = 3L))
            assertEquals("-12.50", clientAnswering("-12,50").getCurrentBalance())
        }
}
