package org.example.project.einvoice.check

import org.example.project.einvoice.model.EInvoice

object En16931RequiredFields {
    /** Null when the EInvoice carries every field a valid EN 16931 CII needs; else the first missing one. */
    fun check(invoice: EInvoice): String? {
        if (invoice.seller.name.isBlank()) return "seller name (BT-27) is required"
        if (invoice.seller.vatId.isNullOrBlank() && invoice.seller.taxNumber.isNullOrBlank()) {
            return "seller VAT-ID (BT-31) or tax number (BT-32) is required"
        }
        countryProblem(invoice.seller.country, "seller country (BT-40)")?.let { return it }
        val buyer = invoice.buyer ?: return "buyer (BG-7) is required"
        if (buyer.name.isBlank()) return "buyer name (BT-44) is required"
        if (buyer.street.isNullOrBlank() || buyer.zip.isNullOrBlank() || buyer.city.isNullOrBlank()) {
            return "buyer address (BG-8: street/zip/city) is required"
        }
        countryProblem(buyer.country, "buyer country (BT-55)")?.let { return it }
        if (invoice.lines.isEmpty()) return "at least one invoice line (BG-25) is required"
        if (invoice.vatBreakdown.isEmpty()) return "a VAT breakdown (BG-23) is required"
        if (invoice.invoiceNumber.isBlank()) return "invoice number (BT-1) is required"
        if (invoice.currency.isBlank()) return "currency (BT-5) is required"
        if (invoice.invoiceTypeCode.isNullOrBlank()) return "invoice type code (BT-3) is required"
        return null
    }

    /**
     * BR-09 / BR-11: the country code is mandatory; BR-CL-14: it is ISO 3166-1 alpha-2. Only the
     * shape is checked here — the caller maps free-text names ("Germany") to codes before this runs.
     */
    private fun countryProblem(
        country: String?,
        term: String,
    ): String? {
        if (country.isNullOrBlank()) return "$term is required"
        if (!ISO_ALPHA_2.matches(country)) return "$term must be an ISO 3166-1 alpha-2 code such as DE, got '$country'"
        return null
    }

    private val ISO_ALPHA_2 = Regex("^[A-Z]{2}$")
}
