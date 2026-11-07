package org.example.project.infrastructure.plugins

import org.example.project.utils.config.YamlConfigLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDocsPolicyTest {
    @Test
    fun `server override wins over the setting and locks the switch`() {
        val on = ApiDocsPolicy.resolve(override = true, devTooling = false, setting = false)
        assertTrue(on.enabled)
        assertTrue(on.lockedByServerConfig)
        val off = ApiDocsPolicy.resolve(override = false, devTooling = true, setting = true)
        assertFalse(off.enabled)
        assertTrue(off.lockedByServerConfig)
    }

    @Test
    fun `dev mode turns docs on when no override is set`() {
        val state = ApiDocsPolicy.resolve(override = null, devTooling = true, setting = false)
        assertTrue(state.enabled)
        assertFalse(state.lockedByServerConfig)
    }

    @Test
    fun `otherwise the setting decides and defaults off`() {
        assertEquals(false, ApiDocsPolicy.resolve(override = null, devTooling = false, setting = false).enabled)
        assertEquals(true, ApiDocsPolicy.resolve(override = null, devTooling = false, setting = true).enabled)
    }

    @Test
    fun `override parsing accepts true and false only`() {
        assertEquals(true, ApiDocsPolicy.parseOverride("true"))
        assertEquals(false, ApiDocsPolicy.parseOverride("FALSE"))
        assertEquals(null, ApiDocsPolicy.parseOverride(""))
        assertEquals(null, ApiDocsPolicy.parseOverride(null))
        assertEquals(null, ApiDocsPolicy.parseOverride("yes"))
    }

    @Test
    fun `unset environment variable means the property is absent and there is no override`() {
        // Only meaningful where POS_API_DOCS is not exported; skip the assertion otherwise.
        if (System.getenv("POS_API_DOCS") != null) return
        assertFalse(YamlConfigLoader.load().hasPath("api.docs.enabled"))
        assertEquals(null, ApiDocsPolicy.readServerOverride())
    }
}
