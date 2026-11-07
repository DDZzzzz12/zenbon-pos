package org.example.project.banking.model

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * A parsed or to-be-generated bank statement, independent of wire format and of this project's
 * persistence and DTO layers.
 *
 * [accountIban], [statementNumber] and [statementDate] are nullable because MT940's `:25:` often
 * carries a legacy account number rather than an IBAN, and the `PDF`/`MANUAL` formats may carry
 * none of these at all.
 */
data class BankStatement(
    val format: BankStatementFormat,
    val currency: String,
    val transactions: List<BankTransaction> = emptyList(),
    val accountIban: String? = null,
    val statementNumber: String? = null,
    val statementDate: LocalDate? = null,
    val accountBic: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val legalSequenceNumber: String? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val openingBalance: BigDecimal? = null,
    val closingBalance: BigDecimal? = null,
)
