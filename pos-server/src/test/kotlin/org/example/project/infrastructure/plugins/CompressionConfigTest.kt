package org.example.project.infrastructure.plugins

import io.ktor.client.HttpClient
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises [configureHTTP] in isolation.
 *
 * The full application deliberately skips compression in test mode (ServerManager's
 * `if (enabled)` guard), so this builds a small app that installs nothing but
 * [configureHTTP], with one route per content type whose handling differs.
 *
 * Reading a compressed body IS safe in this harness: Ktor sends a compressed response chunked,
 * so there is no Content-Length for the test engine to mismatch against. That does not
 * necessarily generalise to the full application with every plugin installed, which is what
 * ServerManager's "avoid Content-Length mismatch in Ktor test engine" comment refers to.
 */
@DisplayName("configureHTTP compression")
class CompressionConfigTest {
    /** 3290 bytes (~3.2 KB) — comfortably over the 1 KB minimumSize both encoders enforce. */
    private val payload = buildString { repeat(200) { append("{\"k\":\"value$it\"},") } }

    /** The same payload as bytes, for the routes that respond with a binary body. */
    private val binaryPayload = payload.toByteArray()

    /**
     * A real file on disk, so the image route goes through `respondFile` exactly like
     * ProductRoutes and ShopInfoRoutes do. LocalFileContent infers `image/jpeg` from the
     * extension, and that inferred type is what the exclusion has to match.
     */
    private val jpegFile: File by lazy {
        File.createTempFile("compression-test-", ".jpg").apply {
            writeBytes(binaryPayload)
            deleteOnExit()
        }
    }

    /** A real `.apk` on disk: the till's update download goes through `respondFile`, which infers the type. */
    private val apkFile: File by lazy {
        File.createTempFile("compression-test-", ".apk").apply {
            writeBytes(binaryPayload)
            deleteOnExit()
        }
    }

    private fun compressionApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            application {
                configureHTTP()
                routing {
                    get("/json") { call.respondText(payload, ContentType.Application.Json) }
                    // respondTextWriter, not respondText: both production SSE producers
                    // (NotificationStreamRoutes, CardPaymentSseRoutes) emit WriteChannelContent this
                    // way, so the regression test guards the shape that actually ships.
                    get("/events") {
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) { write(payload) }
                    }
                    get("/zip") { call.respondBytes(binaryPayload, ContentType.Application.Zip) }
                    get("/pdf") { call.respondBytes(binaryPayload, ContentType.Application.Pdf) }
                    get("/webp") { call.respondBytes(binaryPayload, ContentType.Image.WEBP) }
                    get("/apk") { call.respondFile(apkFile) }
                    get("/image-file") { call.respondFile(jpegFile) }
                    get("/csv") { call.respondText(payload, ContentType.Text.CSV) }
                }
            }
            block(client)
        }

    @Test
    fun `gzip is offered again`() =
        compressionApp { client ->
            val response = client.get("/json") { header(HttpHeaders.AcceptEncoding, "gzip") }
            assertEquals(
                "gzip",
                response.headers[HttpHeaders.ContentEncoding],
                "gzip was commented out as 'temporary' in configureHTTP, so clients that offer only " +
                    "gzip (OkHttp's transparent default, HttpURLConnection, some proxies) fell " +
                    "through to an uncompressed body",
            )
        }

    @Test
    fun `deflate is still offered`() =
        compressionApp { client ->
            val response = client.get("/json") { header(HttpHeaders.AcceptEncoding, "deflate") }
            assertEquals(
                "deflate",
                response.headers[HttpHeaders.ContentEncoding],
                "re-enabling gzip must not displace the deflate encoder",
            )
        }

    @Test
    fun `the gzip body on the wire decodes back to the original`() =
        compressionApp { plain ->
            // The magic-byte check is what makes this fail if gzip is ever disabled again: an
            // identity body would round-trip through the decoding client just fine.
            val wire = plain.get("/json") { header(HttpHeaders.AcceptEncoding, "gzip") }.readRawBytes()
            assertEquals(
                listOf(0x1f, 0x8b),
                wire.take(2).map { it.toInt() and 0xff },
                "response claimed Content-Encoding: gzip but the bytes carry no gzip magic header",
            )

            // ContentEncoding negotiates gzip and inflates the response itself.
            val decoding = createClient { install(ContentEncoding) { gzip() } }
            assertEquals(
                payload,
                decoding.get("/json").bodyAsText(),
                "the gzip stream on the wire did not decode back to the original body",
            )
        }

    @Test
    fun `server-sent events are never compressed`() =
        compressionApp { client ->
            val response = client.get("/events") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertNull(
                response.headers[HttpHeaders.ContentEncoding],
                "Ktor does not flush a complete compressed block per SSE event, so compressing " +
                    "text/event-stream truncates the notification stream that carries TSE status pushes",
            )
        }

    @Test
    fun `zip exports are not compressed`() =
        compressionApp { client ->
            val response = client.get("/zip") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertNull(
                response.headers[HttpHeaders.ContentEncoding],
                "ZIP is already deflated; re-compressing the backup and DSFinV-K/DATEV exports burns " +
                    "CPU across the whole (potentially large) body for no meaningful size reduction",
            )
        }

    @Test
    fun `pdf documents are not compressed`() =
        compressionApp { client ->
            val response = client.get("/pdf") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertNull(
                response.headers[HttpHeaders.ContentEncoding],
                "PDF streams carry their own compression, so gzipping Z-Bon and Kassenmeldung " +
                    "documents is wasted work",
            )
        }

    @Test
    fun `images served from disk are not compressed`() =
        compressionApp { client ->
            val response = client.get("/image-file") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertNull(
                response.headers[HttpHeaders.ContentEncoding],
                "product and shop-logo images go out via respondFile; JPEG is already compressed",
            )
        }

    @Test
    fun `webp images are not compressed`() =
        compressionApp { client ->
            val response = client.get("/webp") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertNull(
                response.headers[HttpHeaders.ContentEncoding],
                "the exclusion must be image/* rather than Ktor's default JPEG+PNG pair, because the " +
                    "upload routes also accept webp",
            )
        }

    @Test
    fun `csv exports are still compressed`() =
        compressionApp { client ->
            val response = client.get("/csv") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertEquals(
                "gzip",
                response.headers[HttpHeaders.ContentEncoding],
                "the DATEV Buchungsstapel is text/csv and compresses well — the exclusion list must " +
                    "not have widened into ordinary text payloads",
            )
        }

    @Test
    fun `an excluded payload is served byte-for-byte`() =
        compressionApp { client ->
            val wire = client.get("/zip") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }.readRawBytes()
            assertContentEquals(
                binaryPayload,
                wire,
                "excluding a content type must pass the body through untouched, not merely drop the " +
                    "Content-Encoding header",
            )
        }

    @Test
    fun `the android apk is never compressed`() =
        compressionApp { client ->
            val response = client.get("/apk") { header(HttpHeaders.AcceptEncoding, "gzip, deflate") }
            assertNull(
                response.headers[HttpHeaders.ContentEncoding],
                "a till hashes the APK bytes it receives against the published SHA-256; a gzip body " +
                    "that the client does not inflate fails that check and the update is discarded",
            )
            assertContentEquals(binaryPayload, response.readRawBytes())
        }
}
