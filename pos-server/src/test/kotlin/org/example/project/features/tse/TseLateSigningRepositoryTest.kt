package org.example.project.features.tse

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Nachsignierung record: first reason wins, signing and abandoning are stamped, never lost. */
class TseLateSigningRepositoryTest {
    private val repo = TseLateSigningRepositoryImpl()
    private val queuedAt = LocalDateTime.parse("2026-09-19T00:23:17")
    private val laterAt = LocalDateTime.parse("2026-09-19T02:23:17")

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `recordQueued keeps the FIRST reason when the same Vorgang is queued again`() {
        transaction {
            repo.recordQueued(TseEntityType.BILL, "5179", queuedAt, TseLateSigningReason.TSE_UNAVAILABLE, "TSE nicht erreichbar")
            repo.recordQueued(TseEntityType.BILL, "5179", laterAt, TseLateSigningReason.PENDING_SWEEP, "sweep")
        }

        val note = transaction { repo.noteFor(TseEntityType.BILL, "5179") }!!
        assertEquals("TSE_UNAVAILABLE", note.reasonCode)
        assertEquals("TSE nicht erreichbar", note.reasonText)
        assertEquals("2026-09-19T00:23:17", note.queuedAt)
        assertTrue(note.pending)
        assertFalse(note.abandoned)
    }

    @Test
    fun `markSigned turns the note into a permanent signed-late record`() {
        transaction { repo.recordQueued(TseEntityType.BILL, "5179", queuedAt, TseLateSigningReason.TSE_UNAVAILABLE, "x") }
        transaction { repo.markSigned(TseEntityType.BILL, "5179", laterAt, tseTransactionId = null) }

        val note = transaction { repo.noteFor(TseEntityType.BILL, "5179") }!!
        assertFalse(note.pending)
        assertEquals("2026-09-19T02:23:17", note.signedAt)
    }

    @Test
    fun `markAbandoned records a cancelled Nachsignierung`() {
        transaction { repo.recordQueued(TseEntityType.KASSENBUCH, "12", queuedAt, TseLateSigningReason.TSE_ERROR, "x") }
        transaction { repo.markAbandoned(TseEntityType.KASSENBUCH, "12", laterAt) }

        val note = transaction { repo.noteFor(TseEntityType.KASSENBUCH, "12") }!!
        assertFalse(note.pending)
        assertTrue(note.abandoned)
        assertNull(note.signedAt)
    }

    @Test
    fun `the same reference under another entity type is a separate Vorgang`() {
        transaction { repo.recordQueued(TseEntityType.BILL, "7", queuedAt, TseLateSigningReason.TSE_ERROR, "x") }

        assertNull(transaction { repo.noteFor(TseEntityType.ORDER, "7") })
    }

    @Test
    fun `a reason text longer than the column is truncated, not rejected`() {
        transaction { repo.recordQueued(TseEntityType.BILL, "8", queuedAt, TseLateSigningReason.TSE_ERROR, "x".repeat(900)) }

        assertEquals(500, transaction { repo.noteFor(TseEntityType.BILL, "8") }!!.reasonText.length)
    }
}
