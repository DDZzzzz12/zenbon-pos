package org.example.project.admin.features.product

import org.example.project.admin.features.product.presentation.CategoryEditor
import org.example.project.admin.features.product.presentation.ProductEditor
import org.example.project.core.utils.file.PickedFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A form is dirty only when it differs from what it was opened (or last loaded) with. */
class ProductEditorDirtyTest {
    private val opened = ProductEditor(id = 1, productNumber = "P1", name = "Cola", price = "2.50", categoryId = 3)

    @Test fun untouchedEditorIsClean() = assertFalse(opened.isDirtyComparedTo(opened.copy()))

    @Test fun noOriginalIsClean() = assertFalse(opened.isDirtyComparedTo(null))

    @Test fun editedFieldIsDirty() = assertTrue(opened.copy(name = "Cola Zero").isDirtyComparedTo(opened))

    @Test fun editThenUndoIsClean() {
        val edited = opened.copy(name = "Cola Zero")
        assertTrue(edited.isDirtyComparedTo(opened))
        assertFalse(edited.copy(name = "Cola").isDirtyComparedTo(opened))
    }

    @Test fun transientFlagsAreIgnored() {
        assertFalse(opened.copy(saving = true, fieldError = "required").isDirtyComparedTo(opened))
    }

    @Test fun attachedGroupChangeIsDirty() = assertTrue(opened.copy(attachedGroupIds = listOf(4)).isDirtyComparedTo(opened))

    @Test fun pickedImageCountsAsAnEdit() {
        val picked = PickedFile(fileName = "a.png", mimeType = "image/png", bytes = byteArrayOf(1))
        assertTrue(opened.copy(imagePicked = picked).isDirtyComparedTo(opened))
    }

    @Test fun categoryEditorFollowsTheSameRules() {
        val cat = CategoryEditor(id = 2, name = "Drinks", sortPosition = "1")
        assertFalse(cat.copy(saving = true, fieldError = "save").isDirtyComparedTo(cat))
        assertFalse(cat.isDirtyComparedTo(null))
        assertTrue(cat.copy(visibility = "POS_ONLY").isDirtyComparedTo(cat))
    }
}
