package org.example.project.features.supplier.data.service

import org.example.project.features.supplier.domain.models.Supplier

/**
 * What resolution decided, for the caller to surface.
 *
 * [ibanWasUnknown] is the payment-redirection signal: the invoice quotes an IBAN this supplier has
 * never used before. It is reported, never acted on here — the capture form warns, and it suppresses
 * IBAN-based match suggestions later.
 *
 * The flag is **transient** on the resolving path: [SupplierResolver] registers the new IBAN
 * immediately after reporting it, so re-resolving the same invoice later returns `false`. On the
 * already-linked path nothing is registered, so the flag stays `true` until the IBAN is accepted.
 * Persisting the warning is the capture UI's problem, not this layer's.
 */
data class SupplierResolution(
    val supplier: Supplier?,
    val created: Boolean,
    val ibanWasUnknown: Boolean,
)
