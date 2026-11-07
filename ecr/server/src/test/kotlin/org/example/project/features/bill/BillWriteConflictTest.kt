package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillWriteConflict
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A bill write still conflicting after its retries is a 409, never the 500 QA rt12 saw. */
@DisplayName("Residual bill-write conflicts map to 409 BILL_NUMBER_COLLISION")
class BillWriteConflictTest {
    private fun wrapped(
        state: String,
        message: String = "boom",
    ) = RuntimeException("exposed wrapper", SQLException(message, state))

    @Test
    fun `a serialization failure left after the retries is a 409`() {
        val conflict = assertNotNull(BillWriteConflict.toConflictOrNull(wrapped("40001")))
        assertEquals(PosErrorCode.BILL_NUMBER_COLLISION, conflict.code)
        assertEquals(HttpStatusCode.Conflict, conflict.httpStatus)
    }

    @Test
    fun `a duplicate bill_number is a 409, the exact error QA saw as a 500`() {
        val e = wrapped("23505", "ERROR: duplicate key value violates unique constraint \"bill_bill_number_unique\"")
        assertEquals(PosErrorCode.BILL_NUMBER_COLLISION, assertNotNull(BillWriteConflict.toConflictOrNull(e)).code)
    }

    @Test
    fun `an unrelated unique violation and a business error pass through unchanged`() {
        assertNull(BillWriteConflict.toConflictOrNull(wrapped("23505", "duplicate key on voucher_number")))
        assertNull(BillWriteConflict.toConflictOrNull(wrapped("23503")))
        assertNull(
            BillWriteConflict.toConflictOrNull(PosException(PosErrorCode.BILL_NOT_FOUND, HttpStatusCode.NotFound, "x")),
        )
        assertNull(BillWriteConflict.toConflictOrNull(IllegalStateException("no SQL here")))
    }

    @Test
    fun `retrying translates what is left once the retries are spent`() {
        var attempts = 0
        val thrown =
            assertFailsWith<PosException> {
                runBlocking {
                    BillWriteConflict.retrying("test") {
                        attempts++
                        throw SQLException("could not serialize access due to concurrent update", "40001")
                    }
                }
            }
        assertEquals(PosErrorCode.BILL_NUMBER_COLLISION, thrown.code)
        assertEquals(5, attempts, "RetryConfig.AGGRESSIVE retried before giving up")
    }
}
