package org.example.project.features.salesinvoice

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.data.service.ArNumberConflict
import org.example.project.infrastructure.errors.PosException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A sales-invoice numbering race that the database reports rather than resolves is a 409 the
 * client may retry, never a 500 — and nothing else is swept into that 409.
 */
class SalesInvoiceNumberConflictTest {
    private fun wrapped(
        state: String,
        message: String = "",
    ) = RuntimeException("exposed", SQLException(message, state))

    @Test
    fun `serialization failure and deadlock are a 409`() {
        for (state in listOf("40001", "40P01", "90131")) {
            val conflict = ArNumberConflict.toConflictOrNull(wrapped(state))
            assertEquals(PosErrorCode.SALES_INVOICE_NUMBER_COLLISION, conflict?.code, state)
            assertEquals(HttpStatusCode.Conflict, conflict?.httpStatus, state)
        }
    }

    @Test
    fun `a duplicate counter row or invoice number is a 409`() {
        for (constraint in listOf("ar_number_sequence_prefix_year_unique", "sales_invoice_invoice_number_unique")) {
            val e = wrapped("23505", "duplicate key value violates unique constraint \"$constraint\"")
            assertEquals(PosErrorCode.SALES_INVOICE_NUMBER_COLLISION, ArNumberConflict.toConflictOrNull(e)?.code)
        }
    }

    @Test
    fun `other unique violations, other SQL errors and PosExceptions are left alone`() {
        assertNull(ArNumberConflict.toConflictOrNull(wrapped("23505", "violates \"sales_invoice_one_live_per_bill\"")))
        assertNull(ArNumberConflict.toConflictOrNull(wrapped("23503", "foreign key")))
        assertNull(ArNumberConflict.toConflictOrNull(IllegalStateException("no SQL at all")))
        assertNull(
            ArNumberConflict.toConflictOrNull(
                PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "bad", cause = SQLException("x", "40001")),
            ),
        )
    }
}
