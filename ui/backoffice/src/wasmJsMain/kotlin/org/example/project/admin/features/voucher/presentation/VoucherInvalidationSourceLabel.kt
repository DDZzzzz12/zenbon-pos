package org.example.project.admin.features.voucher.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.enums.voucher.VoucherInvalidationSource
import org.example.project.core.presentation.strings.StringKey

/**
 * The localized label key for a voucher's `invalidationSource` (the enum name the server sends), or
 * null for a value this client does not know, so the caller can fall back to the raw text instead
 * of hiding it.
 */
internal fun voucherInvalidationSourceKey(source: String): StringKey? =
    when (VoucherInvalidationSource.entries.firstOrNull { it.name == source }) {
        VoucherInvalidationSource.BILL_STORNO -> AdminStringKeys.VouchersInvalidationSourceBillStorno
        VoucherInvalidationSource.ORDER_CANCELLED -> AdminStringKeys.VouchersInvalidationSourceOrderCancelled
        null -> null
    }
