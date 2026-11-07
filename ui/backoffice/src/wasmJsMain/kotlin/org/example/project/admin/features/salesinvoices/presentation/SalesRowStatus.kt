package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.ui.components.beleg.BelegTone

/**
 * The status word of an Ausgangsrechnung row: Entwurf grey, Ausgestellt blue, Offen amber,
 * Überfällig red, Erledigt green, Storniert and Gutschrift grey.
 */
enum class SalesRowStatus(
    val labelKey: StringKey,
    val tone: BelegTone,
) {
    DRAFT(AdminStringKeys.SalesInvoicesStatusDraft, BelegTone.Neutral),
    ISSUED(AdminStringKeys.SalesInvoicesStatusIssued, BelegTone.Info),
    OPEN(AdminStringKeys.SalesInvoicesBucketOpen, BelegTone.Warning),
    OVERDUE(AdminStringKeys.SalesInvoicesBucketOverdue, BelegTone.Danger),
    PAID(AdminStringKeys.BelegflowStageDone, BelegTone.Success),
    CREDIT_NOTE(AdminStringKeys.BelegflowStatusCreditNote, BelegTone.Neutral),
    CANCELLED(AdminStringKeys.SalesInvoicesStatusCancelled, BelegTone.Neutral),
}
