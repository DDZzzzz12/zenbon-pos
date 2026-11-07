package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F66 check 2: an oversize restore upload is refused with **413** and a sentence naming the limit —
 * v1.0.10's setup restore answered a 110 MB backup with a bare 500 because Ktor's multipart parser
 * threw an IOException at its 50 MB default.
 *
 * Both legs of the refusal are exercised: a declared `Content-Length` over the cap (refused before a
 * byte is read) and a chunked upload with no length (refused while streaming, once the count passes
 * the cap). The cap is the backup configuration's `maxUploadBytes`, lowered to 1 KiB here.
 */
@DisplayName("Restore upload size limit")
class BackupUploadLimitTest {
    private val smallCap = 1024L
    private val oversize = ByteArray(64 * 1024) { 7 }
    private val undersize = ByteArray(512) { 7 }

    /** Past the parser's own bound (cap + 1 MiB of multipart overhead), so Ktor's scanner can trip first. */
    private val farOversize = ByteArray(4 * 1024 * 1024) { 7 }

    @Nested
    @DisplayName("POST /api/v1/backup/restore")
    inner class AuthenticatedRestore {
        private fun adminWithShop() {
            TestData.createAdmin()
            TestData.createShopInfo()
        }

        private suspend fun capped(client: HttpClient): String {
            val token = TestAuthHelper.loginAsAdmin(client)
            val config =
                client.put("/api/v1/backup/config") {
                    bearerAuth(token)
                    setBody(TextContent("""{"autoEnabled":false,"maxUploadBytes":$smallCap}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, config.status, config.bodyAsText())
            return token
        }

        @Test
        fun `a declared oversize upload is refused with 413 naming the limit`() =
            testApp(setupData = { adminWithShop() }) { client ->
                val token = capped(client)
                val response =
                    client.post("/api/v1/backup/restore") {
                        bearerAuth(token)
                        setBody(sizedMultipart(oversize))
                    }
                try {
                    assertTooLarge(response)
                } finally {
                    resetCap()
                }
            }

        @Test
        fun `a chunked oversize upload is refused with 413 while streaming`() =
            testApp(setupData = { adminWithShop() }) { client ->
                val token = capped(client)
                val response =
                    client.post("/api/v1/backup/restore") {
                        bearerAuth(token)
                        setBody(ChunkedMultipart(oversize))
                    }
                try {
                    assertTooLarge(response)
                } finally {
                    resetCap()
                }
            }

        @Test
        fun `an upload under the cap reaches the restore and is judged on its content`() =
            testApp(setupData = { adminWithShop() }) { client ->
                val token = capped(client)
                val response =
                    client.post("/api/v1/backup/restore") {
                        bearerAuth(token)
                        setBody(ChunkedMultipart(undersize))
                    }
                try {
                    assertTrue(response.status.value in 400..499, "${response.status}: ${response.bodyAsText()}")
                    assertTrue(response.status != HttpStatusCode.PayloadTooLarge, response.bodyAsText())
                } finally {
                    resetCap()
                }
            }
    }

    @Nested
    @DisplayName("POST /api/setup/restore-backup")
    inner class SetupRestore {
        /** A shop row and a config row but no admin: still uninitialized, so the route is open. */
        private fun uninitializedWithSmallCap() {
            TestData.createShopInfo()
            writeCap(smallCap)
        }

        @Test
        fun `a declared oversize upload is refused with 413 naming the limit`() =
            testApp(setupData = { uninitializedWithSmallCap() }) { client ->
                val response = client.post("/api/setup/restore-backup") { setBody(sizedMultipart(oversize)) }
                try {
                    assertTooLarge(response)
                } finally {
                    resetCap()
                }
            }

        @Test
        fun `a chunked oversize upload is refused with 413 while streaming`() =
            testApp(setupData = { uninitializedWithSmallCap() }) { client ->
                val response = client.post("/api/setup/restore-backup") { setBody(ChunkedMultipart(oversize)) }
                try {
                    assertTooLarge(response)
                } finally {
                    resetCap()
                }
            }

        /**
         * QA RST-11 (2026-10-06): a chunked upload more than the multipart overhead over the cap made
         * Ktor's boundary scanner — which reads ahead of the copy — trip its own limit first, with
         * "Limit of … bytes exceeded while searching for …". That message did not match the one marker
         * the receiver knew, so the refusal surfaced as a bare 500.
         */
        @Test
        fun `a chunked upload far over the cap is refused with 413 even when the parser trips first`() =
            testApp(setupData = { uninitializedWithSmallCap() }) { client ->
                val response = client.post("/api/setup/restore-backup") { setBody(ChunkedMultipart(farOversize, 1024 * 1024)) }
                try {
                    assertTooLarge(response)
                } finally {
                    resetCap()
                }
            }
    }

    /**
     * `ShopConfigurationManager` is a process-wide cache that outlives each test's database, so the
     * 1 KiB cap is put back to the 2 GiB default before the next test in this JVM can inherit it.
     */
    private fun writeCap(bytes: Long) =
        runBlocking {
            dbQuery {
                ConfigurationRepository().setSetting(
                    shopId = 1L,
                    configKey = ConfigKey.BACKUP_CONFIG,
                    value = """{"maxUploadBytes":$bytes}""",
                )
            }
            ShopConfigurationManager.refreshConfiguration()
        }

    private fun resetCap() = writeCap(2L * 1024 * 1024 * 1024)

    private suspend fun assertTooLarge(response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status, body)
        assertTrue(body.contains("UPLOAD_TOO_LARGE"), body)
        assertTrue(body.contains("1 KB") || body.contains("1024"), "the refusal must name the limit: $body")
    }

    private fun sizedMultipart(bytes: ByteArray): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    "backup",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/zip")
                        append(HttpHeaders.ContentDisposition, "filename=\"pos_backup.zip\"")
                    },
                )
            },
        )

    /** The same multipart body, but streamed with no Content-Length, as a chunked upload arrives. */
    private class ChunkedMultipart(
        private val payload: ByteArray,
        private val writeSize: Int = 4096,
    ) : OutgoingContent.WriteChannelContent() {
        private val boundary = "f66-boundary"

        override val contentType: ContentType =
            ContentType.MultiPart.FormData.withParameter("boundary", boundary)

        override val contentLength: Long? = null

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val head =
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"backup\"; filename=\"pos_backup.zip\"\r\n" +
                    "Content-Type: application/zip\r\n\r\n"
            channel.writeFully(head.encodeToByteArray())
            for (offset in payload.indices step writeSize) {
                channel.writeFully(payload, offset, minOf(offset + writeSize, payload.size))
            }
            channel.writeFully("\r\n--$boundary--\r\n".encodeToByteArray())
        }
    }
}
