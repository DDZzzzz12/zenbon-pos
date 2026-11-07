/**
 * Enumeration for DATEV debit/credit indicators.
 *
 * Defines the debit/credit posting indicators required for DATEV export format
 * according to German accounting standards. Used to indicate the direction of
 * account postings in double-entry bookkeeping.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.kassenbuch

/**
 * DATEV-compliant debit/credit indicators for account postings.
 *
 * In German accounting and DATEV format, these indicators specify whether
 * an amount should be posted to the debit (Soll) or credit (Haben) side
 * of an account.
 */
enum class DebitCreditIndicator(
    val code: String,
    val displayName: String,
    val description: String,
) {
    /** Soll (Debit) - increases asset and expense accounts */
    DEBIT(
        code = "S",
        displayName = "Soll",
        description = "Debit posting - increases assets, expenses, and drawings",
    ),

    /** Haben (Credit) - increases liability, equity, and revenue accounts */
    CREDIT(
        code = "H",
        displayName = "Haben",
        description = "Credit posting - increases liabilities, equity, and revenues",
    ),
    ;

    companion object {
        /**
         * Gets the indicator from DATEV code.
         *
         * @param code The DATEV code ("S" or "H")
         * @return The corresponding indicator
         * @throws IllegalArgumentException if code is invalid
         */
        fun fromCode(code: String): DebitCreditIndicator =
            when (code.uppercase()) {
                "S" -> DEBIT
                "H" -> CREDIT
                else -> throw IllegalArgumentException("Invalid DATEV indicator code: $code")
            }

        /**
         * Determines the appropriate indicator for a cash transaction.
         *
         * @param isIncoming Whether the transaction increases cash
         * @return DEBIT for incoming cash, CREDIT for outgoing cash
         */
        fun forCashTransaction(isIncoming: Boolean): DebitCreditIndicator = if (isIncoming) DEBIT else CREDIT

        /**
         * Determines the indicator based on account type and transaction nature.
         *
         * @param accountNumber The SKR04 account number
         * @param isIncrease Whether the transaction increases the account balance
         * @return The appropriate debit/credit indicator
         */
        fun forAccount(
            accountNumber: String,
            isIncrease: Boolean,
        ): DebitCreditIndicator {
            val accountType =
                when (accountNumber.first()) {
                    '0', '1' -> AccountType.ASSET
                    '2' -> AccountType.LIABILITY
                    '3' -> AccountType.EQUITY
                    '4', '5', '6', '7' -> AccountType.EXPENSE
                    '8', '9' -> AccountType.REVENUE
                    else -> AccountType.ASSET // Default
                }

            return when (accountType) {
                AccountType.ASSET, AccountType.EXPENSE -> if (isIncrease) DEBIT else CREDIT
                AccountType.LIABILITY, AccountType.EQUITY, AccountType.REVENUE -> if (isIncrease) CREDIT else DEBIT
            }
        }
    }

    /**
     * Internal enum for account type classification.
     */
    private enum class AccountType {
        ASSET,
        LIABILITY,
        EQUITY,
        EXPENSE,
        REVENUE,
    }
}
