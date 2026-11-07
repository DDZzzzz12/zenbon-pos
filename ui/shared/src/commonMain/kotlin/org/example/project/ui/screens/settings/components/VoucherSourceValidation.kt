package org.example.project.ui.screens.settings.components

import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys

/**
 * Client-side validation of the voucher-source form: one message key per invalid field; empty = savable.
 *
 * A type is the identity of a source, so adding one whose type is already configured would replace
 * that source rather than add a second; in edit mode the type is fixed and never checked.
 */
fun validateVoucherSourceDraft(
    type: VoucherSourceType,
    name: String,
    isEditMode: Boolean,
    existingTypes: List<VoucherSourceType>,
): Map<VoucherSourceField, StringKey> =
    buildMap {
        if (!isEditMode && type in existingTypes) put(VoucherSourceField.TYPE, SettingsKeys.voucherSourceTypeTaken)
        if (name.isBlank()) put(VoucherSourceField.NAME, CommonKeys.fieldRequired)
    }
