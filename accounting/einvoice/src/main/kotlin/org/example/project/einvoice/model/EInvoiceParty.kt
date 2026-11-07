package org.example.project.einvoice.model

/** EN 16931 BG-4 Seller / BG-7 Buyer. Only the terms this project stores. */
data class EInvoiceParty(
    val name: String,
    val vatId: String? = null,
    val taxNumber: String? = null,
    val street: String? = null,
    val zip: String? = null,
    val city: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val email: String? = null,
)
