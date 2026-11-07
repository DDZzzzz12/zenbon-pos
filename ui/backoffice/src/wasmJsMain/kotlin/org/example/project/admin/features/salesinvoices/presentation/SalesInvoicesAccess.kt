package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the signed-in principal may create, edit, issue or cancel an Ausgangsrechnung.
 *
 * Every write under `/api/v1/sales-invoices` (`standalone`, `from-bill`, `PUT`/`DELETE {id}`,
 * `issue`, `cancel`) is `INVOICE_WRITE` in `InvoiceRules`; reading and the PDF are `INVOICE_READ`
 * (FO-03).
 */
data class SalesInvoicesAccess(
    val mayWrite: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): SalesInvoicesAccess = SalesInvoicesAccess(mayWrite = policy.allows(Capability.INVOICE_WRITE))
    }
}
