package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {
    @Test
    fun `parses a plain version`() {
        assertEquals(SemanticVersion(1, 1, 0), SemanticVersion.parse("1.1.0"))
    }

    @Test
    fun `parses a v-prefixed tag`() {
        assertEquals(SemanticVersion(1, 1, 0), SemanticVersion.parse("v1.1.0"))
    }

    @Test
    fun `treats a missing patch segment as zero`() {
        assertEquals(SemanticVersion(1, 1, 0), SemanticVersion.parse("v1.1"))
    }

    @Test
    fun `rejects a non-version string`() {
        assertNull(SemanticVersion.parse("nightly"))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("v1.x.0"))
    }

    @Test
    fun `orders by major then minor then patch`() {
        assertTrue(SemanticVersion.parse("1.1.0")!! > SemanticVersion.parse("1.0.99")!!)
        assertTrue(SemanticVersion.parse("2.0.0")!! > SemanticVersion.parse("1.99.99")!!)
        assertTrue(SemanticVersion.parse("1.0.11")!! > SemanticVersion.parse("1.0.9")!!)
    }

    @Test
    fun `equal versions are not newer than each other`() {
        assertEquals(0, SemanticVersion.parse("1.1.0")!!.compareTo(SemanticVersion.parse("v1.1.0")!!))
    }

    @Test
    fun `ignores a prerelease suffix when comparing but records it`() {
        val rc = SemanticVersion.parse("1.2.0-rc1")!!
        assertEquals(SemanticVersion(1, 2, 0, "rc1"), rc)
        assertTrue(rc > SemanticVersion.parse("1.1.0")!!)
    }
}
