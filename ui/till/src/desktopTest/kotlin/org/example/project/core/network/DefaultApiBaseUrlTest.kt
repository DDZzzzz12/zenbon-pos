package org.example.project.core.network

import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop leg of the API base URL default, and the rule that matters on every platform:
 * a stored preference wins.
 *
 * The wasm leg (page origin) has no test source set in this module and is verified in a browser
 * instead — see the commit that introduced this file. Asserting `window.location.origin` in a unit
 * test would only restate the implementation; loading the served page and reading the URL it
 * actually calls is the evidence worth having.
 */
class DefaultApiBaseUrlTest {
    @Test
    fun `the desktop default is the conventional local server`() {
        // A desktop till is a separate device on the shop network; it starts here and is then
        // pointed at the real server. This is deliberately NOT the page-origin rule wasm uses.
        assertEquals("http://localhost:8080/", defaultApiBaseUrl())
    }

    @Test
    fun `a stored preference wins over the default`() {
        val db = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "https://kasse.example.test:9443/"))

        assertEquals("https://kasse.example.test:9443/", db.getApiBaseUrl())
    }

    @Test
    fun `with nothing stored the platform default is used`() {
        assertEquals(defaultApiBaseUrl(), FakeLocalDatabaseManager.create().getApiBaseUrl())
    }

    @Test
    fun `a stored preference without a trailing slash still gains one`() {
        // Every API client builds "${getBaseUrl()}$path", so a missing slash would produce
        // ".../api/foo" glued onto the host — a 404 that looks like a routing bug.
        val db = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "https://kasse.example.test"))

        assertEquals("https://kasse.example.test/", db.getApiBaseUrl())
    }

    @Test
    fun `the default itself always ends in a slash`() {
        assertTrue(defaultApiBaseUrl().endsWith("/"), "callers concatenate paths onto it directly")
    }
}
