/**
 * Voucher Source Dialog component.
 *
 * Dialog for adding or editing voucher source configurations. Provides form fields
 * for voucher source type, name, description, and enabled status.
 *
 * The dialog handles both add and edit modes:
 * - Add mode: User selects source type, enters name and description
 * - Edit mode: Source type is read-only, user can modify name, description, and status
 *
 * Features:
 * - Source type dropdown with all available types
 * - Name and description text fields
 * - Enabled/disabled toggle switch
 * - Form validation before submission
 * - Localized labels and hints
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.settings.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.defaultSourceDescription
import org.example.project.core.utils.enumStrings.defaultSourceName
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.screens.settings.components.AddItemDialog

/**
 * Dialog for adding or editing a voucher source.
 *
 * Displays a form dialog allowing users to configure voucher sources including
 * source type, name, description, and enabled status. In edit mode, the source
 * type field is disabled to prevent changing the source type of an existing source.
 *
 * @param editingSource Existing voucher source to edit, or null for add mode
 * @param existingTypes List of source types that are already configured (to prevent duplicates in add mode)
 * @param language Current language for localization
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when form is confirmed with type, name, enabled status, and description
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherSourceDialog(
    editingSource: VoucherSource?,
    existingTypes: List<VoucherSourceType>,
    language: Language,
    onDismiss: () -> Unit,
    onConfirm: (type: VoucherSourceType, name: String, enabled: Boolean, description: String?) -> Unit,
) {
    val isEditMode = editingSource != null

    // Form state
    var selectedType by remember { mutableStateOf(editingSource?.type ?: VoucherSourceType.INTERNAL) }
    var name by remember { mutableStateOf(editingSource?.name ?: "") }
    var description by remember { mutableStateOf(editingSource?.description ?: "") }
    var enabled by remember { mutableStateOf(editingSource?.enabled ?: true) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    // Get available types for selection (exclude already configured types in add mode)
    val availableTypes =
        if (isEditMode) {
            VoucherSourceType.entries
        } else {
            VoucherSourceType.entries.filterNot { it in existingTypes }
        }

    // Validation runs on Save, never by disabling it: the errors show at the field, and editing a
    // field drops its own error.
    var fieldErrors by remember { mutableStateOf(emptyMap<VoucherSourceField, StringKey>()) }
    var validationAttempt by remember { mutableIntStateOf(0) }
    val focus = rememberPosFormFocus<VoucherSourceField>()
    val formErrors = fieldErrors.mapValues { (_, key) -> str(key) }

    fun anchor(field: VoucherSourceField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(VoucherSourceField.entries, formErrors)
    }

    AddItemDialog(
        title =
            if (isEditMode) {
                str(SettingsKeys.editVoucherSource)
            } else {
                str(SettingsKeys.addVoucherSource)
            },
        onDismiss = onDismiss,
        onConfirm = {
            val errors = validateVoucherSourceDraft(selectedType, name, isEditMode, existingTypes)
            if (errors.isNotEmpty()) {
                fieldErrors = errors
                validationAttempt++
            } else {
                onConfirm(
                    selectedType,
                    name.trim(),
                    enabled,
                    description.trim().takeIf { it.isNotBlank() },
                )
            }
        },
        confirmText = getString(CommonKeys.save, language),
        dismissText = getString(CommonKeys.cancel, language),
        dirty =
            selectedType != (editingSource?.type ?: VoucherSourceType.INTERNAL) ||
                name != (editingSource?.name ?: "") ||
                description != (editingSource?.description ?: "") ||
                enabled != (editingSource?.enabled ?: true),
    ) {
        // Source Type Dropdown
        Box {
            PosPickerField(
                value = selectedType.getName(language),
                onClick = { if (!isEditMode) typeDropdownExpanded = true },
                label = requiredLabel(str(SettingsKeys.voucherSourceType)),
                modifier = Modifier.fillMaxWidth().then(anchor(VoucherSourceField.TYPE)),
                enabled = !isEditMode, // Disable in edit mode
                isError = VoucherSourceField.TYPE in formErrors,
                supportingText = formErrors[VoucherSourceField.TYPE],
            )

            PosDropdown(
                expanded = typeDropdownExpanded,
                onDismissRequest = { typeDropdownExpanded = false },
            ) {
                availableTypes.forEach { type ->
                    PosDropdownItem(
                        text = type.getName(language),
                        selected = type == selectedType,
                        onClick = {
                            if (type != selectedType) fieldErrors = fieldErrors - VoucherSourceField.TYPE
                            selectedType = type
                            typeDropdownExpanded = false

                            // Pre-fill an empty name/description with the type's default in the UI
                            // language. It is stored as shown (the till prints it on its redeem chips),
                            // so the operator sees exactly what will be saved and can change it.
                            if (name.isBlank()) {
                                fieldErrors = fieldErrors - VoucherSourceField.NAME
                                name = type.defaultSourceName(language)
                            }
                            if (description.isBlank()) {
                                description = type.defaultSourceDescription(language)
                            }
                        },
                    )
                }
            }
        }

        // Name Field
        PosTextField(
            value = name,
            onValueChange = {
                if (it != name) fieldErrors = fieldErrors - VoucherSourceField.NAME
                name = it
            },
            label = requiredLabel(str(SettingsKeys.voucherSourceName)),
            placeholder = str(SettingsKeys.voucherSourceNameHint),
            modifier = Modifier.fillMaxWidth().then(anchor(VoucherSourceField.NAME)),
            isError = VoucherSourceField.NAME in formErrors,
            supportingText = formErrors[VoucherSourceField.NAME]?.let { msg -> { Text(msg) } },
        )

        // Description Field
        PosTextField(
            value = description,
            onValueChange = { description = it },
            label = str(SettingsKeys.voucherSourceDescription),
            placeholder = str(SettingsKeys.voucherSourceDescriptionHint),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            maxLines = 3,
            imeAction = ImeAction.Done,
        )

        // Enabled Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str(SettingsKeys.active))
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }
    }
}
