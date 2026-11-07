package org.example.project.testutils.fakes

import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.domain.dto.payment.PaymentTypeDto

/**
 * Configurable [PaymentTypeApiClient] fake for ViewModel tests.
 *
 * Set [paymentTypes] for the happy path or [failure] to simulate an API error
 * on [getAllPaymentTypes] and [createPaymentType]; [createPaymentType] records into [created];
 * all other operations are unimplemented.
 */
class FakePaymentTypeApiClient : PaymentTypeApiClient {
    var paymentTypes: List<PaymentTypeDto.PaymentTypeResponseDto> = emptyList()
    var failure: Exception? = null

    override suspend fun getAllPaymentTypes(includeInactive: Boolean): PaymentTypeDto.PaymentTypeListResponseDto {
        failure?.let { throw it }
        return PaymentTypeDto.PaymentTypeListResponseDto(
            paymentTypes = paymentTypes,
            total = paymentTypes.size,
            activeCount = paymentTypes.count { it.isActive },
            inactiveCount = paymentTypes.count { !it.isActive },
        )
    }

    override suspend fun getPaymentTypeById(id: Long) = throw NotImplementedError()

    override suspend fun getActivePaymentTypes() = throw NotImplementedError()

    /** Every create request received, in order — a double submit shows up as two entries. */
    val created = mutableListOf<PaymentTypeDto.CreatePaymentTypeRequestDto>()

    override suspend fun createPaymentType(request: PaymentTypeDto.CreatePaymentTypeRequestDto): PaymentTypeDto.PaymentTypeResponseDto {
        created += request
        failure?.let { throw it }
        return PaymentTypeDto.PaymentTypeResponseDto(
            id = created.size.toLong(),
            name = request.name,
            isActive = true,
            updatedAt = "2026-10-15T00:00:00",
        )
    }

    override suspend fun updatePaymentType(
        id: Long,
        request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
    ) = throw NotImplementedError()

    override suspend fun deletePaymentType(id: Long) = throw NotImplementedError()

    override suspend fun getPaymentTypeStatistics() = throw NotImplementedError()

    override suspend fun syncPaymentTypes(lastSyncTime: String) = throw NotImplementedError()
}
