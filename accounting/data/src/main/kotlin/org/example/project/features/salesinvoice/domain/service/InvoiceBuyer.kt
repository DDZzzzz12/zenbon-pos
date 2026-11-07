package org.example.project.features.salesinvoice.domain.service

/** Who the invoice is made out to. Typed by a human; no master-data record is required. */
data class InvoiceBuyer(
    val name: String,
    val vatId: String? = null,
    val street: String? = null,
    val zip: String? = null,
    val city: String? = null,
    val country: String? = null,
    val email: String? = null,
)
