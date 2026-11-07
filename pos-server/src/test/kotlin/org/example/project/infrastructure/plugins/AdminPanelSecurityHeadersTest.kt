package org.example.project.infrastructure.plugins

import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminPanelSecurityHeadersTest {
    @Test
    fun `admin panel and pos preview responses carry the policy`() =
        testApplication {
            application {
                install(AdminPanelSecurityHeaders)
                routing {
                    get("/admin/index.html") { call.respondText("<html></html>", ContentType.Text.Html) }
                    get("/pos-preview/index.html") { call.respondText("<html></html>", ContentType.Text.Html) }
                }
            }
            val admin = client.get("/admin/index.html")
            assertEquals(ADMIN_PANEL_CSP, admin.headers["Content-Security-Policy"])
            assertEquals("nosniff", admin.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", admin.headers["Referrer-Policy"])
            assertEquals(ADMIN_PANEL_CSP, client.get("/pos-preview/index.html").headers["Content-Security-Policy"])
        }

    @Test
    fun `api and lookalike paths are untouched`() =
        testApplication {
            application {
                install(AdminPanelSecurityHeaders)
                routing {
                    get("/api/ping") { call.respondText("pong") }
                    get("/administrator") { call.respondText("not the panel") }
                }
            }
            assertNull(client.get("/api/ping").headers["Content-Security-Policy"])
            assertNull(client.get("/administrator").headers["Content-Security-Policy"])
        }

    /**
     * Guards that the static-route `modify` hook adds the security headers regardless of how the
     * request path is spelled — `GET //admin/index.html` and `GET /%61dmin/index.html` don't
     * textually start with `/admin/`, so [AdminPanelSecurityHeaders]'s `onCall` hook (which
     * matches on the raw, undecoded request path) would let both slip through with no headers —
     * while Ktor's routing decodes segments and collapses empty ones, so both are still routed to
     * the `/admin` static route and served. This test exercises the real production wiring —
     * [browserAppStaticResources], the same function `Routing.kt` installs at `/admin` — over a
     * distinct test-only resource base (`test-admin-panel`, not `admin`: the real `admin/`
     * classpath resource only exists when the build is run with `-PbundleAdminPanel=true`, and a
     * test resource of the same name would silently start passing or failing depending on whether
     * that flag was set for the build that produced the test classpath).
     */
    @Test
    fun `real admin static resources carry the policy across path spellings`() =
        testApplication {
            application {
                install(IgnoreTrailingSlash)
                routing {
                    browserAppStaticResources("/admin", "test-admin-panel")
                }
            }

            val spellings =
                listOf(
                    "/admin/index.html",
                    "/admin/",
                    // A leading "//" without an explicit scheme+host is parsed by the HTTP client
                    // itself as a protocol-relative URL (host "admin"), never reaching the server
                    // at all — so the double slash must be forced with an explicit host.
                    "http://localhost//admin/index.html",
                    "/%61dmin/index.html",
                )
            for (path in spellings) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, "expected $path to serve the static resource")
                val csp = response.headers["Content-Security-Policy"]
                assertEquals(ADMIN_PANEL_CSP, csp, "expected the CSP header on $path")
                assertTrue(
                    csp!!.contains("worker-src 'self' blob:"),
                    "expected worker-src 'self' blob: in the CSP served for $path",
                )
                assertEquals("nosniff", response.headers["X-Content-Type-Options"], "expected nosniff on $path")
                assertEquals("no-referrer", response.headers["Referrer-Policy"], "expected no-referrer on $path")
            }
        }
}
