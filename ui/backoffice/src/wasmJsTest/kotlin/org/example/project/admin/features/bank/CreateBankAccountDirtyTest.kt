package org.example.project.admin.features.bank

import org.example.project.admin.features.bank.presentation.isCreateBankAccountDirty
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateBankAccountDirtyTest {
    @Test
    fun `an empty or whitespace-only form is clean`() {
        assertFalse(isCreateBankAccountDirty("", "", "", "", ""))
        assertFalse(isCreateBankAccountDirty(" ", "", "  ", "", ""))
    }

    @Test
    fun `any non-blank field makes it dirty`() {
        assertTrue(isCreateBankAccountDirty("DE02", "", "", "", ""))
        assertTrue(isCreateBankAccountDirty("", "", "", "", "1200"))
    }
}
