package org.example.project.ui.components.form

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosFormFocusTest {
    @Test
    fun emptyErrorsYieldNull() {
        assertNull(firstInvalid(listOf("a", "b"), emptyMap<String, String>()))
    }

    @Test
    fun orderWinsOverMapOrder() {
        val errors = linkedMapOf("c" to "x", "a" to "y")
        assertEquals("a", firstInvalid(listOf("a", "b", "c"), errors))
    }

    @Test
    fun keyAbsentFromOrderIsIgnored() {
        assertNull(firstInvalid(listOf("a", "b"), mapOf("z" to "x")))
    }
}
