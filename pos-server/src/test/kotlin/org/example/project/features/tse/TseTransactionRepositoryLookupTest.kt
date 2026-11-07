package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The signer's repository lookups: current-by-reference (never SUPERSEDED, newest first),
 * legacy-signed fallback (entity_type NULL, matched by exact process data), and the two
 * supersede-bookkeeping writes.
 */
class TseTransactionRepositoryLookupTest {
    private val repo = TseTransactionRepositoryImpl()

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun newTransaction(
        transactionNumber: Long,
        referenceId: String?,
        entityType: String?,
        processDataHash: String? = "process-data",
        signature: String? = "sig",
        state: TseTransactionState = TseTransactionState.FINISHED,
        processType: String = "Kassenbeleg-V1",
    ): TseTransaction =
        TseTransaction.new {
            this.tseSerialNumber = "SIM-1"
            this.transactionNumber = transactionNumber
            this.processType = processType
            this.tseMode = TseMode.SIMULATION
            this.referenceId = referenceId
            this.entityType = entityType
            this.processDataHash = processDataHash
            this.signature = signature
            this.state = state
        }

    @Test
    fun `findCurrentByReference skips a SUPERSEDED row even when it is the newest`() {
        transaction {
            // The FINISHED row is created FIRST (lower id); the SUPERSEDED row is created LAST
            // (highest id), so a plain "newest by id" lookup with no state filter would wrongly
            // return it. The filter must reject it and fall back to the older FINISHED row.
            newTransaction(1L, "5179", "BILL")
            newTransaction(2L, "5179", "BILL", state = TseTransactionState.SUPERSEDED)
        }

        val result = transaction { runBlocking { repo.findCurrentByReference("5179", "BILL") } }

        assertEquals(1L, result?.transactionNumber)
    }

    @Test
    fun `findCurrentByReference does not match a different entity type`() {
        transaction { newTransaction(1L, "5179", "BILL") }

        val result = transaction { runBlocking { repo.findCurrentByReference("5179", "ORDER") } }

        assertNull(result)
    }

    @Test
    fun `findLegacySigned finds a signed row with null entity_type and exact process data`() {
        transaction { newTransaction(1L, "5179", null, processDataHash = "exact-payload") }

        val found = transaction { runBlocking { repo.findLegacySigned("5179", "Kassenbeleg-V1", "exact-payload") } }
        assertEquals(1L, found?.transactionNumber)

        val notFound = transaction { runBlocking { repo.findLegacySigned("5179", "Kassenbeleg-V1", "different-payload") } }
        assertNull(notFound)
    }

    @Test
    fun `findLegacySigned does not adopt a row signed for a different process type`() {
        transaction { newTransaction(1L, "5179", null, processDataHash = "exact-payload", processType = "Bestellung-V1") }

        assertNull(
            transaction { runBlocking { repo.findLegacySigned("5179", "Kassenbeleg-V1", "exact-payload") } },
            "one reference id can carry Vorgänge of different kinds; the payload alone must not adopt across them",
        )
    }

    @Test
    fun `findLegacySigned adopts the OLDEST matching row, deterministically`() {
        transaction {
            newTransaction(7L, "5179", null, processDataHash = "exact-payload")
            newTransaction(8L, "5179", null, processDataHash = "exact-payload")
        }

        repeat(2) {
            assertEquals(
                7L,
                transaction { runBlocking { repo.findLegacySigned("5179", "Kassenbeleg-V1", "exact-payload") } }?.transactionNumber,
                "an unordered firstOrNull() could hand back either row and change its mind between calls",
            )
        }
    }

    @Test
    fun `markSuperseded sets state and linkReplacement sets supersededById`() {
        val (originalId, replacementId) =
            transaction {
                val original = newTransaction(1L, "5179", "BILL")
                val replacement = newTransaction(2L, "5179", "BILL")
                original.id.value to replacement.id.value
            }

        transaction { runBlocking { repo.markSuperseded(originalId) } }
        transaction { runBlocking { repo.linkReplacement(originalId, replacementId) } }

        transaction {
            val original = TseTransaction.findById(originalId)!!
            assertEquals(TseTransactionState.SUPERSEDED, original.state)
            assertEquals(replacementId, original.supersededById)
        }
    }
}
