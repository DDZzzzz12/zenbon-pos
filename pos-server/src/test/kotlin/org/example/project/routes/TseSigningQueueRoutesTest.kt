package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("TSE Signing Queue Routes — Auth Requirements")
class TseSigningQueueRoutesTest {
    @Nested
    @DisplayName("GET /api/tse/queue/status")
    inner class GetQueueStatus {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/queue/status")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/queue/entries")
    inner class ListEntries {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/queue/entries")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/tse/queue/entries/{id}")
    inner class GetEntry {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/tse/queue/entries/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/queue/entries/{id}/retry")
    inner class RetryEntry {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/queue/entries/1/retry")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/queue/entries/{id}/cancel")
    inner class CancelEntry {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/queue/entries/1/cancel")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/queue/pause")
    inner class PauseService {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/queue/pause")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/queue/resume")
    inner class ResumeService {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/queue/resume")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/queue/trigger")
    inner class TriggerRetry {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/tse/queue/trigger")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/tse/queue/cleanup")
    inner class Cleanup {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/tse/queue/cleanup") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
