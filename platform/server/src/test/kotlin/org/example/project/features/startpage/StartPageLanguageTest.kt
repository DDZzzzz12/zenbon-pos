package org.example.project.features.startpage

import kotlin.test.Test
import kotlin.test.assertEquals

class StartPageLanguageTest {
    @Test
    fun `picks the first supported language by quality`() {
        assertEquals(StartPageLanguage.ZH, StartPageLanguage.fromAcceptLanguage("zh-CN,zh;q=0.9,en;q=0.8"))
        assertEquals(StartPageLanguage.EN, StartPageLanguage.fromAcceptLanguage("fr-FR,en;q=0.5,de;q=0.4"))
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage("de-DE"))
    }

    @Test
    fun `falls back to German`() {
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage(null))
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage("fr,es;q=0.5"))
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage(";;;garbage"))
    }

    @Test
    fun `honours q-values and ignores unusable parts`() {
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage("zh;q=0"))
        assertEquals(StartPageLanguage.EN, StartPageLanguage.fromAcceptLanguage("zh;q=abc,en;q=0.5"))
        assertEquals(StartPageLanguage.EN, StartPageLanguage.fromAcceptLanguage("zh;q=NaN,en;q=0.5"))
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage(",,"))
        assertEquals(StartPageLanguage.DE, StartPageLanguage.fromAcceptLanguage("*"))
        assertEquals(StartPageLanguage.ZH, StartPageLanguage.fromAcceptLanguage("de-DE;q=0.8,zh"))
        assertEquals(StartPageLanguage.ZH, StartPageLanguage.fromAcceptLanguage("ZH;Q=0.9"))
    }

    @Test
    fun `every language has exactly the declared keys`() {
        val declared =
            setOf(
                "title",
                "admin",
                "admin_hint",
                "pos_cashier",
                "pos_station",
                "pos_both",
                "pos_hint",
                "manual",
                "api_docs",
                "set_default",
                "default_browser",
                "default_server",
                "reset",
                "default_unavailable",
                "version",
            )
        StartPageLanguage.entries.forEach {
            assertEquals(declared, StartPageStrings.of(it).keys, "keys differ for $it")
        }
    }
}
