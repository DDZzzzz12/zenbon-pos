package org.example.project.infrastructure.server

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlainHttpExposureTest {
    @Test
    fun `loopback bind in production is quiet`() {
        assertNull(plainHttpExposureWarning(host = "127.0.0.1", developmentMode = false))
        assertNull(plainHttpExposureWarning(host = "localhost", developmentMode = false))
    }

    @Test
    fun `wildcard bind in production warns and names the fix`() {
        val warning = assertNotNull(plainHttpExposureWarning(host = "0.0.0.0", developmentMode = false))
        assertTrue(warning.contains("HOST=127.0.0.1"))
    }

    @Test
    fun `any non-loopback bind in production warns, IPv6 loopback does not`() {
        assertNotNull(plainHttpExposureWarning(host = "::", developmentMode = false))
        val lan = assertNotNull(plainHttpExposureWarning(host = "192.168.1.10", developmentMode = false))
        // Quoted, so an empty or padded HOST value is visible in the log.
        assertTrue(lan.contains("'192.168.1.10'"))
        assertNull(plainHttpExposureWarning(host = "::1", developmentMode = false))
    }

    @Test
    fun `development mode never warns`() {
        assertNull(plainHttpExposureWarning(host = "0.0.0.0", developmentMode = true))
    }
}
