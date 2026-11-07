package org.example.project.features.allocation

import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The enum names are stored as strings in `payment_allocation`, so renaming one silently orphans
 * every existing row. This pins the wire/storage form.
 */
class AllocationKindTest {
    @Test
    fun `source kinds are exactly the three payment origins`() {
        assertEquals(
            listOf("BANK_TRANSACTION", "CASH_BOOK_ENTRY", "TILL_RECEIPT"),
            AllocationSourceKind.entries.map { it.name },
        )
    }

    @Test
    fun `target kinds cover every thing a payment can settle`() {
        assertEquals(
            listOf(
                "INVOICE",
                "CONTRACT_INSTALMENT",
                "SALES_INVOICE",
                "CASH_TRANSIT",
                "INTERNAL_TRANSFER",
                "EXPENSE_NO_DOC",
                "FEE",
            ),
            AllocationTargetKind.entries.map { it.name },
        )
    }

    @Test
    fun `matched-by records how the allocation came about`() {
        assertEquals(
            listOf("MANUAL", "SUGGESTION_ACCEPTED", "AUTO_RULE"),
            AllocationMatchedBy.entries.map { it.name },
        )
    }
}
