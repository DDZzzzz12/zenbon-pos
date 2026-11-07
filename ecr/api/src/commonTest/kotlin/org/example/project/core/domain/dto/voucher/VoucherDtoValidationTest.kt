package org.example.project.core.domain.dto.voucher

import org.example.project.core.domain.models.setup.VoucherSourceType
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the money validation on the voucher request DTOs after it moved off `String.toDouble()` onto
 * `Decimal.parse`. The parse both validates the format (throwing on garbage) and yields an exact
 * value for the positivity checks, so these cases guard against the migration loosening or tightening
 * what the server accepts.
 */
class VoucherDtoValidationTest {
    @Test
    fun createVoucher_acceptsPositiveDecimalValueAndZeroTip() {
        // No exception thrown => accepted.
        VoucherDto
            .CreateVoucherRequestDto(
                originalValue = "16.90",
                paymentTypeId = 1L,
                tipAmount = "0.00",
            ).validate()
    }

    @Test
    fun createVoucher_rejectsZeroValue() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto.CreateVoucherRequestDto(originalValue = "0.00", paymentTypeId = 1L).validate()
        }
    }

    @Test
    fun createVoucher_rejectsNegativeValue() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto.CreateVoucherRequestDto(originalValue = "-5.00", paymentTypeId = 1L).validate()
        }
    }

    @Test
    fun createVoucher_rejectsMalformedValue() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto.CreateVoucherRequestDto(originalValue = "abc", paymentTypeId = 1L).validate()
        }
    }

    /** A table voucher is paid with the table's bill, which carries its own tip — a tip here would be lost (T5-3). */
    @Test
    fun createVoucher_rejectsTipOnTableSessionVoucher() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto.CreateVoucherRequestDto(sessionId = 7L, originalValue = "10.00", tipAmount = "2.00").validate()
        }
    }

    @Test
    fun createVoucher_acceptsZeroTipOnTableSessionVoucher() {
        VoucherDto.CreateVoucherRequestDto(sessionId = 7L, originalValue = "10.00", tipAmount = "0.00").validate()
    }

    @Test
    fun createVoucher_rejectsNegativeTip() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto
                .CreateVoucherRequestDto(
                    originalValue = "10.00",
                    paymentTypeId = 1L,
                    tipAmount = "-1.00",
                ).validate()
        }
    }

    @Test
    fun redeem_acceptsPositiveDecimal() {
        VoucherDto
            .RedeemVoucherRequestDto(
                voucherNumber = "G2025-0001",
                redeemedAmount = "12.50",
                voucherSource = VoucherSourceType.INTERNAL,
            ).validate()
    }

    @Test
    fun redeem_rejectsZeroAmount() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto
                .RedeemVoucherRequestDto(
                    voucherNumber = "G2025-0001",
                    redeemedAmount = "0.00",
                    voucherSource = VoucherSourceType.INTERNAL,
                ).validate()
        }
    }

    @Test
    fun redeem_rejectsMalformedAmount() {
        assertFailsWith<IllegalArgumentException> {
            VoucherDto
                .RedeemVoucherRequestDto(
                    voucherNumber = "G2025-0001",
                    redeemedAmount = "xyz",
                    voucherSource = VoucherSourceType.INTERNAL,
                ).validate()
        }
    }
}
