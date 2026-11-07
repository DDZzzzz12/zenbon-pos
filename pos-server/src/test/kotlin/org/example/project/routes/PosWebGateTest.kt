package org.example.project.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browser till is served at `/pos/` only while the shop has a browser station role enabled.
 * The page itself is the stub `src/test/resources/pos-web/index.html`; the real bundle is only on
 * the classpath of a deployment build.
 *
 * The browser-station setting lives in the process-wide ShopConfigurationManager cache, so every test
 * sets it explicitly.
 */
class PosWebGateTest {
    private val cspHeader = "Content-Security-Policy"

    @Test
    fun `GET pos is 404 when no browser role is enabled`() =
        testApp { client ->
            TestData.setBrowserStationRoles(emptySet())
            assertEquals(HttpStatusCode.NotFound, client.get("/pos/index.html").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/pos/").status)
        }

    @Test
    fun `GET pos serves the page when a role is enabled`() =
        testApp { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val response = client.get("/pos/index.html")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("pos-web-test"))
            assertTrue(response.headers[cspHeader] != null, "CSP header missing")
        }

    @Test
    fun `the old pos-preview path redirects permanently to pos`() =
        testApp {
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/pos-preview/index.html")
            assertEquals(HttpStatusCode.MovedPermanently, response.status)
            assertEquals("/pos/", response.headers[HttpHeaders.Location])
        }

    @Test
    fun `the pos-preview redirect keeps the query string`() =
        testApp {
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/pos-preview/?code=ABC123&x=1")
            assertEquals(HttpStatusCode.MovedPermanently, response.status)
            assertEquals("/pos/?code=ABC123&x=1", response.headers[HttpHeaders.Location])
        }

    @Test
    fun `index is no-cache and the content-hashed wasm is cached for a year`() =
        testApp { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            val index = client.get("/pos/index.html").headers[HttpHeaders.CacheControl].orEmpty()
            assertTrue(index.contains("no-cache"), "index.html Cache-Control was: $index")
            val wasm = client.get("/pos/stub-0123.wasm")
            assertEquals(HttpStatusCode.OK, wasm.status)
            assertTrue(wasm.headers[HttpHeaders.CacheControl].orEmpty().contains("max-age=31536000"))
        }

    @Test
    fun `CSP forbids framing and foreign scripts`() =
        testApp { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.ORDER_STATION))
            val csp = client.get("/pos/index.html").headers[cspHeader].orEmpty()
            assertTrue(csp.contains("frame-ancestors 'none'"), csp)
            assertTrue(csp.contains("script-src 'self'"), csp)
            assertTrue(csp.contains("connect-src 'self'"), csp)
        }
}
