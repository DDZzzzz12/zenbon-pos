package org.example.project.admin.features.debtors.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebtorDetailStateTest {
    private val dirty =
        DebtorDetailState(id = 7, form = DebtorForm(name = "typed"), pristine = DebtorForm(name = "saved"))

    @Test
    fun saveNeedsADirtyFormThatIsNeitherSavingNorLoading() {
        assertTrue(dirty.canSave)
        assertFalse(dirty.copy(isSaving = true).canSave)
        // the stale-dirty frame: the previous debtor's edits still in state while another loads
        assertFalse(dirty.copy(isLoading = true).canSave)
        assertFalse(DebtorDetailState(id = 7).canSave)
    }

    @Test
    fun theCreateFormIsSavableEmptySoPressingSaveCanSayWhatIsMissing() {
        // UI-E2-06: an empty create form used to show a grey Save and no hint why.
        assertTrue(DebtorDetailState().canSave)
        assertFalse(DebtorDetailState(isSaving = true).canSave)
    }

    @Test
    fun aDraftIsHeldOnlyForTheSameDebtorAndOnlyWhileDirty() {
        assertTrue(dirty.holdsDraftFor(7))
        assertFalse(dirty.holdsDraftFor(8))
        assertFalse(DebtorDetailState(id = 7).holdsDraftFor(7))
    }
}
