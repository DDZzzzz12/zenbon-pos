package org.example.project.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdListQueryParameterTest {
    @Test
    fun `a missing parameter is no filter`() {
        assertNull((null as String?).toIdListOrNull())
    }

    @Test
    fun `entries are trimmed and non-numbers dropped`() {
        assertEquals(listOf(1L, 2L, 3L), " 1, 2,x,,3 ".toIdListOrNull())
    }

    @Test
    fun `a parameter without a valid entry is no filter`() {
        assertNull("".toIdListOrNull())
        assertNull("a, b".toIdListOrNull())
    }
}
