package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.startpage.WebEntryRegistry
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `GET /`, `HEAD /` and `POST /start-page`. The web-access and browser-station settings live in the
 * process-wide ShopConfigurationManager cache, so every test resets them before and after.
 */
class StartPageRoutesTest {
    /**
     * An initialised server (InitialSetupServiceImpl: admin user + shop info + at least one setting).
     * The setting is written by [reset], which every initialised test runs before its first request.
     */
    private fun seed() {
        TestData.createAdmin()
        TestData.createShopInfo()
    }

    private suspend fun reset() {
        TestData.clearWebAccessSettings()
        TestData.setBrowserStationRoles(emptySet())
    }

    /** Runs [block] on a seeded, reset server and resets the shared settings afterwards. */
    private fun seededApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApp(setupData = { seed() }) { client ->
            reset()
            try {
                block(client)
            } finally {
                reset()
            }
        }

    private fun ApplicationTestBuilder.noRedirectClient(cookies: Boolean = false): HttpClient =
        createClient {
            followRedirects = false
            expectSuccess = false
            if (cookies) install(HttpCookies)
        }

    @Test
    fun `the seeded server reports itself initialised`() =
        seededApp { client ->
            assertTrue("\"isInitialized\":true" in client.get("/api/setup/status").bodyAsText().replace(" ", ""))
        }

    @Test
    fun `shows the page with admin and manual, no pos, no api docs`() =
        seededApp { client ->
            val response = client.get("/") { header(HttpHeaders.AcceptLanguage, "de") }
            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertTrue("href=\"/admin/\"" in html)
            assertTrue("href=\"/manual/\"" in html)
            assertFalse("href=\"/pos/\"" in html)
            assertFalse("href=\"/swagger\"" in html)
            assertTrue("Test Shop" in html)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
            assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
            assertTrue(response.headers["Content-Security-Policy"]!!.startsWith("default-src 'none'"))
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }

    @Test
    fun `pos and api docs appear when enabled, without a restart`() =
        seededApp { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            TestData.setApiDocsEnabled(true)
            val html = client.get("/").bodyAsText()
            assertTrue("href=\"/pos/\"" in html)
            assertTrue("href=\"/swagger\"" in html)
            reset()
            val after = client.get("/").bodyAsText()
            assertFalse("href=\"/pos/\"" in after)
            assertFalse("href=\"/swagger\"" in after)
        }

    @Test
    fun `chinese when asked`() =
        seededApp { client ->
            val html = client.get("/") { header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9") }.bodyAsText()
            assertTrue("网页管理后台" in html)
        }

    @Test
    fun `setting a browser default redirects there, start shows the page, reset clears it`() =
        seededApp {
            val c = noRedirectClient(cookies = true)
            val set = c.submitForm("/start-page", parameters { append("target", "admin") })
            assertEquals(HttpStatusCode.SeeOther, set.status)
            assertEquals("/admin/", set.headers[HttpHeaders.Location])
            val setCookies = set.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            val cookie = setCookies.single { it.startsWith("pos_start=") }
            assertTrue("HttpOnly" in cookie, cookie)
            assertTrue("SameSite=Lax" in cookie, cookie)

            val root = c.get("/")
            assertEquals(HttpStatusCode.Found, root.status)
            assertEquals("/admin/", root.headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.OK, c.get("/?start").status)

            val cleared = c.submitForm("/start-page", parameters { append("target", "none") })
            assertEquals(HttpStatusCode.SeeOther, cleared.status)
            assertEquals("/?start", cleared.headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.OK, c.get("/").status)
        }

    @Test
    fun `server default applies, browser cookie overrides it`() =
        seededApp {
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            TestData.setStartPageDefault(StartPageTarget.POS)
            val c = noRedirectClient(cookies = true)
            assertEquals("/pos/", c.get("/").headers[HttpHeaders.Location])
            c.submitForm("/start-page", parameters { append("target", "admin") })
            assertEquals("/admin/", c.get("/").headers[HttpHeaders.Location])
        }

    @Test
    fun `an unavailable server default shows the page with its label`() =
        seededApp { client ->
            TestData.setStartPageDefault(StartPageTarget.POS)
            val response = client.get("/") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertTrue("Server default: Browser till / order station" in html, html)
        }

    @Test
    fun `forged target and forged cookie never redirect off the registry`() =
        seededApp {
            val c = noRedirectClient()
            val forged = c.submitForm("/start-page", parameters { append("target", "https://evil.example") })
            assertEquals(HttpStatusCode.BadRequest, forged.status)
            assertTrue(forged.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty(), "a refused target sets no cookie")
            val r = c.get("/") { header(HttpHeaders.Cookie, "pos_start=https%3A%2F%2Fevil.example") }
            assertEquals(HttpStatusCode.OK, r.status)
            val setCookies = r.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            assertTrue(setCookies.any { it.startsWith("pos_start=") && "Max-Age=0" in it }, setCookies.toString())
        }

    @Test
    fun `an uninitialised server sends everyone to the admin`() =
        testApp {
            try {
                reset()
                val c = noRedirectClient()
                assertEquals("/admin/", c.get("/").headers[HttpHeaders.Location])
                assertEquals("/admin/", c.get("/?start").headers[HttpHeaders.Location])
            } finally {
                reset()
            }
        }

    @Test
    fun `HEAD answers like GET without a body`() =
        seededApp { client ->
            val r = client.head("/")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("no-cache", r.headers[HttpHeaders.CacheControl])
            assertEquals("", r.bodyAsText())
        }

    @Test
    fun `HEAD forwards like GET`() =
        seededApp {
            TestData.setStartPageDefault(StartPageTarget.ADMIN)
            val r = noRedirectClient().head("/")
            assertEquals(HttpStatusCode.Found, r.status)
            assertEquals("/admin/", r.headers[HttpHeaders.Location])
        }

    @Test
    fun `every registry path is routed`() =
        seededApp { client ->
            TestData.setBrowserStationRoles(setOf(TerminalRole.CASHIER))
            TestData.setApiDocsEnabled(true)
            WebEntryRegistry.all.forEach { entry ->
                assertNotEquals(HttpStatusCode.NotFound, client.get(entry.path).status, entry.path)
            }
        }
}
