package org.example.project.banking.model

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * One statement entry. Field names follow ISO 20022; MT940's `:61:` and `:86:` subfields map onto
 * this shape only where the two formats agree. MT940 has no structured counterpart to CAMT.053's
 * `BkTxCd` or structured remittance, so [bankTxDomain]/[bankTxFamily]/[bankTxSubFamily] and
 * [remittanceStructuredRef] stay null for MT940 input.
 *
 * [rawEntry] is an additive escape hatch for source fields not modelled here. It is never a store
 * of record — no business logic may read it.
 */
data class BankTransaction(
    val bookingDate: LocalDate,
    val valueDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val creditDebit: CreditDebit,
    val status: String? = null,
    val bankTxDomain: String? = null,
    val bankTxFamily: String? = null,
    val bankTxSubFamily: String? = null,
    val accountServicerReference: String? = null,
    val endToEndId: String? = null,
    val mandateId: String? = null,
    val creditorId: String? = null,
    val counterpartyName: String? = null,
    val counterpartyIban: String? = null,
    val counterpartyBic: String? = null,
    val remittanceUnstructured: String? = null,
    val remittanceStructuredRef: String? = null,
    val reversalIndicator: Boolean = false,
    val rawEntry: String? = null,
)
