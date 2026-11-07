package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey

/** The steps of an Ausgangsrechnung's Verlauf, in the order the panel lists them. */
enum class SalesHistoryStep(
    val labelKey: StringKey,
) {
    DRAFTED(AdminStringKeys.BelegflowSalesDraft),
    ISSUED(AdminStringKeys.SalesInvoicesStatusIssued),
    ARCHIVED(AdminStringKeys.BelegflowSalesStepArchived),
    PAID(AdminStringKeys.BelegflowSalesStepPaid),
}
