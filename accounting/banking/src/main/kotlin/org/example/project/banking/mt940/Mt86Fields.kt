package org.example.project.banking.mt940

/**
 * What a `:86:` field yielded. Every member is nullable: an undecodable field produces an instance
 * carrying only [remittance], and that is a correct outcome rather than a degraded one.
 */
data class Mt86Fields(
    val gvc: String? = null,
    val postingText: String? = null,
    val remittance: String? = null,
    val counterpartyName: String? = null,
    val counterpartyIban: String? = null,
    val counterpartyBic: String? = null,
    val endToEndId: String? = null,
    val mandateId: String? = null,
    val creditorId: String? = null,
)
