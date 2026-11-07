package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenanceConfigTest {
    private val validHash: String = BCrypt.withDefaults().hashToString(4, "correct horse".toCharArray())

    @Test
    fun `blank settings leave the feature off`() {
        val config = MaintenanceConfig.of(username = "", passwordHash = "", sessionMinutes = "")
        assertFalse(config.enabled)
        assertEquals(MaintenanceConfig.DEFAULT_SESSION_MINUTES, config.sessionMinutes)
    }

    @Test
    fun `absent settings leave the feature off`() {
        assertFalse(MaintenanceConfig.of(null, null, null).enabled)
    }

    @Test
    fun `a username without a hash leaves the feature off`() {
        assertFalse(MaintenanceConfig.of("maint", "", null).enabled)
    }

    @Test
    fun `a hash without a username leaves the feature off`() {
        assertFalse(MaintenanceConfig.of("", validHash, null).enabled)
    }

    @Test
    fun `both set turns the feature on and trims whitespace`() {
        val config = MaintenanceConfig.of("  maint  ", " $validHash ", " 45 ")
        assertTrue(config.enabled)
        assertEquals("maint", config.username)
        assertEquals(validHash, config.passwordHash)
        assertEquals(45, config.sessionMinutes)
    }

    @Test
    fun `a malformed hash is a startup error, not a silent disable`() {
        val failure = assertFailsWith<IllegalStateException> { MaintenanceConfig.of("maint", "not-a-hash", null) }
        assertTrue("MAINTAINER_PASSWORD_HASH" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a malformed hash is rejected even when the username is missing`() {
        assertFailsWith<IllegalStateException> { MaintenanceConfig.of("", "plaintext-password", null) }
    }

    /** [validHash] with its two cost digits replaced: `$2a$04$...` becomes `$2a$<cost>$...`. */
    private fun withCost(cost: String): String = validHash.substring(0, 4) + cost + validHash.substring(6)

    @Test
    fun `a BCrypt cost outside 4 to 31 is a startup error`() {
        listOf("03", "32", "99").forEach { cost ->
            val failure = assertFailsWith<IllegalStateException>("cost $cost") { MaintenanceConfig.of("maint", withCost(cost), null) }
            assertTrue("MAINTAINER_PASSWORD_HASH" in failure.message.orEmpty(), "for cost $cost: ${failure.message}")
        }
    }

    @Test
    fun `a BCrypt cost of 4 or 31 is accepted`() {
        listOf("04", "31").forEach { cost ->
            assertTrue(MaintenanceConfig.of("maint", withCost(cost), null).enabled, "cost $cost")
        }
    }

    @Test
    fun `session minutes outside 1 to 480 or not a number are rejected`() {
        listOf("0", "481", "abc").forEach { bad ->
            val failure = assertFailsWith<IllegalStateException> { MaintenanceConfig.of("maint", validHash, bad) }
            assertTrue("MAINTAINER_SESSION_MINUTES" in failure.message.orEmpty(), "for '$bad': ${failure.message}")
        }
    }

    @Test
    fun `toString never prints the hash`() {
        val config = MaintenanceConfig.of("maint", validHash, null)
        assertFalse(validHash in config.toString())
        assertFalse(config.username in config.toString())
    }
}
