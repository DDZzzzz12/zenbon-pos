/**
 * SQLite-backed implementation of [PaymentTypeDataProvider].
 *
 * Stage C Batch 9: flipped to throws-style — DB exceptions propagate.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider.local

import org.example.project.core.data.database.PaymentTypeDataManager
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.provider.PaymentTypeDataProvider

/**
 * Local data provider wrapping [PaymentTypeDataManager] for payment type reads.
 */
class LocalPaymentTypeDataProvider(
    private val paymentTypeDataManager: PaymentTypeDataManager,
) : PaymentTypeDataProvider {
    override suspend fun getAllPaymentTypes(): List<PaymentType> =
        paymentTypeDataManager
            .getAllPaymentTypes()
            .map { PaymentType.fromDatabase(it) }

    override suspend fun getActivePaymentTypes(): List<PaymentType> =
        paymentTypeDataManager
            .getActivePaymentTypes()
            .map { PaymentType.fromDatabase(it) }

    override suspend fun getPaymentTypeById(id: Long): PaymentType? = paymentTypeDataManager.getPaymentTypeById(id)?.let { PaymentType.fromDatabase(it) }
}
