package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccessLevelMaintainerTest {
    @Test
    fun `names no capability`() {
        assertEquals(emptySet(), AccessLevel.Maintainer.requiredCapabilities)
    }

    @Test
    fun `reads as MAINTAINER on the admin permissions screen`() {
        assertEquals("MAINTAINER", AccessLevel.Maintainer.description)
    }

    @Test
    fun `no capability set, however large, satisfies it`() {
        val denial = assertNotNull(AccessDecision.check(AccessLevel.Maintainer, Capability.entries.toSet()))
        assertTrue(denial.missing.isEmpty(), "there is nothing an employee could be granted to pass")
    }

    @Test
    fun `the maintenance rules are registered and gate the log search on the maintainer`() {
        val module = RoutePermissions.modules.single { it.name == "Maintenance" }
        assertEquals(emptySet(), module.allowedCapabilities)
        assertEquals(
            AccessLevel.Maintainer,
            RoutePermissions.findRule("/api/maintenance/logs", io.ktor.http.HttpMethod.Get),
        )
        assertEquals(
            AccessLevel.Public,
            RoutePermissions.findRule("/api/maintenance/login", io.ktor.http.HttpMethod.Post),
        )
    }
}
