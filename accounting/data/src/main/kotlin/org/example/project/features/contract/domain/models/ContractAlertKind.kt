package org.example.project.features.contract.domain.models

/**
 * What a contract is complaining about.
 *
 * Derived on read, never stored — an alert is a conclusion about current state, and a stored one is
 * wrong from the moment the state changes. The same rule the instalment status follows.
 */
enum class ContractAlertKind {
    PAYMENT_MISSING,
    INVOICE_MISSING,
    AMOUNT_OFF_BAND,
}
