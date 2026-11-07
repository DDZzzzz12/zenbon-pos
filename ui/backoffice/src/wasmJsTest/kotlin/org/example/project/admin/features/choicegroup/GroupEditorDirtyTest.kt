package org.example.project.admin.features.choicegroup

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupField
import org.example.project.admin.features.choicegroup.presentation.GroupEditor
import org.example.project.admin.features.choicegroup.presentation.OptionRow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupEditorDirtyTest {
    private val opened = GroupEditor(id = 1, name = "Size", options = listOf(OptionRow(extraId = 7, name = "Large", priceDelta = "1.00")))

    @Test fun untouchedEditorIsClean() = assertFalse(opened.isDirtyComparedTo(opened.copy()))

    @Test fun noOriginalIsClean() = assertFalse(opened.isDirtyComparedTo(null))

    @Test fun editedFieldIsDirty() = assertTrue(opened.copy(maxSelections = "2").isDirtyComparedTo(opened))

    @Test fun optionRowChangeIsDirty() {
        assertTrue(opened.copy(options = opened.options + OptionRow(extraId = 8, name = "XL")).isDirtyComparedTo(opened))
        assertTrue(opened.copy(options = listOf(opened.options.first().copy(priceDelta = "2.00"))).isDirtyComparedTo(opened))
    }

    @Test fun editThenUndoIsClean() = assertFalse(opened.copy(name = "Sizes").copy(name = "Size").isDirtyComparedTo(opened))

    @Test fun transientFlagsAreIgnored() = assertFalse(opened.copy(saving = true, fieldError = "save").isDirtyComparedTo(opened))

    @Test fun validationStateIsIgnored() =
        assertFalse(
            opened
                .copy(
                    fieldErrors = mapOf(ChoiceGroupField.NAME to AdminStringKeys.ProductFieldRequired),
                    validationAttempt = 2,
                ).isDirtyComparedTo(opened),
        )
}
