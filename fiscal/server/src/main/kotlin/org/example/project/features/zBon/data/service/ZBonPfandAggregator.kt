package org.example.project.features.zBon.data.service

import org.example.project.features.order.domain.models.OrderItem
import org.example.project.utils.formatter.toPercentage
import java.math.BigDecimal

/**
 * Buckets Pfand components out of a pre-fetched list of billed order items, keyed by VAT rate
 * (spec 2026-07-09 §2.1). NOT pure DB-wise: reads lazy DAO fields (product flags, vat), so the
 * caller must invoke it inside the Z-Bon transaction and pass items of the report's activeBills
 * only — storno consistency then follows by construction (activeBills excludes storno pairs).
 */
object ZBonPfandAggregator {
    /** Deposit (isDeposit, ≥ 0) and return (isDepositReturn, ≤ 0) totals for one VAT rate. */
    data class PfandTotals(
        val deposit: BigDecimal,
        val returned: BigDecimal,
    )

    /** @return map keyed by `vat.vatRate.toPercentage()` ("19"/"7"/…); rates with no Pfand are absent. */
    fun aggregate(items: List<OrderItem>): Map<String, PfandTotals> {
        val deposit = mutableMapOf<String, BigDecimal>()
        val returned = mutableMapOf<String, BigDecimal>()
        items.forEach { item ->
            val product = item.product
            val rate = item.vat.vatRate.toPercentage()
            when {
                product.isDeposit -> deposit[rate] = (deposit[rate] ?: BigDecimal.ZERO) + item.finalPrice
                product.isDepositReturn -> returned[rate] = (returned[rate] ?: BigDecimal.ZERO) + item.finalPrice
            }
        }
        return (deposit.keys + returned.keys).associateWith { rate ->
            PfandTotals(deposit[rate] ?: BigDecimal.ZERO, returned[rate] ?: BigDecimal.ZERO)
        }
    }
}
