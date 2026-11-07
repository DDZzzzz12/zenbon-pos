/**
 * API client-backed implementation of [PaymentTypeDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style — exceptions propagate.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.remote.PaymentTypeApiClient

class ApiClientPaymentTypeDataProvider(
    private val paymentTypeApiClient: PaymentTypeApiClient,
) : PaymentTypeDataProvider {
    override suspend fun getAllPaymentTypes(): List<PaymentType> =
        paymentTypeApiClient
            .getAllPaymentTypes(includeInactive = true)
            .paymentTypes
            .map { PaymentType.fromDto(it) }

    override suspend fun getActivePaymentTypes(): List<PaymentType> =
        paymentTypeApiClient
            .getAllPaymentTypes(includeInactive = false)
            .paymentTypes
            .map { PaymentType.fromDto(it) }

    override suspend fun getPaymentTypeById(id: Long): PaymentType? = PaymentType.fromDto(paymentTypeApiClient.getPaymentTypeById(id))
}
