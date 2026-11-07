package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.ui.components.filter.pluralResultKey

/**
 * The result line's count: the server's exact total for the applied bucket, not the rows on the one
 * page the screen loads. An exact figure exists here, so it is never weakened to an "N+" lower bound.
 */
internal fun salesInvoicesResultCount(state: SalesInvoicesState): String = state.total.toString()

/** "Offen: 1 Rechnung" vs "Offen: 2 Rechnungen": the line's key follows the same exact total. */
internal fun salesInvoicesResultLineKey(state: SalesInvoicesState): StringKey =
    pluralResultKey(state.total, AdminStringKeys.InvoiceBucketResultLineOne, AdminStringKeys.InvoiceBucketResultLine)
