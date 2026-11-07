package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 1 of the client access-policy track: the rules that decide what a gate is allowed to answer.
 *
 * Nothing gates on capabilities yet — slices 2 and 3 move the ~150 call sites. What is testable now
 * is the resolution these three pieces of state produce, and the two ways it can go quietly wrong:
 *
 *  - reading "the server reported nothing" as "this principal holds nothing", which would hide every
 *    control in the UI the moment a client met an un-upgraded server;
 *  - letting a temporary session's set be derived from a role rather than taken from the server,
 *    which reproduces the exact divergence purpose-built groups exist to express.
 *
 * `PermissionManager` itself needs a `LocalDatabaseManager` and a `TokenStore`, so the rule is
 * extracted as a pure function and exercised directly. That is deliberate: the rule is what carries
 * the risk, and a test that had to stand up storage to reach it would be a test of the storage.
 */
class CapabilityHolderTest {
    /**
     * The PRODUCTION rule, not a copy of it — `resolveAccessPolicy` is what
     * `PermissionManager.updateAccessPolicy` calls. A test that restated the rule would keep passing
     * while the real one drifted, which is the failure this track is unpicking elsewhere.
     */
    private fun resolve(
        reported: Set<Capability>?,
        temporary: Set<Capability>?,
        effectiveRole: EmployeeRole?,
    ): AccessPolicy = resolveAccessPolicy(reported, temporary, effectiveRole)

    @Test
    fun `an unreported set falls back to the role, not to nothing`() {
        // The failure this prevents: a new client against a server that predates the capability
        // field would gate on an empty set and hide every control — indistinguishable, to the user,
        // from a broken app.
        val policy = resolve(reported = null, temporary = null, effectiveRole = EmployeeRole.MANAGER)

        assertTrue(policy.allows(Capability.CATALOG_MANAGE), "a manager must keep their controls")
        assertTrue(policy.allows(Capability.ORDER_WRITE))
    }

    @Test
    fun `a reported set wins over the role, including where it is narrower`() {
        // The case the whole track exists for: an accountant carries role MANAGER and group
        // ACCOUNTANT. Gating on the role would offer them catalog management, which the server
        // refuses.
        val accountant = CapabilityBundles.forGroup("ACCOUNTANT")!!
        val policy = resolve(reported = accountant, temporary = null, effectiveRole = EmployeeRole.MANAGER)

        assertTrue(policy.allows(Capability.INVOICE_WRITE), "an accountant books invoices")
        assertFalse(policy.allows(Capability.CATALOG_MANAGE), "and does not manage the catalog")
    }

    @Test
    fun `no session gates on nothing`() {
        // A null role is signed-out, not "unrestricted".
        val policy = resolve(reported = null, temporary = null, effectiveRole = null)
        Capability.entries.forEach { assertFalse(policy.allows(it), "$it must not be reachable without a session") }
    }

    @Test
    fun `a temporary session replaces the base set rather than adding to it`() {
        // Mirrors the server: `elevateWithTempSession` swaps employeeId as well as role, so the
        // route plugin resolves the ELEVATING principal's group and the operator's own capabilities
        // do not apply. The client must show the same, including where that is narrower.
        val operator = CapabilityBundles.forGroup("ORDER_ONLY")!!
        val elevated = CapabilityBundles.forGroup("ACCOUNTANT")!!

        val policy = resolve(reported = operator, temporary = elevated, effectiveRole = EmployeeRole.CASHIER)

        assertTrue(policy.allows(Capability.INVOICE_WRITE), "the elevated set applies")
        assertFalse(
            policy.allows(Capability.ORDER_WRITE),
            "and REPLACES rather than unions — an ORDER_ONLY operator loses ORDER_WRITE while " +
                "elevated to an ACCOUNTANT. This asserts today's server behaviour, not an endorsement " +
                "of it; switching to a union is the step-up stage's decision.",
        )
    }

    @Test
    fun `clearing the temporary set falls back to the base set, not to the role`() {
        val operator = CapabilityBundles.forGroup("ORDER_ONLY")!!
        val policy = resolve(reported = operator, temporary = null, effectiveRole = EmployeeRole.MANAGER)

        assertTrue(policy.allows(Capability.ORDER_WRITE))
        assertFalse(
            policy.allows(Capability.CATALOG_MANAGE),
            "stepping down must not leave the operator with the role's authority",
        )
    }

    @Test
    fun `an elevated set is used even when the base set was never reported`() {
        // Old server for the base login, new answer during elevation. The elevated set is still the
        // better answer, so it must not be discarded in favour of the role fallback.
        val elevated = CapabilityBundles.forGroup("AUDITOR")!!
        val policy = resolve(reported = null, temporary = elevated, effectiveRole = EmployeeRole.CASHIER)

        assertTrue(policy.allows(Capability.AUDIT_READ))
        assertFalse(policy.allows(Capability.BILL_WRITE), "a cashier's own authority is not retained")
    }

    @Test
    fun `unknown capability names are dropped, not fatal`() {
        // Forward compatibility: a client meeting a server that added a capability must ignore the
        // name it does not know. This is the mapping PermissionManager applies to the wire strings.
        val wire = setOf("ORDER_WRITE", "SOMETHING_ADDED_LATER")
        val mapped = wire.mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }.toSet()

        assertTrue(SetAccessPolicy(mapped).allows(Capability.ORDER_WRITE))
        assertTrue(mapped.size == 1, "the unknown name is dropped rather than failing the whole set")
    }
}
