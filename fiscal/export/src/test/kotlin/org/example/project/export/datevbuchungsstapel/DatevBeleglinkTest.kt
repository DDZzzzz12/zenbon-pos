package org.example.project.export.datevbuchungsstapel

import kotlin.test.Test
import kotlin.test.assertEquals

class DatevBeleglinkTest {
    @Test
    fun `guid is deterministic and uppercase`() {
        val a = DatevBeleglink.guidFor("ZBon_Nr7_2026-01-02.pdf")
        val b = DatevBeleglink.guidFor("ZBon_Nr7_2026-01-02.pdf")
        assertEquals(a, b)
        assertEquals(a, a.uppercase())
        assertEquals(true, Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$").matches(a))
    }

    @Test
    fun `different belege get different guids`() {
        assertEquals(false, DatevBeleglink.guidFor("a.pdf") == DatevBeleglink.guidFor("b.pdf"))
    }

    @Test
    fun `cellValue doubles inner quotes so writer produces DATEV format`() {
        val guid = "E0A08953-FBAA-4054-AF36-993D5D68F040"
        assertEquals("BEDI \"\"$guid\"\"", DatevBeleglink.cellValue(guid))
    }
}
