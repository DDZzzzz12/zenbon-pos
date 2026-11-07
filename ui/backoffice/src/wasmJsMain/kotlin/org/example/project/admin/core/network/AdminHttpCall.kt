package org.example.project.admin.core.network

import io.ktor.client.HttpClient
import org.example.project.core.network.HttpCall

/**
 * adminApp [HttpCall]: the browser serves the panel from the same origin as pos-server
 * (the dev webpack server proxies /api), so the base URL is relative "/". API clients
 * build paths like "/api/statistics/...".
 */
class AdminHttpCall(
    override val httpClient: HttpClient,
) : HttpCall {
    override fun getBaseUrl(): String = "/"
}
