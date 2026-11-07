package org.example.project.repository

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Guards the per-device uniqueness of `tse_transaction` (Exposed composite index
 * `tse_transaction_serial_number_unique`, mirrored by Flyway V85).
 *
 * Regression: the original schema had a GLOBAL unique on `transaction_number`, which
 * rejected valid receipts from a new/replacement TSE whose counter restarts at 1 and
 * collides with numbers left by the previous device:
 *   duplicate key value violates unique constraint "tse_transaction_transaction_number_unique"
 *   Key (transaction_number)=(3) already exists.
 *
 * Uses the H2-backed test app (schema created by Exposed, MODE=PostgreSQL) so the
 * composite index under test is the real one a fresh install gets.
 */
class TseTransactionUniqueIndexTest {
    private fun insertTx(
        serial: String,
        txNumber: Long,
    ) = transaction {
        TseTransactionTable.insert {
            it[tseSerialNumber] = serial
            it[transactionNumber] = txNumber
            it[processType] = "Kassenbeleg-V1"
            it[tseMode] = TseMode.NORMAL
        }
    }

    @Test
    fun `same transaction number is allowed across different TSE serials`() {
        testApp(
            setupData = {
                // Old device used numbers 1..3.
                insertTx(serial = "OLD-TSE-SERIAL", txNumber = 1)
                insertTx(serial = "OLD-TSE-SERIAL", txNumber = 2)
                insertTx(serial = "OLD-TSE-SERIAL", txNumber = 3)

                // New device restarts at 1 — previously this collided on the global unique.
                insertTx(serial = "NEW-TSE-SERIAL", txNumber = 1)
                insertTx(serial = "NEW-TSE-SERIAL", txNumber = 2)
                insertTx(serial = "NEW-TSE-SERIAL", txNumber = 3)

                transaction {
                    assertEquals(6L, TseTransactionTable.selectAll().count())
                }
            },
        ) { _ -> }
    }

    @Test
    fun `duplicate transaction number within the same TSE serial is rejected`() {
        testApp(
            setupData = {
                insertTx(serial = "TSE-A", txNumber = 7)
                assertFailsWith<ExposedSQLException> {
                    insertTx(serial = "TSE-A", txNumber = 7)
                }
            },
        ) { _ -> }
    }
}
