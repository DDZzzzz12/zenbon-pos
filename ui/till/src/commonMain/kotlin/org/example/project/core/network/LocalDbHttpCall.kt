package org.example.project.core.network

import io.ktor.client.HttpClient
import org.example.project.core.data.database.LocalDatabaseManager

/**
 * composeApp [HttpCall]: base URL comes from the local DB (user-configured server URL).
 */
class LocalDbHttpCall(
    override val httpClient: HttpClient,
    private val localDb: LocalDatabaseManager,
) : HttpCall {
    override fun getBaseUrl(): String = localDb.getApiBaseUrl()
}
