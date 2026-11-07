package org.example.project.admin.features.auditlog

import org.example.project.features.auditlog.presentation.auditRowHeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the collapsed audit row's headline decision.
 *
 * A failed row's `entityName` is always the constant "Operation failed due to error", so before this
 * every failure rendered identically and the real error was only visible after expanding the row.
 */
class AuditRowHeadlineTest {
    @Test
    fun failedRowShowsTheReasonInsteadOfTheConstant() {
        val headline =
            auditRowHeadline(
                entityId = "FAILED",
                entityName = "Operation failed due to error",
                reason = "ERROR: IllegalStateException: Bill 42 is already closed",
            )

        assertEquals("ERROR: IllegalStateException: Bill 42 is already closed", headline.text)
        assertTrue(headline.isFailure)
    }

    @Test
    fun failedRowWithoutAReasonFallsBackToEntityName() {
        val headline =
            auditRowHeadline(
                entityId = "FAILED",
                entityName = "Operation failed due to error",
                reason = null,
            )

        assertEquals("Operation failed due to error", headline.text)
        assertTrue(headline.isFailure)
    }

    /**
     * A refused invoice cancel now records the invoice it refused as its entity id instead of the
     * literal "FAILED" (E2E 2026-09-27). The row must still read as a failure.
     */
    @Test
    fun failedRowNamingItsEntityStillReadsAsAFailure() {
        val headline =
            auditRowHeadline(
                entityId = "42",
                entityName = "Operation failed due to error",
                reason = "ERROR: PosException: Invoice 42 is not issued",
            )

        assertEquals("ERROR: PosException: Invoice 42 is not issued", headline.text)
        assertTrue(headline.isFailure)
    }

    @Test
    fun successfulRowKeepsItsEntityNameEvenWhenItsReasonLooksLikeAnError() {
        // The "ERROR: " prefix is a message format, not a contract. Keying on it would mis-read this
        // successful row — which carries a cancellation reason — as a failure.
        val headline =
            auditRowHeadline(
                entityId = "4711",
                entityName = "Tisch 12 — Rechnung 4711",
                reason = "ERROR: storniert auf Wunsch des Gastes",
            )

        assertEquals("Tisch 12 — Rechnung 4711", headline.text)
        assertFalse(headline.isFailure)
    }

    @Test
    fun successfulRowWithoutAnEntityNameHasNoHeadline() {
        val headline = auditRowHeadline(entityId = "4711", entityName = null, reason = null)

        assertNull(headline.text)
        assertFalse(headline.isFailure)
    }

    @Test
    fun successfulRowNeverPromotesItsReasonEvenWithNoEntityName() {
        val headline =
            auditRowHeadline(
                entityId = "4711",
                entityName = null,
                reason = "Storniert: Gast hat abgebrochen",
            )

        assertNull(headline.text)
        assertFalse(headline.isFailure)
    }
}
