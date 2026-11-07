package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class EmptyValueTest {
    @Test
    fun nullBlankAndValue() {
        assertEquals("—", null.orDash())
        assertEquals("—", "  ".orDash())
        assertEquals("x", "x".orDash())
    }
}
