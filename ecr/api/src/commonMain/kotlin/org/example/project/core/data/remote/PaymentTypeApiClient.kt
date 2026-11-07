/**
 * API client interface for payment type operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.payment.PaymentTypeDto

interface PaymentTypeApiClient {
    suspend fun getAllPaymentTypes(includeInactive: Boolean = false): PaymentTypeDto.PaymentTypeListResponseDto

    suspend fun getPaymentTypeById(id: Long): PaymentTypeDto.PaymentTypeResponseDto

    suspend fun getActivePaymentTypes(): List<PaymentTypeDto.PaymentTypeSummaryDto>

    suspend fun createPaymentType(request: PaymentTypeDto.CreatePaymentTypeRequestDto): PaymentTypeDto.PaymentTypeResponseDto

    suspend fun updatePaymentType(
        id: Long,
        request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
    ): PaymentTypeDto.PaymentTypeResponseDto

    suspend fun deletePaymentType(id: Long)

    suspend fun getPaymentTypeStatistics(): PaymentTypeDto.PaymentTypeStatisticsDto

    suspend fun syncPaymentTypes(lastSyncTime: String): List<PaymentTypeDto.PaymentTypeResponseDto>
}
