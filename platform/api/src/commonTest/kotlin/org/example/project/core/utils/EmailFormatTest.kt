package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailFormatTest {
    @Test
    fun `absent and blank are acceptable because the field is optional`() {
        assertTrue(EmailFormat.isAcceptable(null))
        assertTrue(EmailFormat.isAcceptable(""))
        assertTrue(EmailFormat.isAcceptable("   "))
    }

    @Test
    fun `real addresses pass`() {
        listOf(
            "info@firma.de",
            "buchhaltung+rechnungen@muster-firma.co.uk",
            "a.b@sub.domain.example",
            "x@y.zz",
            "rechnung@müller-gmbh.de",
            "  info@firma.de  ",
        ).forEach { assertTrue(EmailFormat.isAcceptable(it), it) }
    }

    @Test
    fun `the mistakes an operator makes are refused`() {
        listOf(
            "kaputt",
            "info@firma",
            "@firma.de",
            "info@",
            "info@@firma.de",
            "info@firma@de.de",
            "info @firma.de",
            "info@fir ma.de",
            "info@.de",
            "info@firma.",
            "info@firma..de",
            "Max Mustermann <max@firma.de>",
        ).forEach { assertFalse(EmailFormat.isAcceptable(it), it) }
    }
}
