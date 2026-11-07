package org.example.project.core.domain.enums.voucher

import kotlinx.serialization.Serializable

@Serializable
enum class VoucherInvalidationSource(
    val german: String,
) {
    BILL_STORNO("Rechnungsstorno"),
    ORDER_CANCELLED("Bestellung storniert"),
}
