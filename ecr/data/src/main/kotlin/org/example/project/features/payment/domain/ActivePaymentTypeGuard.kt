package org.example.project.features.payment.domain

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.infrastructure.errors.PosException

/**
 * The tender a NEW payment is booked with: it must exist (404 `PAYMENT_TYPE_NOT_FOUND`) and still be
 * active (409 `PAYMENT_TYPE_INACTIVE`).
 *
 * Deactivating a payment type retires it for new money only. Historic payments keep referencing it —
 * storno refunds, Z-Bon, DSFinV-K and the bill history read the stored row and never call this. Every
 * path that writes a new [org.example.project.features.payment.domain.models.Payment] goes through it:
 * bill create (table bills, split bills, the counter voucher sale), the Zahlartwechsel's requested
 * tender, and settling a receivable invoice at the till. The till and adminApp pickers already offer
 * only active types; this is the server half of that guard (rule 8).
 *
 * Needs an ambient transaction.
 */
fun requireActivePaymentType(paymentTypeId: Long): PaymentType {
    val paymentType =
        PaymentType.findById(paymentTypeId)
            ?: throw PosException(
                PosErrorCode.PAYMENT_TYPE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "PaymentType not found: $paymentTypeId",
            )
    if (!paymentType.isActive) {
        throw PosException(
            PosErrorCode.PAYMENT_TYPE_INACTIVE,
            HttpStatusCode.Conflict,
            "Payment type '${paymentType.name}' ($paymentTypeId) is deactivated and cannot take new payments",
        )
    }
    return paymentType
}
