package org.example.project.features.update

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("ClientUpdateRoutes")
class ClientUpdateRoutesTest {
    @Test
    @DisplayName("with nothing staged the metadata answers 404 -- reachable without a token, not 401/403")
    fun `metadata is public and 404 when unstaged`() =
        testApp { client ->
            val response = client.get("/api/update/client/android")
            assertEquals(HttpStatusCode.NotFound, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("with nothing staged the download answers 404 -- reachable without a token")
    fun `download is public and 404 when unstaged`() =
        testApp { client ->
            val response = client.get("/api/update/client/android/download")
            assertEquals(HttpStatusCode.NotFound, response.status, "body: ${response.bodyAsText()}")
        }
}
