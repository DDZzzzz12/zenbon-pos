package org.example.project.features.payment.domain.service

/** Admission check for VAT rates (a decimal fraction, e.g. 0.19); throws when the rate is not acceptable. */
fun interface VatRateAdmission {
    fun requireMappable(rate: Double)
}
