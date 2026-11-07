package org.example.project.features.bill.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import java.sql.SQLException

/**
 * Turns a bill write that still conflicts after its retries into 409 `BILL_NUMBER_COLLISION`.
 *
 * Bill numbers are drawn from a counter row locked `FOR UPDATE` (`bill_number_sequence`), so
 * parallel bills from one shop serialise on it. Under REPEATABLE_READ every waiter that lost the
 * race gets a serialization failure and is retried with a fresh snapshot; with enough bills in
 * flight at once a request can exhaust [RetryConfig.AGGRESSIVE]. That is contention, not a server
 * fault: it surfaced as a 500 (real-TSE QA rt12, together with the duplicate key the old `count(*)`
 * produced), while the bill routes promise 409. A unique violation on `bill_number` is mapped the
 * same way as a last line of defence. Nothing was committed in either case, so retrying is safe.
 */
object BillWriteConflict {
    /** Postgres serialization failure / deadlock, and H2's "concurrent update" (the test engine). */
    private val CONFLICT_STATES = setOf("40001", "40P01", "90131")

    private const val UNIQUE_VIOLATION = "23505"

    /** [withRetryThrows] with [RetryConfig.AGGRESSIVE], translating what is left after the retries. */
    suspend fun <T> retrying(
        operationName: String,
        block: suspend () -> T,
    ): T =
        try {
            withRetryThrows(operationName = operationName, config = RetryConfig.AGGRESSIVE, block = block)
        } catch (e: Exception) {
            throw toConflictOrNull(e) ?: e
        }

    /** The 409 for [e], or null when [e] is not a bill-write conflict. */
    fun toConflictOrNull(e: Throwable): PosException? {
        if (e is PosException) return null
        val sql = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull() ?: return null
        val state = sql.sqlState ?: return null
        val isConflict =
            state in CONFLICT_STATES ||
                (state == UNIQUE_VIOLATION && sql.message.orEmpty().contains("bill_number", ignoreCase = true))
        if (!isConflict) return null
        return PosException(
            PosErrorCode.BILL_NUMBER_COLLISION,
            HttpStatusCode.Conflict,
            "Another bill was created at the same moment and this one could not be numbered; retry the request",
            cause = e,
        )
    }
}
