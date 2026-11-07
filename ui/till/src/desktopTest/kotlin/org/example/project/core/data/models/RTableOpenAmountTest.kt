package org.example.project.core.data.models

import org.example.project.core.domain.dto.table.RTableDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RTableOpenAmountTest {
    private fun dto(openAmount: String?) =
        RTableDto.RTableResponseDto(
            id = 1L,
            tableNumber = "1",
            seats = 4,
            locationTypeId = 1L,
            locationTypeName = "Indoor",
            locationTypeEnglishName = "Indoor",
            locationTypeChineseName = "室内",
            isVirtual = false,
            isTakeaway = false,
            tableStatus = "OCCUPIED",
            currentSessionId = 9L,
            hasActiveSession = true,
            positionX = 0f,
            positionY = 0f,
            rotation = 0f,
            openAmount = openAmount,
        )

    @Test
    fun `fromDto maps openAmount when present`() {
        assertEquals("48.50", RTable.fromDto(dto("48.50")).openAmount)
    }

    @Test
    fun `fromDto maps null openAmount`() {
        assertNull(RTable.fromDto(dto(null)).openAmount)
    }
}
