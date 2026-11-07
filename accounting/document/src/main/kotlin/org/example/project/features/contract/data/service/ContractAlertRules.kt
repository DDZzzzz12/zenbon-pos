package org.example.project.features.contract.data.service

import java.math.BigDecimal

/**
 * The contract-level facts every one of its instalments is judged against.
 *
 * Plain values, not an entity, so the derivation stays pure and free of Exposed.
 *
 * @param expectsInvoice whether a document is expected at all. When false, no instalment of this
 *   contract can ever raise `INVOICE_MISSING`, however late it is — otherwise every rent contract
 *   would complain every month.
 * @param expectedAmount the contract's own expected amount, used for the band only when an
 *   instalment carries none of its own.
 * @param tolerancePct in **percent units** (`15.00` = 15 %), matching the DB and `Percentage`.
 * @param toleranceAbs an absolute allowance in the contract's currency.
 */
data class ContractAlertRules(
    val expectsInvoice: Boolean,
    val expectedAmount: BigDecimal?,
    val tolerancePct: BigDecimal?,
    val toleranceAbs: BigDecimal?,
)
