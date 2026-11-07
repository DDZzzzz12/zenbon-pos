package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.file.pickImage
import org.example.project.admin.core.util.resolveImageUrl
import org.example.project.admin.features.product.presentation.ProductEditor
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosSideSheet
import org.example.project.ui.components.form.PosChangedMarker
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.inventory.CategoryOption
import org.example.project.ui.components.inventory.ProductEditForm
import org.example.project.ui.components.inventory.ProductFormField
import org.example.project.ui.components.inventory.ProductFormSectionCopy
import org.example.project.ui.components.inventory.ProductFormState

@Composable
fun ProductEditDrawer(
    editor: ProductEditor,
    dirty: Boolean,
    categories: List<ProductCategoryDto.ProductCategoryResponseDto>,
    groups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
    pfandTypes: List<PfandTypeDto.PfandTypeResponseDto>,
    onField: ((ProductEditor) -> ProductEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onAttachGroup: (Int) -> Unit,
    onDetachGroup: (Int) -> Unit,
    onMoveGroup: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focus = rememberPosFormFocus<ProductFormField>()
    val formErrors = editor.fieldErrors.mapValues { (_, key) -> str(key) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) {
            focus.revealFirst(ProductFormField.entries, formErrors)
        }
    }
    PosSideSheet(
        onDismiss = onCancel,
        title = str(if (editor.isCreate) AdminStringKeys.ProductCreateTitle else AdminStringKeys.ProductEditTitle),
        modifier = modifier,
        primary =
            PosDialogAction(
                str(AdminStringKeys.CommonSave),
                onSave,
                isLoading = editor.saving,
            ),
        secondary = PosDialogAction(str(AdminStringKeys.CommonCancel), onCancel),
        dismissOnScrimClick = false,
        headerExtras = if (dirty) ({ PosChangedMarker() }) else null,
        // Löschen only for a saved product, and only where the caller allows it (canManageProducts).
        footerStart = if (!editor.isCreate && onDelete != null) ({ SideSheetDeleteAction(onDelete) }) else null,
    ) {
        // A save failure is about the whole form. PosSideSheet has no slot between the body and its
        // footer, so it heads the self-scrolling body (as in CategoryEditDrawer).
        val saveFailure =
            editor.saveError?.displayMessage()
                ?: editor.fieldError?.let { str(AdminStringKeys.ProductErrorSaveFailed) }
        saveFailure?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        ProductEditForm(
            state =
                ProductFormState(
                    productNumber = editor.productNumber,
                    name = editor.name,
                    enName = editor.en,
                    chName = editor.ch,
                    description = editor.description,
                    price = editor.price,
                    productType = editor.productType?.let { runCatching { ProductType.valueOf(it) }.getOrNull() },
                    categoryId = editor.categoryId?.toLong() ?: 0L,
                    isActive = editor.isActive,
                    allowCustomPrice = editor.allowCustomPrice,
                ),
            onState = { s ->
                onField {
                    it.copy(
                        productNumber = s.productNumber,
                        name = s.name,
                        en = s.enName,
                        ch = s.chName,
                        description = s.description,
                        price = s.price,
                        productType = s.productType?.name,
                        categoryId = if (s.categoryId == 0L) null else s.categoryId.toInt(),
                        isActive = s.isActive,
                        allowCustomPrice = s.allowCustomPrice,
                    )
                }
            },
            categories = categories.map { CategoryOption(it.id.toLong(), it.name) },
            imageModel = if (editor.imagePicked != null) null else resolveImageUrl(editor.imageUrl),
            pickedFileName = editor.imagePicked?.fileName,
            onPickImage = { scope.launch { pickImage()?.let { picked -> onField { it.copy(imagePicked = picked) } } } },
            onClearImage = { onField { it.copy(imageUrl = null, imagePicked = null) } },
            language = AdminLocale.language,
            modifier = Modifier.fillMaxWidth(),
            errors = formErrors,
            focus = focus,
            sectionCopy =
                ProductFormSectionCopy(
                    basicsTitle = str(AdminStringKeys.ProductSectionBasics),
                    translationsTitle = str(AdminStringKeys.ProductSectionTranslations),
                    allowCustomPriceHint = str(AdminStringKeys.ProductAllowCustomPriceHint),
                ),
        )

        ProductPfandTypeSection(
            editor = editor,
            pfandTypes = pfandTypes,
            onSelect = { id, name -> onField { it.copy(pfandTypeId = id, pfandTypeName = name) } },
            modifier = Modifier.fillMaxWidth(),
        )

        ProductChoiceGroupSection(
            editor = editor,
            groups = groups,
            onAttach = onAttachGroup,
            onDetach = onDetachGroup,
            onMove = onMoveGroup,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
