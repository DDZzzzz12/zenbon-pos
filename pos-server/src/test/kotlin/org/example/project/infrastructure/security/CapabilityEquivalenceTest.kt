package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The load-bearing test of the arc: no verdict changes except the ones a stage deliberately made.
 *
 * Through Stage 1 the expected set was **empty**, and it had to be: Stage 1 was equivalence-
 * preserving by construction, so any diff was a bug. Stage 2b is the first deliberate behaviour
 * change, and its diffs live in [TerminalBundleTrimTest.EXPECTED_DIFFS].
 *
 * The comparison below is set **equality**, not containment, and that distinction is the whole
 * reason this stays a real test rather than a rubber stamp. An allowlist would only catch diffs
 * nobody intended. Equality also catches an intended diff that stopped happening — which is exactly
 * the shape a regression takes here: someone restores `REPORT_READ` to the terminal bundle and the
 * hole silently reopens while the suite stays green.
 *
 * Adding an entry to that set is therefore a reviewed behaviour change, never a way to make a red
 * build go green. `TerminalBundleTrimTest` refuses any entry that grants reach or touches a role
 * other than the device.
 *
 * Run it after **every** module in Task 6, not at the end — a diff then points at one file.
 *
 * The grant dimension was REMOVED in Stage 2a, not relaxed: the `EXCEPTION_*` mechanism it exercised
 * was retired because no client populated it and no endpoint managed it, so `forGrant` no longer
 * exists to test. The matrix is now 504 rules x 4 roles.
 */
@DisplayName("Capability migration changes only the verdicts a stage intended")
class CapabilityEquivalenceTest {
    @Test
    fun `the only verdicts that changed are the ones a stage deliberately changed`() {
        val diffs = mutableListOf<String>()
        var compared = 0

        for ((key, _) in LegacyRuleFixture.rules) {
            val (pattern, method) = key
            // Substitute a literal for each {param} so both resolvers take the same route through
            // their parameterized-matching branches.
            val path = pattern.replace(Regex("\\{[^}]*}"), "1")

            for (role in EmployeeRole.entries) {
                val legacy = LegacyAccessLevelResolver.verdict(path, method, role, emptySet())
                val current = currentVerdict(path, method, role, emptySet())
                compared++
                if (legacy != current) {
                    diffs += "${method.value} $path role=$role legacy=$legacy current=$current"
                }
            }
        }

        assertTrue(compared > 0, "no comparisons ran — the fixture is empty, which is itself a bug")

        val expected = TerminalBundleTrimTest.EXPECTED_DIFFS + ERP_REMOVAL_DIFFS + RECEIPT_REPRINT_DIFFS + CASHIER_TRIM_DIFFS
        val unexpected = diffs.toSet() - expected
        val missing = expected - diffs.toSet()

        assertEquals(
            expected,
            diffs.toSet(),
            buildString {
                appendLine("$compared comparisons; the verdict changes do not match what the arc intends.")
                if (unexpected.isNotEmpty()) {
                    appendLine("UNINTENDED — these verdicts changed and nothing asked them to:")
                    unexpected.sorted().take(50).forEach { appendLine("  $it") }
                    if (unexpected.size > 50) appendLine("  ... and ${unexpected.size - 50} more")
                }
                if (missing.isNotEmpty()) {
                    appendLine("REVERTED — Stage 2b removed these and they are reachable again:")
                    missing.sorted().forEach { appendLine("  $it") }
                }
            },
        )
    }

    /** The live registry's verdict, mirroring `RouteAuthorizationPlugin` including the `Cap` arm. */
    private fun currentVerdict(
        path: String,
        method: HttpMethod,
        role: EmployeeRole,
        grants: Set<LegacyPermission>,
    ): Boolean =
        when (val rule = RoutePermissions.findRule(path, method)) {
            null -> false
            is AccessLevel.Public -> true
            is AccessLevel.Cap -> rule.capability in CapabilityBundles.forRole(role)
            // Stage 1c. Only the FLOOR is comparable here: the ownership half lives in the handler
            // and depends on which row is addressed, which this matrix has no notion of. That is
            // why 1c's real behaviour change — three handlers that checked nothing now do — is
            // covered by OwnershipFloorTest and the route tests, not by this one.
            is AccessLevel.CapWithOwnership ->
                CapabilityBundles.forRole(role).any { it in rule.anyOf }
            // ALL of, mirroring `AccessDecision.check` — the arm directly above is any-of and this
            // one is its opposite, so writing `any` here would make the baseline agree with a gate
            // that admits strictly fewer people, which is the failure this whole test exists to
            // catch. No registry rule carries this arm yet, so the branch changes no verdict today;
            // it is here so that the day one does, the comparison is already right.
            is AccessLevel.AllOf ->
                rule.allOf.all { it in CapabilityBundles.forRole(role) }
            // No legacy rule is a maintainer rule, and an employee can never satisfy this arm.
            is AccessLevel.Maintainer -> false
        }

    companion object {
        /**
         * Verdicts that changed because the ROUTE no longer exists, not because a gate narrowed.
         *
         * The POS-ERP cash-ledger integration was removed on `dev` (90cd23a92, Flyway V116) while
         * this arc was in flight. [LegacyRuleFixture] is a frozen verbatim snapshot that must never
         * be edited, so it still lists five rules whose endpoints are gone — and an endpoint that
         * does not exist correctly resolves to `false` for everyone.
         *
         * These cannot live in [TerminalBundleTrimTest.EXPECTED_DIFFS]: that set asserts every entry
         * touches only TERMINAL_DEVICE, and these are ADMIN and MANAGER. Keeping them separate also
         * keeps the two reasons legible — a deliberate trim, and a feature that left.
         */
        /**
         * The one verdict issue #1 deliberately widened: a waiter may reprint a customer receipt.
         *
         * `POST /api/v1/bills/{billId}/print` moved from `BILL_WRITE` to `PRINTER_WRITE` — see
         * `BillRules` for why. It is the only entry here because the other three roles already
         * held both capabilities, so `TERMINAL_DEVICE` is the only verdict that could change, and
         * this is a *widening*, which is why it cannot live in
         * [TerminalBundleTrimTest.EXPECTED_DIFFS] — that set refuses any entry granting reach.
         */
        val RECEIPT_REPRINT_DIFFS: Set<String> =
            setOf(
                "POST /api/v1/bills/1/print role=TERMINAL_DEVICE legacy=false current=true",
            )

        /**
         * V130 (product decision D1): DOCUMENT_READ and VOUCHER_REPORT left the CASHIER bundle, so the
         * cashier loses these (legacy `Authenticated`/cashier-level) reads. MANAGER and above are
         * unchanged. Every entry is CASHIER and `true -> false`.
         */
        val CASHIER_TRIM_DIFFS: Set<String> =
            setOf(
                "GET /api/documents role=CASHIER legacy=true current=false",
                "GET /api/documents/1 role=CASHIER legacy=true current=false",
                "GET /api/documents/1/file role=CASHIER legacy=true current=false",
                "GET /api/documents/1/invoice role=CASHIER legacy=true current=false",
                "GET /api/documents/1/linked role=CASHIER legacy=true current=false",
                "GET /api/documents/1/metadata role=CASHIER legacy=true current=false",
                "GET /api/documents/document-types role=CASHIER legacy=true current=false",
                "GET /api/documents/export/datev-ap role=CASHIER legacy=true current=false",
                "GET /api/documents/export/datev-ap/statistics role=CASHIER legacy=true current=false",
                "GET /api/documents/statistics role=CASHIER legacy=true current=false",
                "GET /api/documents/unlinked role=CASHIER legacy=true current=false",
                "GET /api/vouchers/created-by-date role=CASHIER legacy=true current=false",
                "GET /api/vouchers/redeemed-by-date role=CASHIER legacy=true current=false",
                "GET /api/vouchers/statistics role=CASHIER legacy=true current=false",
            )

        val ERP_REMOVAL_DIFFS: Set<String> =
            setOf(
                "GET /api/v1/config/1/erp-integration role=ADMIN legacy=true current=false",
                "PUT /api/v1/config/1/erp-integration role=ADMIN legacy=true current=false",
                "GET /api/v1/kassenbuch/transit-outbox role=ADMIN legacy=true current=false",
                "GET /api/v1/kassenbuch/transit-outbox role=MANAGER legacy=true current=false",
                "POST /api/v1/kassenbuch/transit-outbox/abandon role=ADMIN legacy=true current=false",
            )
    }
}
