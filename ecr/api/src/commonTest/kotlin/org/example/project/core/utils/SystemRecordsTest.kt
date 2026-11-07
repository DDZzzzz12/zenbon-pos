package org.example.project.core.utils

import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.table.LocationTypeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemRecordsTest {
    private fun locationType(
        id: Long,
        isSystem: Boolean,
    ) = LocationTypeDto.ResponseDto(
        id = id,
        name = "LT$id",
        englishName = "LT$id",
        chineseName = "LT$id",
        isVirtual = isSystem,
        isTakeaway = false,
        sortOrder = id.toInt(),
        isSystem = isSystem,
    )

    @Test
    fun pfandAndVoucherCategoriesAreSystemRecords() {
        assertTrue(SystemRecords.isSystemCategory("PFAND"))
        assertTrue(SystemRecords.isSystemCategory("VOUCHER"))
        assertTrue(SystemRecords.isSystemCategory(" VOUCHER "), "the server trims category names")
        assertFalse(SystemRecords.mayEditCategory("PFAND"))
        assertFalse(SystemRecords.mayEditCategory("VOUCHER"))
    }

    @Test
    fun ordinaryCategoriesStayEditable() {
        assertTrue(SystemRecords.mayEditCategory("Drinks"))
        assertTrue(SystemRecords.mayEditCategory("pfand"), "names are matched exactly, as the server seeds them")
        assertTrue(SystemRecords.mayEditCategory("Voucher Specials"))
    }

    @Test
    fun theVoucherArticleIsTheOnlySystemProduct() {
        assertFalse(SystemRecords.mayEditProduct("VOUCHER"))
        assertTrue(SystemRecords.mayEditProduct("FOOD"))
        assertTrue(SystemRecords.mayEditProduct("DRINKS"))
        assertTrue(SystemRecords.mayEditProduct("OTHERS"))
    }

    @Test
    fun theSeededVirtualLocationTypesAreSystemIds() {
        assertTrue(SystemRecords.isSystemLocationTypeId(WellKnownLocationTypes.VOUCHER))
        assertTrue(SystemRecords.isSystemLocationTypeId(WellKnownLocationTypes.QUICK_SALE))
        assertFalse(SystemRecords.isSystemLocationTypeId(WellKnownLocationTypes.INDOOR))
        assertFalse(SystemRecords.isSystemLocationTypeId(WellKnownLocationTypes.TAKEAWAY))
    }

    @Test
    fun selectableLocationTypesDropsSystemRowsOnly() {
        val all = listOf(locationType(1, false), locationType(4, true), locationType(5, true), locationType(6, false))
        assertEquals(listOf(1L, 6L), SystemRecords.selectableLocationTypes(all).map { it.id })
    }
}
