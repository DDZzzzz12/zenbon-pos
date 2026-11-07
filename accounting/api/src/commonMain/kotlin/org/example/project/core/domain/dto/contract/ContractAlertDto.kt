package org.example.project.core.domain.dto.contract

import kotlinx.serialization.Serializable

/**
 * Wire contract for what a contract is currently complaining about.
 *
 * Alerts are **derived on read**, never stored, exactly like the instalment status and both
 * residuals — so an alert disappears by itself once the situation is resolved and there is nothing
 * for an operator to tick off.
 *
 * [AlertDto.kind] is a `ContractAlertKind` constant name; the client maps it to localized text.
 * Money crosses as a decimal `String`, written with `toExactDecimalString()` so a `DECIMAL(19,4)`
 * amount keeps its scale, and dates as ISO `yyyy-MM-dd`.
 */
object ContractAlertDto {
    @Serializable
    data class AlertDto(
        val kind: String,
        val contractId: Long,
        val contractName: String,
        val instalmentId: Long,
        val dueDate: String,
        /** Decimal strings; both null unless the kind is `AMOUNT_OFF_BAND`. */
        val expectedAmount: String? = null,
        val actualAmount: String? = null,
        /** The shop's configured currency (ISO 4217) — see `ContractDto.ResponseDto.currency`. */
        val currency: String = "EUR",
    )

    @Serializable
    data class ListResponseDto(
        val items: List<AlertDto>,
        val total: Long,
    )
}
