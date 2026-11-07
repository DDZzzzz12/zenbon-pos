package org.example.project.admin.features.settings.presentation.sections

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Client-side validation of the payment-type dialog, run when Save is pressed (Save is never disabled
 * for an empty form): one message key per invalid field; empty = savable. The translations are optional.
 */
internal fun validatePaymentTypeInput(name: String): Map<PaymentTypeField, StringKey> =
    buildMap {
        if (name.isBlank()) put(PaymentTypeField.NAME, CommonKeys.fieldRequired)
    }
