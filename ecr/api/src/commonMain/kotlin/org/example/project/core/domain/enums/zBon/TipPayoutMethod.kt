package org.example.project.core.domain.enums.zBon

import kotlinx.serialization.Serializable

/**
 * How an employee's tips (TrinkgeldAN) from a closing were settled.
 *
 * - [CASH]: handed out from the fiscal Ladenkasse — a TSE-signed Kassenbuch withdrawal (line kind
 *   `EMPLOYEE_TIP`), DSFinV-K TrinkgeldAN, DATEV on the employee-tip account.
 * - [BANK_PAYROLL]: paid by bank transfer or with the payroll. No cash moves, so the POS writes
 *   **no** Kassenbuch entry, no DSFinV-K row and no DATEV booking — it only records that the open
 *   amount is settled. The tax adviser books the bank/payroll payment against the transit account
 *   (Durchlaufende Posten, SKR03 1590) the closing credited the tips to.
 */
@Serializable
enum class TipPayoutMethod {
    CASH,
    BANK_PAYROLL,
}
