package org.example.project.features.statistics.data.repository

import java.math.BigDecimal

/**
 * One tender of the financial overview's payment breakdown, as read from the grouped query:
 * the German master [name] (the breakdown's key) with its two display translations, and the
 * figures for that tender.
 */
internal data class FinancialPaymentRow(
    val name: String,
    val enName: String?,
    val chName: String?,
    val totalAmount: BigDecimal,
    val transactionCount: Int,
)
