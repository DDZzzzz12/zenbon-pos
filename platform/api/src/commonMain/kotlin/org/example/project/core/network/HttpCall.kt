package org.example.project.core.network

import io.ktor.client.HttpClient

/**
 * Abstraction over the Ktor [HttpClient] plus the resolved API base URL, injected into
 * all API clients. composeApp backs [getBaseUrl] with the local DB; adminApp returns a
 * relative base ("/") because the panel is served from the same origin as pos-server.
 */
interface HttpCall {
    val httpClient: HttpClient

    fun getBaseUrl(): String
}
