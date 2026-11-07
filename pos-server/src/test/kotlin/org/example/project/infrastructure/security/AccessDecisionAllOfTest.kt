package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.auth.Capability
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * That [AccessLevel.AllOf] is actually *enforced*, not merely declarable.
 *
 * [AccessDecision.check] is the one comparison the gate makes — `RouteAuthorizationPlugin` resolves
 * what the caller holds and then hands the answer straight to it. An arm the registry can express
 * but the gate does not enforce is worse than no arm at all: every reader of the rule file would
 * believe an operation is gated on both capabilities when it is gated on neither.
 *
 * The half-holder cases are the point. `CapWithOwnership` would admit them, and admitting them is
 * exactly the policy this arm was added to reject.
 */
@DisplayName("AccessDecision — AllOf")
class AccessDecisionAllOfTest {
    private val level = AccessLevel.AllOf(setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE))

    @Test
    fun `holding both capabilities passes`() {
        assertNull(AccessDecision.check(level, setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE)))
    }

    @Test
    fun `holding more than both still passes`() {
        // Strictly narrowing, so there is no exact-match trap: a more privileged principal is never
        // refused by a rule a less privileged one satisfies. That was defect D1, which removed the
        // old `Role` arm, and it is why adding this arm does not reintroduce it.
        assertNull(
            AccessDecision.check(
                level,
                setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE, Capability.CREDITOR_READ),
            ),
        )
    }

    @Test
    fun `holding only one half is refused, and the denial names the half that is missing`() {
        val creditorOnly = assertNotNull(AccessDecision.check(level, setOf(Capability.CREDITOR_WRITE)))
        assertEquals(setOf(Capability.DEBTOR_WRITE), creditorOnly.missing)

        val debtorOnly = assertNotNull(AccessDecision.check(level, setOf(Capability.DEBTOR_WRITE)))
        assertEquals(setOf(Capability.CREDITOR_WRITE), debtorOnly.missing)
    }

    @Test
    fun `holding neither is refused`() {
        val denial = assertNotNull(AccessDecision.check(level, emptySet()))
        assertEquals(setOf(Capability.CREDITOR_WRITE, Capability.DEBTOR_WRITE), denial.missing)
    }
}
