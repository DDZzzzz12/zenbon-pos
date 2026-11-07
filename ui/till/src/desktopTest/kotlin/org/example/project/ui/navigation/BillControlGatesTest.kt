package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Design §3.4: every bill-side control on an order station's path is gated on a CAPABILITY the
 * server also names (rule 8 — the server refuses these on the mask; this is the UI half). The
 * sweep of 2026-09-06 found two controls with no gate at all; this pins the gate to the source so
 * the next refactor cannot quietly drop it. Source-scanned like NoRoleGatesInScreensTest, because
 * both controls sit behind Koin view models that a composition test cannot stand up cheaply.
 *
 * **What is asserted, and why it is not the literal body.** Two questions:
 *  1. Is the capability asked for at all — `holds(Capability.X)`. That names the thing under test
 *     and survives every rename.
 *  2. Does the answer reach the control. This is asked through the gate val's OWN name, read out of
 *     the declaration rather than written down here, and looked for near the control it protects.
 *
 * The first version of (2) pinned literal source lines (`"if (canChangePayment && " +
 * "bill.isRebookable"` in today's terms). Renaming the val, letting ktlint rewrap the condition, or
 * converting the block to `CapabilityGate(...)` — the idiom `CapabilityGates.kt` says to prefer —
 * each broke the test with a message accusing the author of deleting a gate that was still there.
 * A test that punishes the improvement it asks for gets deleted, and the rot it guards against
 * arrives afterwards. The rot itself is narrow and stays caught: a `holds` line that survives while
 * its `if` does not, leaving the capability read into a val nobody uses.
 */
class BillControlGatesTest {
    private val ui = java.io.File("src/commonMain/kotlin/org/example/project/ui")

    private fun source(relative: String): String {
        val file = ui.resolve(relative)
        assertTrue(file.isFile, "expected ${file.path} to exist")
        return file.readText()
    }

    /**
     * The name the file gave its `holds(Capability.[capability])` answer. The capability may be
     * combined with another condition in the same val (`val canX = billingOffered && holds(...)` —
     * the station never bills, OS-b); what matters is that the answer lands in a val the control uses.
     */
    private fun gateName(
        text: String,
        capability: String,
        where: String,
    ): String {
        val declaration = Regex("""val\s+(\w+)\s*=\s*(?:[\w.]+\s*&&\s*)*holds\(Capability\.$capability\)""").find(text)
        assertTrue(declaration != null, "$where must read holds(Capability.$capability) into a val")
        return declaration.groupValues[1]
    }

    /**
     * Whether every occurrence of [anchor] is guarded — the gate val, or a `CapabilityGate`, within
     * [window] lines either side of it.
     *
     * Both directions, because a gate sits above the thing it protects (`if (gate) { control }`)
     * or just below its name (`onChangePayment =` on one line, the guarding `if` on the next). One
     * direction would make this a test of formatting, which is the trap the previous version fell
     * into.
     */
    private fun isGuarded(
        text: String,
        anchor: String,
        gate: String,
        window: Int = 4,
    ): Boolean {
        val lines = text.lines()
        val anchors = lines.indices.filter { anchor in lines[it] }
        assertTrue(anchors.isNotEmpty(), "expected the source to contain `$anchor`")
        return anchors.all { at ->
            lines
                .subList(maxOf(0, at - window), minOf(lines.size, at + window + 1))
                .any { Regex("""\b$gate\b""").containsMatchIn(it) || "CapabilityGate(" in it }
        }
    }

    /**
     * The names of the nullable callbacks the overlay builds behind [gate] —
     * `val openX: (() -> Unit)? = if (gate && …) { … } else null`. One such callback feeds both the
     * panel button and the Aktionen menu entry, so it counts as the gate wherever it is handed on.
     */
    private fun gatedCallbacks(
        text: String,
        gate: String,
    ): List<String> {
        val lines = text.lines()
        return lines.indices.mapNotNull { at ->
            val name = Regex("""val\s+(\w+)\s*:\s*\(\(\)\s*->\s*Unit\)\?\s*=""").find(lines[at])?.groupValues?.get(1)
            name?.takeIf { lines.subList(at, minOf(lines.size, at + 3)).any { Regex("""\b$gate\b""").containsMatchIn(it) } }
        }
    }

    /** Every `anchor` in the overlay is guarded by the capability's gate or by a callback built behind it. */
    private fun assertOverlayGates(
        capability: String,
        anchors: List<String>,
    ) {
        val text = source("components/PosBillReceiptOverlay.kt")
        assertTrue(text.contains("holds(Capability.$capability)"), "PosBillReceiptOverlay must ask for $capability")
        // The capability must reach the control, not merely be read into an unused val — that is
        // exactly how a gate rots: the `holds` line survives a refactor and the `if` does not.
        val gate = gateName(text, capability, "PosBillReceiptOverlay")
        val guards = listOf(gate) + gatedCallbacks(text, gate)
        anchors.forEach { anchor ->
            assertTrue(
                isGuardedByAny(text, anchor, guards),
                "PosBillReceiptOverlay reads $capability into `$gate` but hands `$anchor` a callback without consulting it",
            )
        }
    }

    private fun isGuardedByAny(
        text: String,
        anchor: String,
        guards: List<String>,
    ): Boolean {
        val lines = text.lines()
        val at = lines.indices.filter { anchor in lines[it] }
        assertTrue(at.isNotEmpty(), "expected the source to contain `$anchor`")
        return at.all { i ->
            lines
                .subList(maxOf(0, i - 4), minOf(lines.size, i + 5))
                .any { line -> "CapabilityGate(" in line || guards.any { Regex("""\b$it\b""").containsMatchIn(line) } }
        }
    }

    @Test
    fun `change tender is gated on BILL_CHANGE_PAYMENT`() {
        // POST /api/v1/bills/{id}/rebook (Zahlartwechsel) is Cap(BILL_CHANGE_PAYMENT); the station
        // mask holds no BILL_*. Before this the button was offered to anyone who could open the panel.
        // Zahlartwechsel is only reachable through the Aktionen menu now, so only its `onChangePayment =`
        // assignment (feeding PosBillCreatedActionsDialog) is covered.
        assertOverlayGates("BILL_CHANGE_PAYMENT", listOf("onChangePayment ="))
    }

    @Test
    fun `Storno is gated on BILL_VOID in the Aktionen menu`() {
        // DELETE /api/v1/bills/{id} is Cap(BILL_VOID). Storno is only reachable through the Aktionen
        // menu now, so only `onStorno =` (feeding PosBillCreatedActionsDialog) is covered.
        assertOverlayGates("BILL_VOID", listOf("onStorno ="))
    }

    @Test
    fun `the panel receives neither the Storno nor the Zahlartwechsel callback`() {
        // 2026-10: "Stornieren" and "Zahlungsart ändern" moved into the Aktionen menu only. The panel
        // must never again be handed `onDelete`/`onChangePayment` — that was the ungated-looking path
        // this decision removed (the panel buttons used the same gated callbacks, but a reviewer
        // scanning PosBillDetailPanel's call site alone could not see that).
        val text = source("components/PosBillReceiptOverlay.kt")
        val panelCallRegex = Regex("""PosBillDetailPanel\((?s:.*?)\n\s{16}\)""")
        val panelCall = panelCallRegex.find(text)
        assertTrue(panelCall != null, "expected to find the PosBillDetailPanel(...) call in the overlay")
        assertTrue(
            "onDelete" !in panelCall.value && "onChangePayment" !in panelCall.value,
            "PosBillDetailPanel must not be handed onDelete/onChangePayment any more; " +
                "Storno/Zahlartwechsel belong only to the Aktionen menu",
        )
    }

    @Test
    fun `the Aktionen menu offers Storno and Zahlungsart aendern only through the host's callbacks`() {
        // The menu holds no gate of its own: it renders the two entries only when the host handed it
        // a callback, and the host hands it the same gated callback as the panel button. A default
        // other than null, or an entry outside the `?.let`, would offer the control to everyone.
        val text = source("components/PosBillCreatedActionsDialog.kt")
        assertTrue(text.contains("onStorno: (() -> Unit)? = null"), "onStorno must default to not offered")
        assertTrue(text.contains("onChangePayment: (() -> Unit)? = null"), "onChangePayment must default to not offered")
        assertTrue(isGuarded(text, "BillReceiptKeys.deleteButton", "onStorno", window = 2))
        assertTrue(isGuarded(text, "BillReceiptKeys.changePaymentButton", "onChangePayment", window = 2))
    }

    @Test
    fun `the bills tab of the table history is gated on BILL_READ`() {
        // GET /api/v1/bills/table-session/{id} is Cap(BILL_READ). Without the gate a station's
        // history screen loads a tab that can only ever show a 403.
        val text = source("screens/dashboard/history/PosTableHistoryScreen.kt")
        assertTrue(
            text.contains("holds(Capability.BILL_READ)"),
            "PosTableHistoryScreen must gate the Bills tab on BILL_READ",
        )
        val gate = gateName(text, "BILL_READ", "PosTableHistoryScreen")
        assertTrue(
            isGuarded(text, "viewModel.loadBills(", gate),
            "PosTableHistoryScreen fetches bills without consulting `$gate`",
        )
        assertTrue(
            isGuarded(text, "PosSegmentedButtonRow(", gate),
            "PosTableHistoryScreen renders the Orders/Bills toggle without consulting `$gate`; " +
                "a one-option toggle whose second option can only 403 is worse than no toggle",
        )
    }
}
