package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.CategoryEditor
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.SystemRecords
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosSideSheet
import org.example.project.ui.components.form.PosChangedMarker
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.inventory.CategoryBulkGroupsSection
import org.example.project.ui.components.inventory.CategoryEditForm
import org.example.project.ui.components.inventory.CategoryFormField
import org.example.project.ui.components.inventory.CategoryFormState

@Composable
fun CategoryEditDrawer(
    editor: CategoryEditor,
    dirty: Boolean,
    onField: ((CategoryEditor) -> CategoryEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    groups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
    groupCoverage: Map<Int, Int>,
    totalProductsInCategory: Int,
    onBulkAssignGroup: (Int, Int) -> Unit,
    onBulkRemoveGroup: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberPosFormFocus<CategoryFormField>()
    val formErrors = editor.fieldErrors.mapValues { (_, key) -> str(key) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) {
            focus.revealFirst(CategoryFormField.entries, formErrors)
        }
    }
    PosSideSheet(
        onDismiss = onCancel,
        title = str(if (editor.isCreate) AdminStringKeys.CategoryCreateTitle else AdminStringKeys.CategoryEditTitle),
        modifier = modifier,
        primary = PosDialogAction(str(AdminStringKeys.CommonSave), onSave, isLoading = editor.saving),
        secondary = PosDialogAction(str(AdminStringKeys.CommonCancel), onCancel),
        dismissOnScrimClick = false,
        headerExtras = if (dirty) ({ PosChangedMarker() }) else null,
        // Löschen only for a saved, non-system category and only where the caller allows it; the delete dialog explains a block.
        footerStart =
            if (!editor.isCreate && onDelete != null && SystemRecords.mayEditCategory(editor.name)) {
                { SideSheetDeleteAction(onDelete) }
            } else {
                null
            },
    ) {
        // A save failure is about the whole form; field validation is shown at the field.
        val saveFailure =
            editor.saveError?.displayMessage()
                ?: editor.fieldError?.let { str(AdminStringKeys.ProductErrorSaveFailed) }
        saveFailure?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        CategoryEditForm(
            state =
                CategoryFormState(
                    name = editor.name,
                    enName = editor.en,
                    chName = editor.ch,
                    description = editor.description,
                    sortPosition = editor.sortPosition,
                    isActive = editor.isActive,
                    visibility = editor.visibility,
                ),
            onState = { s ->
                onField {
                    it.copy(
                        name = s.name,
                        en = s.enName,
                        ch = s.chName,
                        description = s.description,
                        sortPosition = s.sortPosition,
                        isActive = s.isActive,
                        visibility = s.visibility,
                    )
                }
            },
            language = AdminLocale.language,
            modifier = Modifier.fillMaxWidth(),
            errors = formErrors,
            focus = focus,
            visibilityAsRadios = true,
        )

        if (editor.id != null) {
            CategoryBulkGroupsSection(
                categoryId = editor.id,
                choiceGroups = groups,
                groupCoverage = groupCoverage,
                totalProductsInCategory = totalProductsInCategory,
                onBulkAssignGroup = onBulkAssignGroup,
                onBulkRemoveGroup = onBulkRemoveGroup,
                rowActions = true,
            )
        }
    }
}
