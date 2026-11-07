package org.example.project.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cache policy of the web admin panel served at `/admin/`: content-hashed `.wasm` chunks are
 * cached publicly for a year, everything else is `no-cache`.
 *
 * Every file carries a content-hash `ETag` (`StaticResourceValidators`) and `ConditionalHeaders`
 * answers a matching `If-None-Match` with 304, so `no-cache` means a cheap revalidation, not a
 * full download. The non-wasm files include chunks with stable names (the lazily loaded PDF.js
 * chunks such as `604.js`, which carry the worker), and `no-cache` is what keeps a browser from
 * holding on to an old PDF.js worker after an upgrade — PDF.js's API/worker version check would
 * then fail every PDF. The files served here are the stubs in `src/test/resources/admin/`;
 * the real bundle is only on the classpath of a deployment build (`-PbundleAdminPanel=true`).
 */
class AdminWebCacheTest {
    @Test
    fun `index html is no-cache`() =
        testApp { client ->
            val response = client.get("/admin/index.html")
            assertEquals(HttpStatusCode.OK, response.status)
            val cacheControl = response.headers[HttpHeaders.CacheControl].orEmpty()
            assertTrue(cacheControl.contains("no-cache"), "index.html Cache-Control was: $cacheControl")
        }

    @Test
    fun `a stably named js chunk is no-cache`() =
        testApp { client ->
            val response = client.get("/admin/604.js")
            assertEquals(HttpStatusCode.OK, response.status)
            val cacheControl = response.headers[HttpHeaders.CacheControl].orEmpty()
            assertTrue(cacheControl.contains("no-cache"), "604.js Cache-Control was: $cacheControl")
            assertFalse(cacheControl.contains("max-age"), "604.js must not be cached: $cacheControl")
        }

    @Test
    fun `a revalidation with the sent ETag is answered with 304 and no body`() =
        testApp { client ->
            val first = client.get("/admin/604.js")
            val etag = first.headers[HttpHeaders.ETag]
            assertNotNull(etag, "604.js sent no ETag")

            val second = client.get("/admin/604.js") { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.NotModified, second.status)
            assertEquals("", second.bodyAsText())
            assertTrue(second.headers[HttpHeaders.CacheControl].orEmpty().contains("no-cache"))
        }

    @Test
    fun `a stale ETag gets the full file`() =
        testApp { client ->
            val response = client.get("/admin/604.js") { header(HttpHeaders.IfNoneMatch, "\"stale\"") }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `the content-hashed wasm is cached publicly for a year`() =
        testApp { client ->
            val response = client.get("/admin/stub-0123.wasm")
            assertEquals(HttpStatusCode.OK, response.status)
            val cacheControl = response.headers[HttpHeaders.CacheControl].orEmpty()
            assertTrue(cacheControl.contains("max-age=31536000"), "wasm Cache-Control was: $cacheControl")
            assertTrue(cacheControl.contains("public"), "wasm Cache-Control was: $cacheControl")
        }

    @Test
    fun `a precompressed sibling is served to a client that accepts gzip`() =
        testApp { client ->
            val response = client.get("/admin/stub-pre.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        }

    @Test
    fun `without Accept-Encoding the plain file is served`() =
        testApp { client ->
            val response = client.get("/admin/stub-pre.js")
            assertNull(response.headers[HttpHeaders.ContentEncoding])
            assertEquals("console.log('plain');\n", response.bodyAsText())
        }
}
