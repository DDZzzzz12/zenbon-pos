package org.example.project.admin.features.choicegroup.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupField
import org.example.project.admin.features.choicegroup.presentation.GroupEditor
import org.example.project.admin.features.choicegroup.presentation.hasUnreadablePriceDelta
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ProductsTabKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceGroupEditorDialog(
    editor: GroupEditor,
    availableExtras: List<ProductExtrasDto.ProductExtrasResponseDto>,
    onField: ((GroupEditor) -> GroupEditor) -> Unit,
    onSetPrice: (Int, String) -> Unit,
    onSetDefault: (Int, Boolean) -> Unit,
    onMove: (Int, Boolean) -> Unit,
    onRemove: (Int) -> Unit,
    onAddOption: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** True when the form holds unsaved edits: closing it then asks first. */
    dirty: Boolean = false,
) {
    var addMenuExpanded by remember { mutableStateOf(false) }
    val focus = rememberPosFormFocus<ChoiceGroupField>()
    val formErrors = editor.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: ChoiceGroupField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(ChoiceGroupField.entries, formErrors)
    }
    val rangeInvalid = ChoiceGroupField.SELECTION_RANGE in formErrors
    val pricesInvalid = ChoiceGroupField.OPTION_PRICES in formErrors
    // The first unreadable delta is the scroll/focus target; every unreadable one is marked.
    val firstBadPriceRow = if (pricesInvalid) editor.options.firstOrNull { it.hasUnreadablePriceDelta() }?.extraId else null

    val close = rememberGuardedDismiss(dirty = dirty, onClose = onCancel)
    PosDialog(
        onDismiss = close,
        title = str(if (editor.isCreate) ChoiceGroupKeys.createGroupTitle else ChoiceGroupKeys.editGroupTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A save failure is about the whole form; field validation is shown at the field.
            val saveFailure =
                editor.saveError?.displayMessage()
                    ?: editor.fieldError?.let { str(AdminStringKeys.ProductErrorSaveFailed) }
            saveFailure?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            PosTextField(
                editor.name,
                { v -> onField { it.copy(name = v) } },
                requiredLabel(str(ChoiceGroupKeys.groupName)),
                modifier = anchor(ChoiceGroupField.NAME),
                isError = ChoiceGroupField.NAME in formErrors,
                supportingText = formErrors[ChoiceGroupField.NAME]?.let { msg -> { Text(msg) } },
            )
            PosTextField(editor.en, { v -> onField { it.copy(en = v) } }, str(ProductsTabKeys.englishName))
            PosTextField(editor.ch, { v -> onField { it.copy(ch = v) } }, str(ProductsTabKeys.chineseName))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosTextField(
                    value = editor.minSelections,
                    onValueChange = { v -> onField { it.copy(minSelections = v) } },
                    label = str(ChoiceGroupKeys.minSelections),
                    textFieldType = PosTextFieldType.Integer,
                    modifier = Modifier.weight(1f).then(anchor(ChoiceGroupField.SELECTION_RANGE)),
                    isError = rangeInvalid,
                )
                PosTextField(
                    value = editor.maxSelections,
                    onValueChange = { v -> onField { it.copy(maxSelections = v) } },
                    label = str(ChoiceGroupKeys.maxSelections),
                    textFieldType = PosTextFieldType.Integer,
                    modifier = Modifier.weight(1f),
                    isError = rangeInvalid,
                )
            }
            // The range rule relates both fields, so its message sits under the pair, not under one half.
            formErrors[ChoiceGroupField.SELECTION_RANGE]?.let { msg ->
                Text(text = msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(ChoiceGroupKeys.active), Modifier.weight(1f))
                Switch(editor.isActive, { v -> onField { it.copy(isActive = v) } })
            }

            HorizontalDivider()

            Text(str(ChoiceGroupKeys.options), style = MaterialTheme.typography.titleSmall)

            if (editor.options.isEmpty()) {
                Text(
                    text = str(ChoiceGroupKeys.noOptionsSelected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                editor.options.forEachIndexed { index, row ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}. ${row.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            PosIconButton(
                                icon = Icons.Default.ArrowUpward,
                                contentDescription = str(ChoiceGroupKeys.moveUp),
                                onClick = { onMove(row.extraId, true) },
                                enabled = index > 0,
                                size = adminTouchSize(40.dp),
                                iconSize = 20.dp,
                            )
                            PosIconButton(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = str(ChoiceGroupKeys.moveDown),
                                onClick = { onMove(row.extraId, false) },
                                enabled = index < editor.options.lastIndex,
                                size = adminTouchSize(40.dp),
                                iconSize = 20.dp,
                            )
                            PosDangerIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = str(CommonKeys.delete),
                                onClick = { onRemove(row.extraId) },
                                size = adminTouchSize(40.dp),
                                iconSize = 20.dp,
                            )
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            val rowPriceInvalid = pricesInvalid && row.hasUnreadablePriceDelta()
                            val priceModifier = Modifier.weight(1f).widthIn(min = 140.dp)
                            PosTextField(
                                value = row.priceDelta,
                                onValueChange = { v -> onSetPrice(row.extraId, v) },
                                label = str(ChoiceGroupKeys.priceDelta),
                                textFieldType = PosTextFieldType.Price,
                                modifier =
                                    if (row.extraId == firstBadPriceRow) {
                                        priceModifier.then(anchor(ChoiceGroupField.OPTION_PRICES))
                                    } else {
                                        priceModifier
                                    },
                                isError = rowPriceInvalid,
                                supportingText = formErrors[ChoiceGroupField.OPTION_PRICES]?.takeIf { rowPriceInvalid }?.let { msg -> { Text(msg) } },
                            )
                            Text(str(ChoiceGroupKeys.isDefault), style = MaterialTheme.typography.bodySmall)
                            Switch(checked = row.isDefault, onCheckedChange = { v -> onSetDefault(row.extraId, v) })
                        }
                    }
                }
            }

            if (availableExtras.isEmpty()) {
                Text(
                    text = str(ChoiceGroupKeys.noExtrasAvailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box {
                    PosSecondaryButton(text = str(ChoiceGroupKeys.addOption), onClick = { addMenuExpanded = true })
                    PosDropdown(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false },
                    ) {
                        availableExtras.forEach { extra ->
                            PosDropdownItem(
                                text = extra.name,
                                onClick = {
                                    onAddOption(extra.id)
                                    addMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
