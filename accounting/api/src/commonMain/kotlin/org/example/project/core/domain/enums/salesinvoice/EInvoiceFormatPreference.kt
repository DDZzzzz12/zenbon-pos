package org.example.project.core.domain.enums.salesinvoice

/**
 * A debtor's preferred output format for the invoices issued to them. Slice 4e.
 *
 * - [NONE] — plain PDF, the default. Every existing debtor stays on this until an operator opts in.
 * - [ZUGFERD] — a hybrid PDF/A-3 e-invoice (ZUGFeRD/Factur-X), as built by the `:accounting-einvoice` writer.
 *
 * Deliberately not the `:accounting-einvoice` module's `EInvoiceFormat` — that is a pure-JVM leaf module that
 * `shared`/`core-data` must not depend on.
 */
enum class EInvoiceFormatPreference {
    NONE,
    ZUGFERD,
}
