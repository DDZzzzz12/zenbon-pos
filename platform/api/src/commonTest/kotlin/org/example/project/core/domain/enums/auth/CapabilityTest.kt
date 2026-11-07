package org.example.project.core.domain.enums.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityTest {
    @Test
    fun `reserved capabilities are exactly the fiscally critical set`() {
        val reserved = Capability.entries.filter { it.assignability == Assignability.RESERVED }.toSet()
        assertEquals(
            setOf(
                Capability.TSE_ADMIN,
                Capability.TSE_QUEUE_ADMIN,
                Capability.ZBON_CLOSE,
                Capability.MONTHLY_CLOSE,
                Capability.DOCUMENT_DELETE,
                Capability.BACKUP_RESTORE,
                Capability.SYSTEM_CONFIG,
                Capability.PERMISSION_ADMIN,
                // Added by 16bae5b31 ("step-up lends capabilities instead of swapping identity")
                // and accepted here on 2026-09-05. Approving a step-up is what hands another
                // employee a capability they do not hold, so an operator-created group must not be
                // able to grant it — the same argument as PERMISSION_ADMIN beside it.
                Capability.ELEVATION_APPROVE,
            ),
            reserved,
            "The RESERVED set is what keeps fiscally critical capabilities out of operator-created " +
                "groups (Stage 5). Adding or removing one is a deliberate decision, not a drive-by edit.",
        )
    }

    @Test
    fun `capability names are unique and screaming snake case`() {
        val names = Capability.entries.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate capability name")
        val pattern = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")
        assertTrue(
            names.all { it.matches(pattern) },
            "non-conforming names: ${names.filterNot { it.matches(pattern) }}",
        )
    }

    @Test
    fun `every write domain has a matching read domain`() {
        // A _WRITE with no _READ would let a group grant mutation without visibility, which is never
        // intended and is usually a typo in the domain prefix.
        val reads =
            Capability.entries
                .map { it.name }
                .filter { it.endsWith("_READ") }
                .map { it.removeSuffix("_READ") }
                .toSet()
        val orphanWrites =
            Capability.entries
                .map { it.name }
                .filter { it.endsWith("_WRITE") }
                .map { it.removeSuffix("_WRITE") }
                .filterNot { it in reads }
        assertTrue(orphanWrites.isEmpty(), "_WRITE capabilities with no matching _READ: $orphanWrites")
    }

    @Test
    fun `no domain has both a WRITE and a MANAGE`() {
        // The two tiers answer different questions and a domain must commit to one. Holding both
        // would make it ambiguous which one a write route should require.
        val names = Capability.entries.map { it.name }
        val writes = names.filter { it.endsWith("_WRITE") }.map { it.removeSuffix("_WRITE") }.toSet()
        val manages = names.filter { it.endsWith("_MANAGE") }.map { it.removeSuffix("_MANAGE") }.toSet()
        val both = writes intersect manages
        assertTrue(both.isEmpty(), "domains carrying both a _WRITE and a _MANAGE: $both")
    }

    @Test
    fun `every manage domain has a matching read domain`() {
        val reads =
            Capability.entries
                .map { it.name }
                .filter { it.endsWith("_READ") }
                .map { it.removeSuffix("_READ") }
                .toSet()
        // Domains that are manage-only on purpose. ISSUE_REPORT: filing is ISSUE_REPORT_CREATE, and
        // reading the target repository or the token state reveals where reports and their
        // diagnostics go, so it needs the same ISSUE_REPORT_MANAGE as changing them (IssueReportRules).
        val manageOnly = setOf("ISSUE_REPORT")
        val orphans =
            Capability.entries
                .map { it.name }
                .filter { it.endsWith("_MANAGE") }
                .map { it.removeSuffix("_MANAGE") }
                .filterNot { it in reads || it in manageOnly }
        assertTrue(orphans.isEmpty(), "_MANAGE capabilities with no matching _READ: $orphans")
    }

    @Test
    fun `domain capabilities are never reserved`() {
        // Reserve the specific dangerous OPERATION (ZBON_CLOSE), never a whole domain's read/write
        // pair — reserving a domain would make ordinary delegation impossible in Stage 5.
        val reservedDomains =
            Capability.entries
                .filter { it.name.endsWith("_READ") || it.name.endsWith("_WRITE") || it.name.endsWith("_MANAGE") }
                .filter { it.assignability == Assignability.RESERVED }
        assertTrue(reservedDomains.isEmpty(), "Reserved domain capabilities: ${reservedDomains.map { it.name }}")
    }
}
