/**
 * VAT entity to DTO mapping functions.
 *
 * Provides conversion methods for VAT entities to their corresponding DTOs
 * for API responses and data transfer operations with proper German tax
 * compliance formatting and validation.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.setup.mapper

import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.features.payment.domain.models.Vat
import java.math.BigDecimal

/**
 * Converts VAT entity to VatResponseDto.
 */
fun Vat.toVatResponse(): VatDto.VatResponseDto {
    val vatPercentageFormatted = "${(this.vatRate * BigDecimal("100")).setScale(2)}%"

    return VatDto.VatResponseDto(
        vatId = this.id.value,
        vatClass = this.vatClass,
        vatRate = this.vatRate.toDouble(),
        vatPercentage = vatPercentageFormatted,
    )
}

/**
 * Converts VAT entity to VatSummaryDto for selection interfaces.
 */
fun Vat.toVatSummary(): VatDto.VatSummaryDto {
    val vatPercentageFormatted = "${(this.vatRate * BigDecimal("100")).setScale(2)}%"
    val standardGermanRates = setOf(BigDecimal("0.00"), BigDecimal("0.07"), BigDecimal("0.19"))

    return VatDto.VatSummaryDto(
        vatId = this.id.value,
        vatClass = this.vatClass,
        vatPercentage = vatPercentageFormatted,
        isStandardRate = this.vatRate in standardGermanRates,
    )
}

/**
 * Extension function for List of VAT entities to convert to response DTOs.
 */
fun List<Vat>.toVatResponseList(): List<VatDto.VatResponseDto> = this.map { it.toVatResponse() }

/**
 * Extension function for List of VAT entities to convert to summary DTOs.
 */
fun List<Vat>.toVatSummaryList(): List<VatDto.VatSummaryDto> = this.map { it.toVatSummary() }
