package org.example.project.ui.components.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.ProductsTabKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.PosFormFocus
import org.example.project.ui.components.form.PosFormSectionLabel
import org.example.project.ui.components.form.requiredLabel

/**
 * Reusable product create/edit form fields, shared between composeApp and the wasm adminApp.
 * The host owns [state] and image selection (picker + resolved preview model); this composable
 * only renders fields and reports changes via [onState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditForm(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    categories: List<CategoryOption>,
    imageModel: Any?,
    pickedFileName: String?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
    /** Validation messages shown at the matching field (already localized). */
    errors: Map<ProductFormField, String> = emptyMap(),
    /** Registers each validated field as a scroll-to-error target; null = no scrolling. */
    focus: PosFormFocus<ProductFormField>? = null,
    /** Non-null = the sectioned layout (web admin); null = the flat layout (composeApp). */
    sectionCopy: ProductFormSectionCopy? = null,
) {
    fun anchor(field: ProductFormField): Modifier = if (focus == null) Modifier else with(focus) { Modifier.formAnchor(field) }

    val hasImage = imageModel != null || pickedFileName != null

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Image section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                PosSecondaryButton(
                    text = if (hasImage) str(ProductsTabKeys.changeImage) else str(ProductsTabKeys.selectImage),
                    icon = if (hasImage) Icons.Default.Edit else Icons.Default.AddPhotoAlternate,
                    onClick = onPickImage,
                )
                if (pickedFileName != null) {
                    Text(
                        text = pickedFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasImage) {
                PosDangerIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = null,
                    onClick = onClearImage,
                )
            }
        }

        if (sectionCopy != null) PosFormSectionLabel(sectionCopy.basicsTitle, divider = false)
        NumberAndNameFields(state, onState, errors, ::anchor)
        if (sectionCopy == null) {
            TranslationFields(state, onState)
            PriceField(state, onState, errors, ::anchor)
            CategoryAndTypePickers(state, onState, categories, language, errors, ::anchor, sideBySide = false)
            ActiveSwitch(state, onState)
            AllowCustomPriceSwitch(state, onState, hint = null)
        } else {
            CategoryAndTypePickers(state, onState, categories, language, errors, ::anchor, sideBySide = true)
            PriceField(state, onState, errors, ::anchor)
            AllowCustomPriceSwitch(state, onState, hint = sectionCopy.allowCustomPriceHint)
            ActiveSwitch(state, onState)
            PosFormSectionLabel(sectionCopy.translationsTitle)
            TranslationFields(state, onState)
        }
    }
}

@Composable
private fun NumberAndNameFields(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    errors: Map<ProductFormField, String>,
    anchor: (ProductFormField) -> Modifier,
) {
    PosTextField(
        value = state.productNumber,
        onValueChange = { onState(state.copy(productNumber = it)) },
        label = requiredLabel(str(ProductsTabKeys.productNumber)),
        modifier = anchor(ProductFormField.NUMBER),
        isError = ProductFormField.NUMBER in errors,
        supportingText = errors[ProductFormField.NUMBER]?.let { msg -> { Text(msg) } },
    )
    PosTextField(
        value = state.name,
        onValueChange = { onState(state.copy(name = it)) },
        label = requiredLabel(str(ProductsTabKeys.name)),
        modifier = anchor(ProductFormField.NAME),
        isError = ProductFormField.NAME in errors,
        supportingText = errors[ProductFormField.NAME]?.let { msg -> { Text(msg) } },
    )
}

@Composable
private fun TranslationFields(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
) {
    PosTextField(
        value = state.enName,
        onValueChange = { onState(state.copy(enName = it)) },
        label = str(ProductsTabKeys.englishName),
    )
    PosTextField(
        value = state.chName,
        onValueChange = { onState(state.copy(chName = it)) },
        label = str(ProductsTabKeys.chineseName),
    )
    PosTextField(
        value = state.description,
        onValueChange = { onState(state.copy(description = it)) },
        label = str(ProductsTabKeys.description),
        singleLine = false,
        maxLines = 3,
    )
}

@Composable
private fun PriceField(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    errors: Map<ProductFormField, String>,
    anchor: (ProductFormField) -> Modifier,
) {
    PosTextField(
        value = state.price,
        onValueChange = { onState(state.copy(price = it)) },
        label = str(ProductsTabKeys.price),
        imeAction = ImeAction.Done,
        textFieldType = PosTextFieldType.Price,
        modifier = anchor(ProductFormField.PRICE),
        isError = ProductFormField.PRICE in errors,
        supportingText = errors[ProductFormField.PRICE]?.let { msg -> { Text(msg) } },
    )
}

/**
 * Category and type: PosPickerField + PosDropdown, not ExposedDropdownMenuBox. That box
 * hardcodes its own OutlinedTextField, so these two stayed Material-outlined while every
 * other field on the form followed LocalPosTextFieldAppearance — visibly two design
 * languages in one panel. Going through PosPickerField makes them follow the app default
 * like the rest, outlined in the till and flat in the console.
 * [sideBySide] puts them in one row when the form is wide enough (the sectioned layout).
 */
@Composable
private fun CategoryAndTypePickers(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    categories: List<CategoryOption>,
    language: Language,
    errors: Map<ProductFormField, String>,
    anchor: (ProductFormField) -> Modifier,
    sideBySide: Boolean,
) {
    if (sideBySide) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= SIDE_BY_SIDE_MIN_WIDTH) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    CategoryPicker(state, onState, categories, errors, anchor, Modifier.weight(1f))
                    TypePicker(state, onState, language, errors, anchor, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryPicker(state, onState, categories, errors, anchor, Modifier.fillMaxWidth())
                    TypePicker(state, onState, language, errors, anchor, Modifier.fillMaxWidth())
                }
            }
        }
    } else {
        CategoryPicker(state, onState, categories, errors, anchor, Modifier.fillMaxWidth())
        TypePicker(state, onState, language, errors, anchor, Modifier.fillMaxWidth())
    }
}

/** Content width from which category and type sit side by side in the sectioned layout. */
private val SIDE_BY_SIDE_MIN_WIDTH = 360.dp

@Composable
private fun CategoryPicker(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    categories: List<CategoryOption>,
    errors: Map<ProductFormField, String>,
    anchor: (ProductFormField) -> Modifier,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        PosPickerField(
            value =
                categories.find { it.id == state.categoryId }?.name
                    ?: str(ProductsTabKeys.selectCategory),
            onClick = { expanded = true },
            label = requiredLabel(str(ProductsTabKeys.category)),
            modifier = Modifier.fillMaxWidth().then(anchor(ProductFormField.CATEGORY)),
            isError = ProductFormField.CATEGORY in errors,
            supportingText = errors[ProductFormField.CATEGORY],
        )
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            categories.forEach { option ->
                PosDropdownItem(
                    text = option.name,
                    selected = option.id == state.categoryId,
                    onClick = {
                        onState(state.copy(categoryId = option.id))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TypePicker(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    language: Language,
    errors: Map<ProductFormField, String>,
    anchor: (ProductFormField) -> Modifier,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        PosPickerField(
            value = state.productType?.getName(language) ?: str(ProductsTabKeys.selectType),
            onClick = { expanded = true },
            label = requiredLabel(str(ProductsTabKeys.type)),
            modifier = Modifier.fillMaxWidth().then(anchor(ProductFormField.TYPE)),
            isError = ProductFormField.TYPE in errors,
            supportingText = errors[ProductFormField.TYPE],
        )
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ProductType.entries.forEach { type ->
                if (type == ProductType.VOUCHER) return@forEach
                PosDropdownItem(
                    text = type.getName(language),
                    selected = type == state.productType,
                    onClick = {
                        onState(state.copy(productType = type))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveSwitch(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(str(ProductsTabKeys.active))
        Switch(checked = state.isActive, onCheckedChange = { onState(state.copy(isActive = it)) })
    }
}

/** "Allow custom price" switch; the sectioned layout adds a helper line under its label. */
@Composable
private fun AllowCustomPriceSwitch(
    state: ProductFormState,
    onState: (ProductFormState) -> Unit,
    hint: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(str(ProductsTabKeys.allowCustomPrice))
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = state.allowCustomPrice,
            onCheckedChange = { onState(state.copy(allowCustomPrice = it)) },
        )
    }
}
