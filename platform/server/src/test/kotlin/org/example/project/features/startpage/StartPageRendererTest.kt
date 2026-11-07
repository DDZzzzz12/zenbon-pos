package org.example.project.features.startpage

import org.example.project.core.domain.enums.setup.StartPageTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartPageRendererTest {
    private fun page(
        shopName: String = "Café <Test> & \"Co\"",
        entries: List<WebEntry> = listOf(WebEntryRegistry.ADMIN, WebEntryRegistry.MANUAL),
        browserDefault: StartPageTarget? = null,
        serverDefault: StartPageTarget = StartPageTarget.NONE,
        unavailable: Boolean = false,
        language: StartPageLanguage = StartPageLanguage.DE,
    ) = StartPageRenderer.render(
        StartPageRenderer.Model(shopName, "1.2.3", entries, browserDefault, serverDefault, unavailable, language),
    )

    @Test
    fun `escapes the shop name`() {
        val html = page()
        assertTrue("Café &lt;Test&gt; &amp; &quot;Co&quot;" in html)
        assertFalse("<Test>" in html)
    }

    @Test
    fun `lists only the given entries and offers default only where allowed`() {
        val html = page()
        assertTrue("href=\"/admin/\"" in html)
        assertTrue("href=\"/manual/\"" in html)
        assertFalse("href=\"/pos/\"" in html)
        assertTrue("name=\"target\" value=\"admin\"" in html)
        assertFalse("value=\"manual\"" in html)
    }

    @Test
    fun `shows the active default and the reset form`() {
        val html = page(browserDefault = StartPageTarget.ADMIN)
        assertTrue("Standard für diesen Browser: Web-Admin" in html)
        assertTrue("name=\"target\" value=\"none\"" in html)
        val server = page(serverDefault = StartPageTarget.ADMIN)
        assertTrue("Standard des Servers: Web-Admin" in server)
    }

    @Test
    fun `renders the unavailable note and the language`() {
        assertTrue("derzeit nicht verfügbar" in page(unavailable = true))
        val zh = page(language = StartPageLanguage.ZH)
        assertTrue("<html lang=\"zh\">" in zh)
        assertTrue("网页管理后台" in zh)
    }

    @Test
    fun `names a server default that is not among the entries`() {
        val html = page(serverDefault = StartPageTarget.POS, unavailable = true, language = StartPageLanguage.EN)
        assertFalse("href=\"/pos/\"" in html)
        assertTrue("Server default: Browser till / order station" in html, html)
        assertFalse("Server default: pos" in html)
    }

    @Test
    fun `names a browser default that is not among the entries`() {
        val html = page(browserDefault = StartPageTarget.POS, language = StartPageLanguage.EN)
        assertTrue("Browser till / order station" in html, html)
    }

    @Test
    fun `a substituted argument is escaped after substitution`() {
        val html =
            StartPageRenderer.render(
                StartPageRenderer.Model(
                    shopName = "{0} <b>",
                    version = "<script>{0}",
                    entries = listOf(WebEntryRegistry.ADMIN),
                    browserDefault = null,
                    serverDefault = StartPageTarget.NONE,
                    defaultUnavailable = false,
                    language = StartPageLanguage.EN,
                ),
            )
        assertTrue("Version &lt;script&gt;{0}" in html, html)
        assertTrue("{0} &lt;b&gt;" in html, html)
        assertFalse("<script" in html.lowercase())
        assertFalse("<b>" in html)
    }

    @Test
    fun `contains no script`() {
        assertFalse("<script" in page().lowercase())
    }
}
