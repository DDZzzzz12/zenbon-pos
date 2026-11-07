package org.example.project.core.domain.enums.voucher

import kotlinx.serialization.Serializable

@Serializable
enum class VoucherStatus {
    ACTIVE,
    INVALIDATED,
}
