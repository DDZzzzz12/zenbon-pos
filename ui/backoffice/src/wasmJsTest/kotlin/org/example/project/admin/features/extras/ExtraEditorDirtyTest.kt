package org.example.project.admin.features.extras

import org.example.project.admin.features.extras.presentation.ExtraEditor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtraEditorDirtyTest {
    private val opened = ExtraEditor(id = 1, name = "Extra cheese", price = "0.50")

    @Test fun untouchedEditorIsClean() = assertFalse(opened.isDirtyComparedTo(opened.copy()))

    @Test fun noOriginalIsClean() = assertFalse(opened.isDirtyComparedTo(null))

    @Test fun editedFieldIsDirty() = assertTrue(opened.copy(price = "0.60").isDirtyComparedTo(opened))

    @Test fun editThenUndoIsClean() = assertFalse(opened.copy(price = "0.60").copy(price = "0.50").isDirtyComparedTo(opened))

    @Test fun transientFlagsAreIgnored() = assertFalse(opened.copy(saving = true, fieldError = "price").isDirtyComparedTo(opened))
}
