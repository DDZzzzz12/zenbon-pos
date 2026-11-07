package org.example.project.admin.features.promotion.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.promotion.presentation.PromotionEditor
import org.example.project.admin.features.promotion.presentation.PromotionField
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosFormGrid
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * Create/edit form for a promotion.
 *
 * Discount type, discount value, scope and target product are **create-only**: the server's
 * `UpdatePromotionRequestDto` carries none of them, so on edit they render disabled rather than
 * accepting input the save would silently drop.
 */
@Composable
fun PromotionEditorDialog(
    editor: PromotionEditor,
    products: List<ProductDto.ProductResponseDto>,
    onField: ((PromotionEditor) -> PromotionEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** True when the form holds unsaved edits: closing it then asks first. */
    dirty: Boolean = false,
) {
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var scopeMenuExpanded by remember { mutableStateOf(false) }
    var productMenuExpanded by remember { mutableStateOf(false) }
    val editable = editor.isCreate
    val focus = rememberPosFormFocus<PromotionField>()
    val formErrors = editor.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: PromotionField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(PromotionField.entries, formErrors)
    }

    val close = rememberGuardedDismiss(dirty = dirty, onClose = onCancel)
    PosDialog(
        onDismiss = close,
        title = str(if (editor.isCreate) PromotionKeys.createPromotion else PromotionKeys.editPromotion),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            editor.fieldError?.let { err ->
                Text(
                    text = if (err == "save") str(AdminStringKeys.ProductErrorSaveFailed) else err, // else: server ProblemDetail message
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            PosTextField(
                editor.name,
                { v -> onField { it.copy(name = v) } },
                requiredLabel(str(PromotionKeys.promotionName)),
                modifier = anchor(PromotionField.NAME),
                isError = PromotionField.NAME in formErrors,
                supportingText = formErrors[PromotionField.NAME]?.let { msg -> { Text(msg) } },
            )
            PosTextField(
                editor.description,
                { v -> onField { it.copy(description = v) } },
                str(PromotionKeys.promotionDescription),
                singleLine = false,
                minLines = 2,
            )

            // Type and value are two halves of one setting: side by side once the dialog is wide enough.
            PosFormGrid(minCellWidth = 180.dp, maxColumns = 2) {
                Cell {
                    Box {
                        PosPickerField(
                            value = discountTypeLabel(editor.discountType),
                            onClick = { typeMenuExpanded = true },
                            label = str(PromotionKeys.discountType),
                            readOnly = !editable,
                            supportingText = if (editable) null else str(AdminStringKeys.PromotionLockedReason),
                        )
                        PosDropdown(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                            listOf("FIXED_AMOUNT", "PERCENTAGE", "FREE_ITEM").forEach { type ->
                                PosDropdownItem(
                                    text = discountTypeLabel(type),
                                    selected = type == editor.discountType,
                                    onClick = {
                                        onField { it.copy(discountType = type) }
                                        typeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Cell {
                    PosTextField(
                        value = editor.discountValue,
                        onValueChange = { v -> onField { it.copy(discountValue = v) } },
                        // Required only where it can be typed: on edit the value is locked (create-only).
                        label = str(PromotionKeys.discountValue).let { if (editable) requiredLabel(it) else it },
                        textFieldType = if (editor.discountType == "PERCENTAGE") PosTextFieldType.Percentage else PosTextFieldType.Price,
                        readOnly = !editable,
                        locked = !editable,
                        modifier = anchor(PromotionField.DISCOUNT_VALUE),
                        isError = PromotionField.DISCOUNT_VALUE in formErrors,
                        supportingText = formErrors[PromotionField.DISCOUNT_VALUE]?.let { msg -> { Text(msg) } },
                        suffix =
                            when (editor.discountType) {
                                "PERCENTAGE" -> ({ Text("%") })
                                "FIXED_AMOUNT" -> ({ Text("€") })
                                else -> null
                            },
                    )
                }
            }

            Box {
                PosPickerField(
                    value = scopeLabel(editor.scope),
                    onClick = { scopeMenuExpanded = true },
                    label = str(PromotionKeys.scope),
                    readOnly = !editable,
                )
                PosDropdown(expanded = scopeMenuExpanded, onDismissRequest = { scopeMenuExpanded = false }) {
                    listOf("BILL_WIDE", "ITEM_SPECIFIC").forEach { scope ->
                        PosDropdownItem(
                            text = scopeLabel(scope),
                            selected = scope == editor.scope,
                            onClick = {
                                onField {
                                    if (scope == "BILL_WIDE") {
                                        it.copy(scope = scope, targetProductId = null, targetProductName = "")
                                    } else {
                                        it.copy(scope = scope)
                                    }
                                }
                                scopeMenuExpanded = false
                            },
                        )
                    }
                }
            }

            if (editor.scope == "ITEM_SPECIFIC") {
                Box {
                    PosPickerField(
                        value = editor.targetProductName,
                        onClick = { productMenuExpanded = true },
                        label = str(PromotionKeys.targetProduct).let { if (editable) requiredLabel(it) else it },
                        readOnly = !editable,
                        modifier = anchor(PromotionField.TARGET_PRODUCT),
                        isError = PromotionField.TARGET_PRODUCT in formErrors,
                        supportingText = formErrors[PromotionField.TARGET_PRODUCT],
                    )
                    PosDropdown(expanded = productMenuExpanded, onDismissRequest = { productMenuExpanded = false }) {
                        products.forEach { p ->
                            PosDropdownItem(
                                text = p.name,
                                selected = p.id == editor.targetProductId,
                                onClick = {
                                    onField { it.copy(targetProductId = p.id, targetProductName = p.name) }
                                    productMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // The format, not a sample date: the old "2026-09-01"/"2026-09-30" read as stale defaults.
            PosFormGrid(minCellWidth = 180.dp, maxColumns = 2) {
                Cell {
                    PosDateField(
                        value = editor.validFromDate,
                        onValueChange = { v -> onField { it.copy(validFromDate = v) } },
                        label = requiredLabel(str(PromotionKeys.validFrom)),
                        modifier = anchor(PromotionField.VALID_FROM),
                        isError = PromotionField.VALID_FROM in formErrors,
                        supportingText = formErrors[PromotionField.VALID_FROM],
                        placeholder = str(CommonKeys.dateFormatHint),
                    )
                }
                Cell {
                    PosDateField(
                        value = editor.validUntilDate,
                        onValueChange = { v -> onField { it.copy(validUntilDate = v) } },
                        label = str(PromotionKeys.validUntil),
                        modifier = anchor(PromotionField.VALID_UNTIL),
                        isError = PromotionField.VALID_UNTIL in formErrors,
                        supportingText = formErrors[PromotionField.VALID_UNTIL],
                        placeholder = str(CommonKeys.dateFormatHint),
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(PromotionKeys.multiUse), Modifier.weight(1f))
                Switch(editor.isMultiUse, { v -> onField { it.copy(isMultiUse = v) } })
            }
            // A different axis from multi-use above: this one is about OTHER promotions on the same
            // bill, so it carries helper text spelling the contrast out.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(AdminStringKeys.PromotionCombinable), Modifier.weight(1f))
                Switch(editor.combinable, { v -> onField { it.copy(combinable = v) } })
            }
            Text(
                text = str(AdminStringKeys.PromotionCombinableHelp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!editor.isCreate) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(str(PromotionKeys.active), Modifier.weight(1f))
                    Switch(editor.isActive, { v -> onField { it.copy(isActive = v) } })
                }
            }
        }
    }
}

// Duplicated from the private helper in features/reports/.../PromotionsSection.kt on purpose:
// that one is private and reporting is deliberately out of this port's scope, so six lines beat
// coupling the reports screen to the catalog manager.
@Composable
internal fun discountTypeLabel(discountType: String): String =
    when (discountType) {
        "FIXED_AMOUNT" -> str(PromotionKeys.fixedAmount)
        "PERCENTAGE" -> str(PromotionKeys.percentage)
        "FREE_ITEM" -> str(PromotionKeys.freeItem)
        else -> discountType
    }

@Composable
internal fun scopeLabel(scope: String): String =
    when (scope) {
        "ITEM_SPECIFIC" -> str(PromotionKeys.itemSpecific)
        "BILL_WIDE" -> str(PromotionKeys.billWide)
        else -> scope
    }
