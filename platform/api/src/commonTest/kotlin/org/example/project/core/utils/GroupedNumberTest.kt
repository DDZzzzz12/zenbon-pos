package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupedNumberTest {
    @Test
    fun german() {
        assertEquals("20.000.000", 20_000_000L.formatGrouped(Language.GERMAN))
    }

    @Test
    fun english() {
        assertEquals("20,000,000", 20_000_000L.formatGrouped(Language.ENGLISH))
    }

    @Test
    fun negativeAndSmall() {
        assertEquals("-1.058", (-1058L).formatGrouped(Language.GERMAN))
        assertEquals("7", 7L.formatGrouped(Language.GERMAN))
    }
}
