package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenancePageTest {
    @AfterEach
    fun restore() {
        MaintenancePolicy.useForTesting(null)
    }

    private fun enable() = MaintenancePolicy.useForTesting(MaintenanceConfig("maint", BCrypt.withDefaults().hashToString(4, "pw".toCharArray()), 30))

    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/maintenance/$name")) { "missing resource maintenance/$name" }.readText()

    @Test
    fun `the page is served with a restrictive CSP when the feature is on`() =
        testApp { client ->
            enable()
            val response = client.get("/maintenance/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("x-data=\"maintenance\"" in response.bodyAsText())
            val csp = response.headers["Content-Security-Policy"].orEmpty()
            assertTrue("default-src 'self'" in csp, csp)
            assertTrue("frame-ancestors 'none'" in csp, csp)
            assertFalse("unsafe-inline" in csp, "inline script/style must stay forbidden: $csp")
        }

    @Test
    fun `opened without the trailing slash the page's assets still resolve`() =
        testApp { client ->
            enable()
            val response = client.get("/maintenance")
            assertEquals(HttpStatusCode.OK, response.status)
            val html = response.bodyAsText()
            assertTrue("x-data=\"maintenance\"" in html)
            // `base-uri 'none'` forbids a <base>, so a relative URL would resolve against /maintenance → /app.js.
            val assets = Regex("""(?:src|href)="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
            assertEquals(setOf("/maintenance/app.css", "/maintenance/app.js", "/maintenance/vendor/alpine.min.js"), assets.toSet())
            assets.forEach { asset ->
                val resolved = URI("http://localhost/maintenance").resolve(asset).path
                assertEquals(HttpStatusCode.OK, client.get(resolved).status, "$asset resolved to $resolved")
            }
        }

    @Test
    fun `every page file answers 404 while the feature is off`() =
        testApp { client ->
            MaintenancePolicy.useForTesting(MaintenanceConfig.DISABLED)
            listOf("/maintenance/", "/maintenance/index.html", "/maintenance/app.js", "/maintenance/vendor/alpine.min.js").forEach {
                assertEquals(HttpStatusCode.NotFound, client.get(it).status, it)
            }
        }

    @Test
    fun `log content is only ever rendered as text`() {
        // Log content is attacker-influenced (client-error reports, request paths, X-Request-ID).
        val forbidden = listOf("x-html", "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function")
        listOf("index.html", "app.js").forEach { file ->
            val text = resource(file)
            forbidden.forEach { token -> assertFalse(token in text, "$file contains '$token'") }
        }
    }

    @Test
    fun `the page has no inline script or inline style - the CSP depends on it`() {
        val html = resource("index.html")
        assertFalse(Regex("<script(?![^>]*\\bsrc=)").containsMatchIn(html), "inline <script>")
        assertFalse(Regex("<style").containsMatchIn(html), "inline <style>")
        assertFalse(Regex("""\sstyle\s*=""").containsMatchIn(html), "style attribute")
        assertFalse("onclick=" in html || "onsubmit=" in html, "inline event handler")
    }

    @Test
    fun `the framework is vendored, not loaded from a CDN`() {
        val html = resource("index.html")
        assertTrue("src=\"/maintenance/vendor/alpine.min.js\"" in html)
        assertFalse("http://" in html || "https://" in html, "no external URL may appear in the page")
        assertTrue(resource("vendor/LICENSE-alpine.txt").contains("MIT"))
    }
}
