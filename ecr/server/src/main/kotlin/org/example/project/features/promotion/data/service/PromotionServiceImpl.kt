package org.example.project.features.promotion.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.domain.repository.PromotionRepository
import org.example.project.features.promotion.domain.service.PromotionService
import org.example.project.features.promotion.mapper.toResponseDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.formatDecimal
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Implementation of PromotionService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 11.
 */
class PromotionServiceImpl(
    private val repository: PromotionRepository,
) : PromotionService {
    private val logger = LoggerFactory.getLogger(PromotionServiceImpl::class.java)

    override suspend fun getActivePromotions(): List<PromotionDto.PromotionResponseDto> =
        dbQuery {
            repository.getActiveAndValid().map { it.toResponseDto() }
        }

    override suspend fun getAllPromotions(): List<PromotionDto.PromotionResponseDto> =
        dbQuery {
            repository.getAll().map { it.toResponseDto() }
        }

    override suspend fun getPromotionById(id: Long): PromotionDto.PromotionResponseDto =
        dbQuery {
            val promotion = repository.getById(id) ?: throw promotionNotFound(id)
            promotion.toResponseDto()
        }

    override suspend fun createPromotion(
        employeeId: String,
        request: PromotionDto.CreatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto =
        dbQuery {
            // Structural rules first: a promotion that cannot be applied must never reach the till,
            // where the failure would surface to a cashier mid-checkout instead of to the manager
            // who configured it.
            request.validate().let { errors ->
                if (errors.isNotEmpty()) throw validationFailed(errors.joinToString("; "))
            }
            requireDiscountFitsTargetProduct(request)

            // Round-3 INV-F3: a PERCENTAGE discount above 100 % lets bill.promotionAmount exceed
            // bill.billAmount, which the billing cap then clamps — leaving the BillPromotion audit
            // rows (and the DSFinV-K Rabatt lines) inconsistent with the capped header. Reject at
            // the source. (UpdatePromotionRequestDto carries no discountType/discountValue, so the
            // discount is immutable after create — validating here is sufficient server-side.)
            if (request.discountType == DiscountType.PERCENTAGE.name) {
                val pct =
                    request.discountValue.toBigDecimalOrNull()
                        ?: throw percentageOutOfRange("percentage discount value is not a number: ${request.discountValue}")
                if (pct < BigDecimal.ZERO || pct > BigDecimal(100)) {
                    throw percentageOutOfRange("percentage discount must be between 0 and 100, was $pct")
                }
            }

            val promotion =
                try {
                    repository.create(
                        name = request.name,
                        description = request.description,
                        discountType = request.discountType,
                        discountValue = request.discountValue,
                        scope = request.scope,
                        targetProductId = request.targetProductId,
                        isMultiUse = request.isMultiUse,
                        combinable = request.combinable,
                        validFrom = request.validFrom,
                        validUntil = request.validUntil,
                        createdByEmployeeId = employeeId,
                    )
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid promotion request")
                } catch (e: NoSuchElementException) {
                    throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        e.message ?: "Referenced resource not found",
                    )
                }
            logger.info("createPromotion: created id={}, name={}", promotion.id.value, promotion.name)
            promotion.toResponseDto()
        }

    override suspend fun updatePromotion(
        id: Long,
        request: PromotionDto.UpdatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto =
        dbQuery {
            request.validate().let { errors ->
                if (errors.isNotEmpty()) throw validationFailed(errors.joinToString("; "))
            }
            // An update may move only one end of the window, so the pure DTO check cannot see the
            // other end: compare the requested change against what is stored.
            val stored = repository.getById(id) ?: throw promotionNotFound(id)
            PromotionDto
                .validityWindowErrors(
                    validFrom = request.validFrom ?: stored.validFrom.toString(),
                    validUntil = request.validUntil ?: stored.validUntil?.toString(),
                ).let { errors ->
                    if (errors.isNotEmpty()) throw validationFailed(errors.joinToString("; "))
                }

            val promotion =
                repository.update(id) {
                    request.name?.let { this.name = it }
                    request.description?.let { this.description = it }
                    request.isMultiUse?.let { this.isMultiUse = it }
                    request.combinable?.let { this.combinable = it }
                    request.validFrom?.let { this.validFrom = kotlinx.datetime.LocalDateTime.parse(it) }
                    request.validUntil?.let { this.validUntil = kotlinx.datetime.LocalDateTime.parse(it) }
                    request.isActive?.let { this.isActive = it }
                } ?: throw promotionNotFound(id)
            logger.info("updatePromotion: updated id={}, name={}", id, promotion.name)
            promotion.toResponseDto()
        }

    override suspend fun deactivatePromotion(id: Long) {
        dbQuery {
            val promotion = repository.getById(id) ?: throw promotionNotFound(id)
            promotion.isActive = false
            logger.info("deactivatePromotion: deactivated id={}, name={}", id, promotion.name)
        }
    }

    override suspend fun getPromotionUsage(id: Long): PromotionDto.PromotionUsageDto =
        dbQuery {
            repository.getById(id) ?: throw promotionNotFound(id)
            PromotionDto.PromotionUsageDto(
                promotionId = id,
                timesUsed = repository.getUsageCount(id),
                totalDiscountGiven = formatDecimal(repository.getTotalDiscountGiven(id)),
            )
        }

    /**
     * A FIXED_AMOUNT discount larger than the product it targets can never do what the manager
     * intended: `BillServiceImpl` clamps it to the item's price, so the item merely becomes free.
     *
     * This check does NOT replace that clamp. The catalog price can be lowered after the promotion
     * is created, and an `allowCustomPrice` item can be sold below catalog, so the clamp remains the
     * only thing standing between a mispriced promotion and a negative line item - which would
     * corrupt the VAT bucket, the Z-Bon totals and the DSFinV-K export.
     */
    private fun requireDiscountFitsTargetProduct(request: PromotionDto.CreatePromotionRequestDto) {
        if (request.discountType != DiscountType.FIXED_AMOUNT.name) return
        if (request.scope != PromotionScope.ITEM_SPECIFIC.name) return
        val targetProductId = request.targetProductId ?: return
        val discount = request.discountValue.toBigDecimalOrNull() ?: return
        val product = Product.findById(targetProductId) ?: return
        if (discount > product.price) {
            throw validationFailed(
                "discountValue $discount exceeds the price of the target product " +
                    "'${product.name}' (${product.price}) - the discount could never be applied in full",
            )
        }
    }

    private fun promotionNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Promotion $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun percentageOutOfRange(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.UnprocessableEntity,
            msg,
        )
}
