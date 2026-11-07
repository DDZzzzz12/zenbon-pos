package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability

/**
 * One path family's route rules, plus the two declarations that make the family self-describing.
 *
 * Declaring [pathPrefixes] and [allowedCapabilities] **here** rather than in a central map is the
 * point of the interface. `CapabilityEquivalenceTest` proves the migration preserved today's
 * verdicts, but it provably cannot catch a *semantic* misassignment: give `GET /api/customers` the
 * capability `CATALOG_READ` instead of `CUSTOMER_READ` and — because both sit in the same bundles —
 * every verdict is unchanged and the test passes. The error only surfaces in Stage 3, when
 * `ACCOUNTANT` receives `CATALOG_READ` and silently inherits the customer database.
 *
 * Co-locating the prefixes with the rules they describe lets `RuleModuleConsistencyTest` catch that
 * class for free, and means the check can never drift from the rules the way a separate map would.
 *
 * Splitting the 955-line registry into these modules is also what makes it readable.
 */
interface RuleModule {
    /** Human-readable family name, used in test failure messages. */
    val name: String

    /**
     * Path prefixes this module owns. Every rule's path must start with one of them, and no two
     * modules may claim overlapping prefixes.
     */
    val pathPrefixes: List<String>

    /**
     * Capabilities this module's rules may require.
     *
     * A **set**, because some families legitimately span domains — `/api/timetracking` carries
     * self-service, payroll and reporting routes. Keep it tight: an over-broad set silently weakens
     * the consistency check.
     */
    val allowedCapabilities: Set<Capability>

    /** This family's rules, in the same shape the registry has always used. */
    val rules: Map<Pair<String, HttpMethod>, AccessLevel>
}
