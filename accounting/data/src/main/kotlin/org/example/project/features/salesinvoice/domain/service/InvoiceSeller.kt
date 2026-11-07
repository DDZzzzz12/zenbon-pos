package org.example.project.features.salesinvoice.domain.service

/**
 * Our own details at the moment of issue — snapshotted because `shop_info` is editable.
 *
 * The identity block (name, tax ids, address) is frozen in the `sales_invoice.seller_*` columns.
 * The contact and bank fields below have no columns: they are read from `shop_info` when the PDF is
 * rendered, which happens exactly once, at issue (or cancellation), and the archived file is what
 * every reprint serves — so they are frozen in the document itself.
 */
data class InvoiceSeller(
    val name: String,
    val vatId: String?,
    val taxNumber: String?,
    val street: String?,
    val zip: String?,
    val city: String?,
    val country: String?,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val bankName: String? = null,
    val bankIban: String? = null,
    val bankBic: String? = null,
)
