package org.example.project.features.salesinvoice.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.sql.SQLException

/**
 * Turns a sales-invoice issuance that lost a race for its §14 number into 409
 * `SALES_INVOICE_NUMBER_COLLISION` instead of a 500.
 *
 * The number comes from the year's `ar_number_sequence` row, created on first use with `INSERT …
 * ON CONFLICT DO NOTHING` and then locked `FOR UPDATE`, so under the default READ COMMITTED the
 * first issuances of a year no longer collide at all. What can remain is contention the database
 * reports rather than resolves — a serialization failure or deadlock — and, as a last line of
 * defence, a unique violation on the counter or on `sales_invoice.invoice_number`. In every case
 * the transaction rolled back, so the number was not consumed and retrying the request is safe.
 */
object ArNumberConflict {
    /** Postgres serialization failure / deadlock, and H2's "concurrent update" (the test engine). */
    private val CONFLICT_STATES = setOf("40001", "40P01", "90131")

    private const val UNIQUE_VIOLATION = "23505"

    private val NUMBER_CONSTRAINTS = listOf("ar_number_sequence", "invoice_number")

    /** The 409 for [e], or null when [e] is not a numbering conflict. */
    fun toConflictOrNull(e: Throwable): PosException? {
        if (e is PosException) return null
        val sql = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull() ?: return null
        val state = sql.sqlState ?: return null
        val message = sql.message.orEmpty()
        val isConflict =
            state in CONFLICT_STATES ||
                (state == UNIQUE_VIOLATION && NUMBER_CONSTRAINTS.any { message.contains(it, ignoreCase = true) })
        if (!isConflict) return null
        return PosException(
            PosErrorCode.SALES_INVOICE_NUMBER_COLLISION,
            HttpStatusCode.Conflict,
            "Another sales invoice was numbered at the same moment and this one could not be; retry the request",
            cause = e,
        )
    }
}
