/**
 * Payment entity to DTO mapping functions.
 *
 * Provides conversion methods for Payment entities to their corresponding DTOs
 * for API responses and data transfer operations. Handles payment data transformation
 * including amount, type, and transaction details.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.payment.mapper

import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.features.payment.domain.models.Payment
import org.example.project.utils.formatter.formatDecimal

/**
 * Converts Payment entity to PaymentResponse DTO.
 */
fun Payment.toResponse(): PaymentDto.PaymentResponse =
    PaymentDto.PaymentResponse(
        id = this.id.value,
        amount = formatDecimal(this.amount),
        paymentTypeId = this.paymentType.id.value,
        paymentTypeName = this.paymentType.name,
        paymentTypeEN = this.paymentType.enName,
        paymentTypeCH = this.paymentType.chName,
        billId = this.bill.id.value,
        createdAt = this.paymentTime.toString(),
        paymentStatus = this.paymentStatus,
        createsReceivable = this.createsReceivable,
    )

fun Payment.toSummeryDto(): PaymentDto.PaymentSummary =
    PaymentDto.PaymentSummary(
        amount = formatDecimal(this.amount),
        paymentTypeDE = this.paymentType.name,
        paymentTypeCH = this.paymentType.chName,
        paymentTypeEN = this.paymentType.enName,
    )
