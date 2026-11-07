package org.example.project.admin.features.bank.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Which of the Bankkonten screen's write actions the signed-in principal may be offered.
 *
 * Each flag names the capability the server's rule requires for the call behind the control, so a
 * read-only group (Prüfer, Export-Leser: `CREDITOR_READ` only) sees the accounts without buttons the
 * server would refuse with a 403 (T9-21):
 * - [mayManageAccounts] — `POST /api/bank-accounts`, `POST /api/bank-accounts/resolve` and
 *   `PUT /api/bank-statements/{id}/account` are `CREDITOR_WRITE` in `CreditorRules`.
 * - [mayImportStatements] — the import goes through `POST /api/documents/upload`, which is
 *   `DOCUMENT_WRITE` in `DocumentRules`.
 */
data class BankAccountsAccess(
    val mayManageAccounts: Boolean,
    val mayImportStatements: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): BankAccountsAccess =
            BankAccountsAccess(
                mayManageAccounts = policy.allows(Capability.CREDITOR_WRITE),
                mayImportStatements = policy.allows(Capability.DOCUMENT_WRITE),
            )
    }
}
