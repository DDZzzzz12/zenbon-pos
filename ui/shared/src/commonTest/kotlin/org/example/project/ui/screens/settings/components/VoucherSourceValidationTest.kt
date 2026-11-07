package org.example.project.ui.screens.settings.components

import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoucherSourceValidationTest {
    @Test
    fun emptyNameIsRequired() {
        val errors = validateVoucherSourceDraft(VoucherSourceType.LEGACY, "  ", isEditMode = false, existingTypes = emptyList())
        assertEquals(mapOf(VoucherSourceField.NAME to CommonKeys.fieldRequired), errors)
    }

    @Test
    fun addingAnAlreadyConfiguredTypeIsATypeError() {
        val errors =
            validateVoucherSourceDraft(
                VoucherSourceType.INTERNAL,
                "",
                isEditMode = false,
                existingTypes = listOf(VoucherSourceType.INTERNAL),
            )
        assertEquals(
            mapOf(VoucherSourceField.TYPE to SettingsKeys.voucherSourceTypeTaken, VoucherSourceField.NAME to CommonKeys.fieldRequired),
            errors,
        )
    }

    @Test
    fun editModeNeverChecksTheType() {
        val errors =
            validateVoucherSourceDraft(
                VoucherSourceType.INTERNAL,
                "Internal",
                isEditMode = true,
                existingTypes = listOf(VoucherSourceType.INTERNAL),
            )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aNamedFreeTypeIsSavable() {
        val errors =
            validateVoucherSourceDraft(
                VoucherSourceType.GIFT_CARD,
                "Gift cards",
                isEditMode = false,
                existingTypes = listOf(VoucherSourceType.INTERNAL),
            )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun theFieldOrderIsTheOnScreenOrder() = assertEquals(listOf(VoucherSourceField.TYPE, VoucherSourceField.NAME), VoucherSourceField.entries)
}
