package org.example.project.features.voucher.mapper

import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.toDecimalString

// The voucher DTO mappers the Z-Bon response needs. They live in the ecr data tier (not beside VoucherMapper in
// :ecr-voucher) because the fiscal server tier may not depend on another area's server tier.

fun Voucher.toSummeryResponse() =
    VoucherDto.VoucherSummeryDto(
        id = this.id.value,
        voucherNumber = this.voucherNumber,
        originalValue = this.originalValue.toDecimalString(),
        employeeNumber = this.orderItem.getEmployee().employeeNumber,
    )

/**
 * Converts VoucherRedeem entity to RedeemVoucherResponse DTO.
 */
fun VoucherRedeem.toResponse(): VoucherDto.RedeemVoucherResponse =
    VoucherDto.RedeemVoucherResponse(
        id = this.id.value,
        voucherNumber = this.voucherNumber,
        voucherSource = this.voucherType,
        redeemedAmount = formatDecimal(this.redeemAmount),
        remainingValue = this.voucher?.remainingValue?.toDecimalString(),
        redeemedAt = this.redeemedAt.toString(),
        billId = this.payment.bill.id.value,
        paymentId = this.payment.id.value,
    )
