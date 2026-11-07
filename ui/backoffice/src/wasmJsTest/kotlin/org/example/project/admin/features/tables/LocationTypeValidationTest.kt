package org.example.project.admin.features.tables

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.tables.presentation.LocationTypeField
import org.example.project.admin.features.tables.presentation.validateLocationTypeInput
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The location-type dialog's Save is never disabled; this is what pressing it on bad input reports. */
class LocationTypeValidationTest {
    @Test
    fun emptyForm_flagsTheNameOnly() {
        assertEquals(mapOf(LocationTypeField.NAME to CommonKeys.fieldRequired), validateLocationTypeInput("", "0"))
        assertEquals(mapOf(LocationTypeField.NAME to CommonKeys.fieldRequired), validateLocationTypeInput("  ", ""))
    }

    @Test
    fun blankSortOrder_stillMeansZero_butTextIsRefused() {
        assertTrue(validateLocationTypeInput("Terrasse", "").isEmpty())
        assertTrue(validateLocationTypeInput("Terrasse", " 3 ").isEmpty())
        assertTrue(validateLocationTypeInput("Terrasse", "-1").isEmpty())
        assertEquals(
            mapOf(LocationTypeField.SORT_ORDER to AdminStringKeys.LocationTypeSortOrderInvalid),
            validateLocationTypeInput("Terrasse", "zwei"),
        )
    }

    @Test
    fun errorsFollowOnScreenOrder() {
        val errors = validateLocationTypeInput("", "x")
        assertEquals(LocationTypeField.entries.toList(), LocationTypeField.entries.filter { it in errors })
    }
}
