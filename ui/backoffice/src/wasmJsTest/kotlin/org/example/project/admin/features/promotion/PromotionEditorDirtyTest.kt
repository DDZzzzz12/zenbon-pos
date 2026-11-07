package org.example.project.admin.features.promotion

import org.example.project.admin.features.promotion.presentation.PromotionEditor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotionEditorDirtyTest {
    private val opened = PromotionEditor(id = 1, name = "Happy hour", validFromDate = "2026-10-01")

    @Test fun untouchedEditorIsClean() = assertFalse(opened.isDirtyComparedTo(opened.copy()))

    @Test fun noOriginalIsClean() = assertFalse(opened.isDirtyComparedTo(null))

    @Test fun editedFieldIsDirty() = assertTrue(opened.copy(validUntilDate = "2026-12-31").isDirtyComparedTo(opened))

    @Test fun editThenUndoIsClean() = assertFalse(opened.copy(name = "Late").copy(name = "Happy hour").isDirtyComparedTo(opened))

    @Test fun transientFlagsAreIgnored() = assertFalse(opened.copy(saving = true, fieldError = "date").isDirtyComparedTo(opened))
}
