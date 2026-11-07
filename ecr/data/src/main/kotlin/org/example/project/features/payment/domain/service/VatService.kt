package org.example.project.features.payment.domain.service

import org.example.project.core.domain.dto.setup.VatDto
import java.math.BigDecimal

/**
 * VAT service interface defining business logic operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 4.
 */
interface VatService {
    /**
     * Retrieves all VAT rates ordered by VAT class.
     */
    suspend fun getAllVatRates(): List<VatDto.VatResponseDto>

    /**
     * Retrieves a VAT rate by its unique identifier.
     *
     * @return VatResponseDto if found, null otherwise
     */
    suspend fun getVatRateById(vatId: String): VatDto.VatResponseDto?

    /**
     * Retrieves a VAT rate by its classification.
     *
     * @return VatResponseDto if found, null otherwise
     */
    suspend fun getVatRateByClass(vatClass: String): VatDto.VatResponseDto?

    /**
     * Retrieves VAT rates within a specific rate range.
     */
    suspend fun getVatRatesByRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): List<VatDto.VatResponseDto>

    /**
     * Retrieves all standard German VAT rates (0%, 7%, 19%).
     */
    suspend fun setStandardGermanVatRates(): List<VatDto.VatResponseDto>

    /**
     * Creates a new VAT rate with validation and German compliance checks.
     */
    suspend fun createVatRate(
        employeeId: String,
        request: VatDto.CreateVatRequestDto,
    ): VatDto.VatResponseDto

    /**
     * Updates an existing VAT rate with validation.
     */
    suspend fun updateVatRate(
        employeeId: String,
        vatId: String,
        request: VatDto.UpdateVatRequestDto,
    ): VatDto.VatResponseDto

    /**
     * Deletes a VAT rate with referential integrity checks.
     *
     * @return The deleted VAT class name
     */
    suspend fun deleteVatRate(
        employeeId: String,
        vatId: String,
    ): String

    /**
     * Checks if a VAT rate exists for the given ID.
     */
    suspend fun vatRateExists(vatId: String): Boolean

    /**
     * Checks if a VAT class is already in use.
     */
    suspend fun vatClassExists(vatClass: String): Boolean

    /**
     * Checks if a VAT rate can be safely deleted (no referencing entities).
     */
    suspend fun canDeleteVatRate(vatId: String): Boolean

    /**
     * Counts the total number of VAT rates in the system.
     */
    suspend fun getVatRateCount(): Long

    /**
     * Counts VAT rates within a specific rate range.
     */
    suspend fun countVatRatesByRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): Long

    /**
     * Retrieves VAT rates as lightweight summaries for UI selection.
     */
    suspend fun getVatRatesForSelection(): List<VatDto.VatSummaryDto>
}
