package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Catches the misassignment class the other two tests cannot: a rule in the right family but on the
 * wrong side of the read/write split.
 *
 * `RuleModuleConsistencyTest` would accept `POST /api/customers -> CUSTOMER_READ` — right module,
 * declared capability, unchanged verdict today. It only bites in Stage 3, when a read-only
 * `ACCOUNTANT` holding `CUSTOMER_READ` turns out to be able to create customers.
 *
 * `_MANAGE` capabilities are deliberately exempt: that tier exists precisely because some domains'
 * restricted *reads* belong with their writes.
 */
@DisplayName("Read capabilities gate reads; write capabilities gate writes")
class ReadWriteSanityTest {
    private val writeVerbs = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)

    /**
     * Every (rule, capability) pair in the registry — a `CapWithOwnership` rule contributes one pair
     * per capability it names.
     *
     * Was `filterIsInstance<AccessLevel.Cap>()` until Stage 1c added an arm. That would not have
     * failed; it would have silently stopped checking six routes while still reporting green, which
     * is the worse outcome. `requiredCapabilities` is a `when` over the sealed class, so the next new
     * arm breaks the build here instead.
     */
    private val capRules =
        RoutePermissions.modules
            .flatMap { m -> m.rules.entries }
            .flatMap { (key, level) -> level.requiredCapabilities.map { key to it } }

    /**
     * Read-shaped writes and write-shaped reads. Every entry needs a reason: an unexplained
     * exemption is how the read/write split quietly stops meaning anything.
     */
    private val exemptions: Map<Pair<String, HttpMethod>, String> =
        mapOf(
            ("/api/employees/authenticate" to HttpMethod.Post) to
                "credential check; mutates nothing, POST only to keep the PIN out of the URL",
            ("/api/vouchers/validate" to HttpMethod.Post) to
                "voucher lookup; POST only to keep the code out of the URL",
            ("/api/timetracking/my-entries-auth" to HttpMethod.Post) to
                "PIN-guarded read of the caller's own entries",
            ("/api/card-terminals/{id}/status" to HttpMethod.Post) to
                "status ping; reads terminal state, changes nothing",
            ("/api/bills/create-with-card" to HttpMethod.Get) to
                "SSE flow that CREATES a signed RECHNUNG; the GET verb is the lie, BILL_WRITE is right",
        )

    @Test
    fun `no READ capability gates a write verb`() {
        val bad =
            capRules
                .filter { (key, cap) ->
                    key.second in writeVerbs && cap.name.endsWith("_READ") && key !in exemptions
                }.map { (key, cap) -> "${key.second.value} ${key.first} -> $cap" }
        assertTrue(bad.isEmpty(), "Write routes gated by a READ capability:\n${bad.joinToString("\n")}")
    }

    @Test
    fun `no WRITE capability gates a GET`() {
        val bad =
            capRules
                .filter { (key, cap) ->
                    key.second == HttpMethod.Get && cap.name.endsWith("_WRITE") && key !in exemptions
                }.map { (key, cap) -> "GET ${key.first} -> $cap" }
        assertTrue(bad.isEmpty(), "GET routes gated by a WRITE capability:\n${bad.joinToString("\n")}")
    }

    @Test
    fun `every exemption still names a live rule`() {
        val live = capRules.map { it.first }.toSet()
        val stale = exemptions.keys - live
        assertTrue(stale.isEmpty(), "Exemptions for rules that no longer exist: $stale")
    }

    @Test
    fun `every exemption is actually needed`() {
        // An exemption that suppresses nothing implies a violation that does not exist, and quietly
        // erodes the "every entry needs a reason" discipline the list depends on. Two such entries
        // (auth/logout, auth/temporary-session — both POST with SELF_WRITE, which neither assertion
        // flags) were removed after review pointed them out.
        val needed =
            capRules
                .filter { (key, cap) ->
                    (key.second in writeVerbs && cap.name.endsWith("_READ")) ||
                        (key.second == HttpMethod.Get && cap.name.endsWith("_WRITE"))
                }.map { it.first }
                .toSet()
        val unnecessary = exemptions.keys - needed
        assertTrue(
            unnecessary.isEmpty(),
            "Exemptions that suppress no violation — delete them:\n" +
                unnecessary.joinToString("\n") { "  ${it.second.value} ${it.first}" },
        )
    }
}
