package org.example.project.admin.features.tse

import org.example.project.admin.features.tse.presentation.TseFindingsCsv
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E round 4, R4-5: the register's CSV held only the current entries, so a corrected text vanished
 * from the file, and with no `id` column a row's `supersedes_id` pointed at nothing in it.
 */
class TseFindingsCsvTest {
    private fun entry(
        id: Long,
        text: String,
        supersedesId: Long? = null,
    ) = TseExplanationDto.ExplanationDto(
        id = id,
        tseSerialHex = "2D2F",
        tanrFrom = 4,
        tanrTo = 4,
        category = TseExplanationCategory.TANR_WITHOUT_POS_RECORD,
        text = text,
        source = TseExplanationSource.OPERATOR,
        createdAt = "2026-09-24T08:10:00",
        createdByEmployeeId = "4e856669",
        createdByEmployeeNumber = "A001",
        supersedesId = supersedesId,
    )

    @Test
    fun aCorrectedEntryStaysInTheFile_markedAndLinked() {
        val lines =
            TseFindingsCsv
                .build(listOf(entry(3, "Korrektur", supersedesId = 2), entry(2, "Erstfassung")))
                .split("\r\n")

        assertTrue(lines[0].startsWith("id;status;superseded_by;"), lines[0])
        assertEquals(3, lines.size, "header + both entries")
        assertTrue(lines[1].startsWith("2;SUPERSEDED;3;"), "the earlier text, pointing at its correction: ${lines[1]}")
        assertTrue(lines[1].endsWith("\"Erstfassung\""), lines[1])
        assertTrue(lines[2].startsWith("3;CURRENT;;"), lines[2])
        assertTrue(lines[2].contains(";A001;2;"), "author number and the id it supersedes: ${lines[2]}")
    }

    /** The screen shows a localized category name; the file is read by tools and keeps the enum name. */
    @Test
    fun theCategoryColumnKeepsTheMachineName() {
        val lines = TseFindingsCsv.build(listOf(entry(2, "Erstfassung"))).split("\r\n")

        assertTrue(lines[1].contains(";TANR_WITHOUT_POS_RECORD;"), lines[1])
    }
}
