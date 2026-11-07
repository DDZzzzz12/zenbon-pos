package org.example.project.admin.features.debtors.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.EmailFormat

/**
 * Client-side validation of a debtor draft: one message per invalid field, empty = savable.
 *
 * The name is the one required field (the server refuses a blank one too). Payment terms are checked
 * because the form converts them with `toIntOrNull()`: "14 Tage" used to be dropped to "no terms"
 * without a word, which is data loss rather than a validation the server could ever see. The e-mail is
 * checked with the shared [EmailFormat], the same function the server's `DebtorDto.validate()` uses, so
 * the form and the 400 cannot disagree (NEW-20, rule 8).
 */
internal fun validateDebtorForm(form: DebtorForm): Map<DebtorFormField, StringKey> =
    buildMap {
        if (form.name.isBlank()) put(DebtorFormField.NAME, CommonKeys.fieldRequired)
        if (!EmailFormat.isAcceptable(form.email)) put(DebtorFormField.EMAIL, AdminStringKeys.PartnerEmailInvalid)
        val terms = form.paymentTermsDays.trim()
        if (terms.isNotEmpty() && (terms.toIntOrNull()?.let { it >= 0 } != true)) {
            put(DebtorFormField.PAYMENT_TERMS, AdminStringKeys.DebtorPaymentTermsInvalid)
        }
    }

/** Drops the error of every field whose value differs between [previous] and [this]. */
internal fun Map<DebtorFormField, StringKey>.clearedFor(
    previous: DebtorForm,
    current: DebtorForm,
): Map<DebtorFormField, StringKey> {
    if (isEmpty()) return this
    val changed =
        buildSet {
            if (previous.name != current.name) add(DebtorFormField.NAME)
            if (previous.email != current.email) add(DebtorFormField.EMAIL)
            if (previous.paymentTermsDays != current.paymentTermsDays) add(DebtorFormField.PAYMENT_TERMS)
        }
    return filterKeys { it !in changed }
}
