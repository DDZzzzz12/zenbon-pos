package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ScheduledJob Routes")
class ScheduledJobRoutesTest {
    // ─────────────────────────────────────────────────────────────────
    // 1. GET /api/scheduled-jobs with MANAGER token → 200 + empty list
    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/scheduled-jobs")
    inner class ListJobs {
        @Test
        fun `returns 200 with empty list for manager`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.get("/api/scheduled-jobs") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(
                    body.contains("[]") || body.trim() == "[]" || body.contains("\"items\":[]").not(),
                    "Expected empty list JSON, got: $body",
                )
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/scheduled-jobs")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        // ─────────────────────────────────────────────────────────────
        // 2. GET /api/scheduled-jobs with CASHIER token → 403
        // ─────────────────────────────────────────────────────────────
        @Test
        fun `returns 403 for cashier role`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/scheduled-jobs") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
    }

    // ─────────────────────────────────────────────────────────────────
    // PUT /{id}/enabled → toggle opt-in jobs, reject config-managed ones
    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/scheduled-jobs/{id}/enabled")
    inner class SetEnabled {
        private suspend fun io.ktor.client.HttpClient.putEnabled(
            id: String,
            enabled: Boolean,
            token: String?,
        ): HttpResponse =
            put("/api/scheduled-jobs/$id/enabled") {
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(
                    testJson.encodeToString(
                        SchedulerDto.SetJobEnabledRequestDto.serializer(),
                        SchedulerDto.SetJobEnabledRequestDto(enabled),
                    ),
                )
            }

        @Test
        fun `manager enables retention job and next run is re-anchored to the future`() {
            lateinit var jobId: String
            val staleNextRun = Instant.now().minusSeconds(30L * 24 * 3600)
            testApp(setupData = {
                TestData.createManager()
                jobId =
                    TestData
                        .createScheduledJob(
                            type = ScheduledJobType.ENTRY_LOG_RETENTION,
                            enabled = false,
                            nextRunAt = staleNextRun,
                        ).id
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.putEnabled(jobId, enabled = true, token = token)
                assertEquals(HttpStatusCode.OK, response.status)
                val dto =
                    testJson.decodeFromString(
                        SchedulerDto.ScheduledJobResponseDto.serializer(),
                        response.bodyAsText(),
                    )
                assertTrue(dto.enabled, "Job should be enabled after PUT")
                assertTrue(
                    dto.nextRunAt > Instant.now().toEpochMilli(),
                    "Enabling must re-anchor nextRunAt to a future instant (was stale: $staleNextRun)",
                )
            }
        }

        @Test
        fun `manager disables an enabled retention job`() {
            lateinit var jobId: String
            testApp(setupData = {
                TestData.createManager()
                jobId =
                    TestData
                        .createScheduledJob(
                            type = ScheduledJobType.ENTRY_LOG_RETENTION,
                            enabled = true,
                        ).id
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.putEnabled(jobId, enabled = false, token = token)
                assertEquals(HttpStatusCode.OK, response.status)
                val dto =
                    testJson.decodeFromString(
                        SchedulerDto.ScheduledJobResponseDto.serializer(),
                        response.bodyAsText(),
                    )
                assertFalse(dto.enabled, "Job should be disabled after PUT")
            }
        }

        @Test
        fun `returns 409 for config-managed BACKUP job`() {
            lateinit var jobId: String
            testApp(setupData = {
                TestData.createManager()
                jobId =
                    TestData
                        .createScheduledJob(
                            type = ScheduledJobType.BACKUP,
                            enabled = false,
                        ).id
                        .toString()
            }) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.putEnabled(jobId, enabled = true, token = token)
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertTrue(
                    response.bodyAsText().contains("SCHEDULED_JOB_CONFIG_MANAGED"),
                    "Expected SCHEDULED_JOB_CONFIG_MANAGED error code, got: ${response.bodyAsText()}",
                )
            }
        }

        @Test
        fun `returns 404 for unknown job id`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.putEnabled(UUID.randomUUID().toString(), enabled = true, token = token)
                assertEquals(HttpStatusCode.NotFound, response.status)
            }

        @Test
        fun `returns 400 for malformed job id`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.putEnabled("not-a-uuid", enabled = true, token = token)
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 403 for cashier role`() {
            lateinit var jobId: String
            testApp(setupData = {
                TestData.createCashier()
                jobId = TestData.createScheduledJob().id.toString()
            }) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.putEnabled(jobId, enabled = true, token = token)
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.putEnabled(UUID.randomUUID().toString(), enabled = true, token = null)
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /types → 200 containing BACKUP
    // ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/scheduled-jobs/types")
    inner class ListTypes {
        @Test
        fun `returns 200 with types list containing BACKUP`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.get("/api/scheduled-jobs/types") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("BACKUP"), "Expected BACKUP in types list, got: $body")
                // The types list is built from getAll<ScheduledJobExecutor>(), so this is also the
                // check that the nightly update job has an executor registered. A scheduled_jobs
                // row naming a type no executor claims does not fail a test -- it fails every night
                // at 03:00, as "no executor registered for type UPDATE_CHECK".
                assertTrue(body.contains("UPDATE_CHECK"), "Expected UPDATE_CHECK in types list, got: $body")
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/scheduled-jobs/types")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
