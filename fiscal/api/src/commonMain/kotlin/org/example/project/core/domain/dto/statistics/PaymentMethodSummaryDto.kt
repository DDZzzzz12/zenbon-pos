package org.example.project.core.domain.dto.statistics

import kotlinx.serialization.Serializable

/**
 * Payment method summary.
 */
@Serializable
data class PaymentMethodSummaryDto(
    /** Payment method name: the German master name (`payment_type.name`), also the map key */
    val paymentMethod: String,
    /**
     * English name of the payment type (`payment_type.en_name`). Display only, for
     * `getLocalizedName`; null from an older server or when no translation is stored.
     */
    val paymentMethodEN: String? = null,
    /** Chinese name of the payment type (`payment_type.ch_name`), as [paymentMethodEN]. */
    val paymentMethodCH: String? = null,
    /** Total amount processed */
    val totalAmount: String,
    /** Number of transactions */
    val transactionCount: Int,
    /** Percentage of total revenue */
    val revenuePercentage: String,
    /** Average transaction value */
    val averageTransactionValue: String,
)
