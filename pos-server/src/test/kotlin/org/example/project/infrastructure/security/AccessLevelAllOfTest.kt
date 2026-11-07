package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.auth.Capability
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The arm for a route that needs two capabilities at once.
 *
 * `Cap` names one and `CapWithOwnership` means *any one of*, so neither can say "both". Merging two
 * business partners restructures a creditor and a debtor in one operation, and the spec is explicit
 * that holding one half must not be enough.
 */
@DisplayName("AccessLevel.AllOf")
class AccessLevelAllOfTest {
    @Test
    fun `it reports every capability it names`() {
        val level = AccessLevel.AllOf(setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE))

        assertEquals(
            setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE),
            level.requiredCapabilities,
        )
    }

    /** A single-capability AllOf is a `Cap` written the long way, and reads as if it demanded more. */
    @Test
    fun `it refuses fewer than two capabilities`() {
        assertFailsWith<IllegalArgumentException> {
            AccessLevel.AllOf(setOf(Capability.CREDITOR_WRITE))
        }
        assertFailsWith<IllegalArgumentException> {
            AccessLevel.AllOf(emptySet())
        }
        Unit
    }
}
