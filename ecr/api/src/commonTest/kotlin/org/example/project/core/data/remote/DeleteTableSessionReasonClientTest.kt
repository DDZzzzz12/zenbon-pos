package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorRTableApiClient
import org.example.project.core.network.HttpCall
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cashier's reason for deleting a session must reach the wire as the `reason` query parameter. */
class DeleteTableSessionReasonClientTest {
    private val requested = mutableListOf<Pair<HttpMethod, Url>>()

    private fun httpCall(): HttpCall {
        val engine =
            MockEngine { request ->
                requested += request.method to request.url
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return object : HttpCall {
            override val httpClient: HttpClient = http

            override fun getBaseUrl(): String = "http://localhost/"
        }
    }

    @Test
    fun deleteSessionSendsTheReason() =
        runTest {
            KtorRTableApiClient(httpCall()).deleteTableSession(12L, "Falscher Tisch")

            val (method, url) = requested.single()
            assertEquals(HttpMethod.Delete, method)
            assertEquals("/api/tables/12/session", url.encodedPath)
            assertEquals("Falscher Tisch", url.parameters["reason"])
        }
}
