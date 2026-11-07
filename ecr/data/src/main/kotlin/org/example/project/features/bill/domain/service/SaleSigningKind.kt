package org.example.project.features.bill.domain.service

/**
 * Which kind of sale Vorgang is signed. The fiscal implementation maps it to the TSE process type:
 * [ORDER] is `Bestellung-V1` (orders and item cancellations), [RECEIPT] is `Kassenbeleg-V1` (bills,
 * including Storno Belege).
 */
enum class SaleSigningKind {
    ORDER,
    RECEIPT,
}
