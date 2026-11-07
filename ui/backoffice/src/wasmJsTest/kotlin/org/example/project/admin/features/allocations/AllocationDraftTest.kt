package org.example.project.admin.features.allocations

import org.example.project.admin.features.allocations.presentation.canonicalAmountOrNull
import org.example.project.admin.features.allocations.presentation.draftKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The client half of the over-allocation guard (root CLAUDE.md rule 8 — the server enforces the
 * same rule with a 409). Pure and separate from the ViewModel so the cases are cheap to state.
 */
class AllocationDraftTest {
    @Test
    fun `a plain amount within the residual is canonicalised`() {
        assertEquals("60.50", canonicalAmountOrNull("60.50", restOpen = "100.0000"))
    }

    @Test
    fun `a comma decimal separator is accepted`() {
        assertEquals("60.50", canonicalAmountOrNull("60,50", restOpen = "100.0000"))
    }

    @Test
    fun `exactly the residual is allowed`() {
        assertEquals("100.0000", canonicalAmountOrNull("100.0000", restOpen = "100.0000"))
    }

    @Test
    fun `blank, unparseable, zero and negative are all refused`() {
        for (bad in listOf("", "   ", "abc", "0", "0.00", "-5")) {
            assertNull(canonicalAmountOrNull(bad, restOpen = "100.0000"), "amount '$bad'")
        }
    }

    @Test
    fun `more than the residual is refused`() {
        assertNull(canonicalAmountOrNull("100.01", restOpen = "100.0000"))
    }

    @Test
    fun `an unparseable residual refuses everything rather than allowing everything`() {
        assertNull(canonicalAmountOrNull("10.00", restOpen = "nicht-eine-zahl"))
    }

    @Test
    fun `the same id under two kinds keys two different drafts`() {
        assertNotEquals(draftKey("INVOICE", 7), draftKey("CONTRACT_INSTALMENT", 7))
    }

    @Test
    fun `a terminal target keys on its kind alone`() {
        assertEquals("FEE", draftKey("FEE", null))
    }
}
