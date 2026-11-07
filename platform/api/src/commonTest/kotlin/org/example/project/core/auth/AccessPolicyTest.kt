package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessPolicyTest {
    private val policy = SetAccessPolicy(setOf(Capability.ORDER_READ, Capability.ORDER_WRITE))

    @Test
    fun `allows a held capability`() {
        assertTrue(policy.allows(Capability.ORDER_READ))
    }

    @Test
    fun `denies a capability not held`() {
        assertFalse(policy.allows(Capability.BILL_VOID))
    }

    @Test
    fun `allowsAny is true when at least one is held`() {
        assertTrue(policy.allowsAny(Capability.BILL_VOID, Capability.ORDER_READ))
    }

    @Test
    fun `allowsAny is false when none are held`() {
        assertFalse(policy.allowsAny(Capability.BILL_VOID, Capability.REPORT_READ))
    }

    @Test
    fun `allowsAll requires every capability`() {
        assertTrue(policy.allowsAll(Capability.ORDER_READ, Capability.ORDER_WRITE))
        assertFalse(policy.allowsAll(Capability.ORDER_READ, Capability.BILL_VOID))
    }

    @Test
    fun `an empty policy allows nothing`() {
        val empty = SetAccessPolicy(emptySet())
        assertTrue(Capability.entries.none { empty.allows(it) })
    }

    @Test
    fun `allowsAny with no arguments is false`() {
        // Vacuous-truth guard: a gate called with a dynamically-built empty list must fail closed.
        assertFalse(policy.allowsAny())
    }

    @Test
    fun `allowsAll with no arguments is true`() {
        // The mirror of the above, and the mathematically correct answer — documented here so the
        // asymmetry with allowsAny is a decision on record rather than a surprise at a call site.
        assertTrue(policy.allowsAll())
    }
}
