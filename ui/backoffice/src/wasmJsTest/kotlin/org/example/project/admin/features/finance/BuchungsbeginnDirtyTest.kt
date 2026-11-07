package org.example.project.admin.features.finance

import org.example.project.admin.features.finance.presentation.isBuchungsbeginnDirty
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuchungsbeginnDirtyTest {
    @Test
    fun `the prefilled value is clean, also with surrounding blanks`() {
        assertFalse(isBuchungsbeginnDirty("2026-01-01", "2026-01-01"))
        assertFalse(isBuchungsbeginnDirty(" 2026-01-01 ", "2026-01-01"))
    }

    @Test
    fun `a different or cleared value is dirty`() {
        assertTrue(isBuchungsbeginnDirty("2026-02-01", "2026-01-01"))
        assertTrue(isBuchungsbeginnDirty("", "2026-01-01"))
    }
}
