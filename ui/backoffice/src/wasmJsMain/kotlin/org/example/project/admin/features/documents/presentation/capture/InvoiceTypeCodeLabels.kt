package org.example.project.admin.features.documents.presentation.capture

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey

/** The label key for each offered invoice type. Kept pure so a missing mapping is a test failure. */
internal fun invoiceTypeCodeLabelKey(type: InvoiceTypeCode): StringKey =
    when (type) {
        InvoiceTypeCode.RECHNUNG -> AdminStringKeys.InvoiceTypeCode380
        InvoiceTypeCode.GUTSCHRIFT -> AdminStringKeys.InvoiceTypeCode381
        InvoiceTypeCode.KORREKTUR -> AdminStringKeys.InvoiceTypeCode384
    }
