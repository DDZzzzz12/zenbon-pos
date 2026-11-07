package org.example.project.admin.features.settings.presentation.sections

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.enums.setup.GermanVatRate
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Client-side validation of the VAT-rate dialog, run when Save is pressed (Save is never disabled for
 * an empty or invalid form): one message key per invalid field; empty = savable. A historical rate an
 * install still holds is shown in the picker but is not saveable — the operator must pick a current one.
 */
internal fun validateVatRateInput(
    vatId: String,
    vatClass: String,
    rate: GermanVatRate?,
): Map<VatRateField, StringKey> =
    buildMap {
        if (vatId.isBlank()) put(VatRateField.VAT_ID, CommonKeys.fieldRequired)
        if (vatClass.isBlank()) put(VatRateField.VAT_CLASS, CommonKeys.fieldRequired)
        when {
            rate == null -> put(VatRateField.RATE, CommonKeys.fieldRequired)
            !rate.configurable -> put(VatRateField.RATE, AdminStringKeys.SettingsVatRateNotSelectable)
        }
    }
