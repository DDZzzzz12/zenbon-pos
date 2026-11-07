package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 4b: there is no weak signing guard left to reach for.
 *
 * `requireSigningTerminal` and `TransactionSigningGuard.assertMaySign` used to be two guards of
 * unequal strength. Order, OrderItem, Bill and SalesInvoice called both; Z-Bon, CashBookTransfer and
 * Kassenbuch called only the weaker one — while signing under the calling station's own TSE client.
 * A deactivated, non-fiscal or TSE-unregistered terminal could therefore produce a signed Z-Bon or
 * fiscal Kassenbuch entry, and be refused an ordinary sale from the same machine.
 *
 * Adding `assertMaySign` to those three sites would have fixed today and left the next signing path
 * with the same even chance of forgetting. Unifying the guards — making the mode a **required
 * parameter** of `requireSigningTerminal`, which then asserts internally — makes it unforgettable:
 * there is no call shape that resolves a terminal without also checking it may sign.
 *
 * These are source-level assertions on purpose. The property is "no call site can do X", which is
 * about the shape of the code rather than the behaviour of one path; a behavioural test would have
 * to enumerate every signing route to say the same thing, and would silently miss the next one
 * added. The compiler already enforces the required parameter — what these guard against is someone
 * reintroducing a second, weaker entry point alongside it.
 */
@DisplayName("Unified signing guard")
class UnifiedSigningGuardTest {
    private companion object {
        /** Kotlin files under [SourceRoots.serverMainRoots] after the ecr data move (stage B2-2) added `ecr/data` to the roots: 934 files, floor 930. */
        const val MINIMUM_SCANNED_FILES = 930

        const val GUARD = "org/example/project/features/terminal/domain/TerminalSigningGuard.kt"
    }

    /** `pos-server`'s main sources and, once it exists, their `platform/server` successor. */
    private fun sources(): List<File> =
        SourceRoots.kotlinFiles(SourceRoots.serverMainRoots()).also {
            SourceRoots.requireScanned("UnifiedSigningGuardTest", it.size, MINIMUM_SCANNED_FILES)
        }

    private fun guardSource(): String = SourceRoots.fileUnder(SourceRoots.serverMainRoots(), GUARD).readText()

    @Test
    fun `no service calls assertMaySign directly any more`() {
        // Its only legitimate caller is requireSigningTerminal. A direct call from a service is
        // either a duplicate of what the guard already did, or — worse — a path that resolved its
        // terminal some other way and is guarding it by hand.
        val offenders =
            sources()
                .filter { it.name != "TransactionSigningGuard.kt" && it.name != "TerminalSigningGuard.kt" }
                .filter { file ->
                    file.readLines().any { line ->
                        line.contains("assertMaySign(") &&
                            !line.trimStart().startsWith("*") &&
                            !line.trimStart().startsWith("//")
                    }
                }.map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "call requireSigningTerminal(repo, deviceId, mode) instead — it asserts for you",
        )
    }

    @Test
    fun `every requireSigningTerminal call passes a mode`() {
        // Belt and braces over the compiler: a future overload with a defaulted mode would compile
        // everywhere and silently restore the weak guard for anyone who omitted the argument.
        val calls =
            sources()
                .filter { it.name != "TerminalSigningGuard.kt" }
                .flatMap { file -> file.readLines().map { file.name to it.trim() } }
                .filter { (_, line) ->
                    line.contains("requireSigningTerminal(") &&
                        !line.startsWith("*") &&
                        !line.startsWith("//") &&
                        !line.startsWith("import ")
                }

        assertTrue(calls.isNotEmpty(), "no call sites found — the scan is broken, not the code")

        // Counts ARGUMENTS rather than matching an argument NAME. The first version looked for
        // the literal "mode)", which broke the moment the hoisting fix renamed the local to
        // `tseMode` — a test that fails on a rename rather than on a defect is worse than none.
        val threeArgs = Regex("requireSigningTerminal\\([^()]*,[^()]*,[^()]*\\)")
        val singleLine = calls.filter { (_, line) -> line.contains(")") }
        val bad = singleLine.filterNot { (_, line) -> threeArgs.containsMatchIn(line) }
        assertEquals(emptyList(), bad, "requireSigningTerminal call without a mode argument")
    }

    @Test
    fun `requireSigningTerminal takes the mode as a required parameter`() {
        // A default value would reintroduce the weak form as the path of least resistance.
        val guard = guardSource()
        assertTrue(guard.contains("mode: TseMode,"), "the mode parameter is missing")
        assertTrue(
            !guard.contains("mode: TseMode = "),
            "the mode must not have a default — a default is how the weak guard comes back",
        )
        assertTrue(
            guard.contains("TransactionSigningGuard.assertMaySign(terminal, mode)"),
            "requireSigningTerminal must assert, not merely resolve",
        )
    }

    @Test
    fun `the guard rejects before returning a terminal, not after`() {
        // Ordering matters: a caller destructures `.clientId` off the return value, so an assertion
        // that ran after the return would be no assertion at all.
        val guard = guardSource()
        val assertAt = guard.indexOf("assertMaySign(terminal, mode)")
        val returnAt = guard.indexOf("return terminal")
        assertTrue(assertAt in 1..<returnAt, "assertMaySign must run before the terminal is returned")
    }

    @Test
    fun `no signing path reads the TSE mode from inside its transaction`() {
        // `getTseMode()` takes `configMutex` and, on a cache miss, opens its own `dbQuery`. Calling
        // it inside an enclosing transaction is therefore both a nested transaction (forbidden by
        // CLAUDE.md) and a lock-ordering hazard — and every one of these transactions holds
        // `pg_advisory_xact_lock(1)`, so a stall there would block Z-Bon and Kassenbuch writes
        // shop-wide. `KassenbuchServiceImpl` already hoists its ERP config above the transaction for
        // exactly this reason; Stage 4b initially failed to do the same for the mode.
        //
        // A source-level check because the property is "no call site does X". Reproducing the
        // deadlock behaviourally would need a cold config cache and an exhausted pool at once.
        val offenders =
            sources()
                .filter { file ->
                    val text = file.readText()
                    val inline = "requireSigningTerminal(terminalRepository, deviceId, configManager.getTseMode())"
                    text.contains(inline) || text.contains("requireSigningTerminal(terminalRepository, dev, configManager.getTseMode())")
                }.map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "resolve the TSE mode into a val ABOVE the enclosing dbQuery and pass it in",
        )
    }

    @Test
    fun `TseMode DISABLED is still a legitimate configuration`() {
        // Documents the blast radius rather than asserting behaviour: assertMaySign returns early on
        // DISABLED after the isActive check, so a shop running without a TSE is unaffected by Stage
        // 4b except that a DEACTIVATED terminal can no longer sign. That is the case with no
        // legitimate reading — the terminal was switched off on purpose.
        assertTrue(TseMode.entries.contains(TseMode.DISABLED))
    }
}
