package org.example.project.printer

import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.utils.formatter.tseSigningNoteLines
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The Beleg lines for a bill that could not be TSE-signed immediately (Nachsignierung). */
class TseSigningNoteLinesTest {
    private val pending =
        TseSigningNoteDto(
            pending = true,
            reasonCode = "TSE_UNAVAILABLE",
            reasonText = "TSE_HARDWARE_NOT_FOUND: TSE hardware not found",
            queuedAt = "2026-09-19T17:45:43.123",
        )

    @Test
    fun `a pending bill says the TSE is unreachable and the Beleg will be signed later`() {
        assertEquals(
            listOf("TSE nicht erreichbar –", "Beleg wird nachsigniert", "Grund: TSE nicht erreichbar"),
            tseSigningNoteLines(pending),
        )
    }

    @Test
    fun `a late-signed bill says when it was signed`() {
        val lines = tseSigningNoteLines(pending.copy(pending = false, signedAt = "2026-09-19T19:02:05.5"))

        assertEquals("Nachsigniert am 19.09.2026 19:02:05", lines.first())
    }

    @Test
    fun `an abandoned Nachsignierung is stated`() {
        assertEquals("Nachsignierung abgebrochen", tseSigningNoteLines(pending.copy(pending = false, abandoned = true)).first())
    }

    @Test
    fun `the raw server error never reaches the Beleg, an unknown code prints a neutral label`() {
        val lines = tseSigningNoteLines(pending.copy(reasonCode = "SOMETHING_NEW"))

        assertEquals("Grund: nicht erfasst", lines.last())
    }
}
