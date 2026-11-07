package org.example.project.core.domain.dto.promotion

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope

object PromotionDto {
    @Serializable
    data class PromotionResponseDto(
        val id: Long,
        val name: String,
        val description: String?,
        val discountType: String,
        val discountValue: String,
        val scope: String,
        val targetProductId: Long?,
        val targetProductName: String?,
        /** Whether the SAME promotion may repeat within one bill. Contrast [combinable]. */
        val isMultiUse: Boolean,
        /**
         * Whether this promotion may share a bill with OTHER promotions
         * ("Nicht mit anderen Aktionen kombinierbar" when false). Contrast [isMultiUse], which is
         * about repeating this one. Defaults to `true` so an older client that does not send it
         * keeps today's stacking behaviour.
         */
        val combinable: Boolean = true,
        val validFrom: String,
        val validUntil: String?,
        val isActive: Boolean,
        val createdBy: String,
        val createdAt: String,
    )

    @Serializable
    data class CreatePromotionRequestDto(
        val name: String,
        val description: String? = null,
        val discountType: String,
        val discountValue: String,
        val scope: String,
        val targetProductId: Long? = null,
        /** Whether the SAME promotion may repeat within one bill. Contrast [combinable]. */
        val isMultiUse: Boolean = false,
        /**
         * Whether this promotion may share a bill with OTHER promotions. Contrast [isMultiUse].
         * Defaults to `true` — stacking stays the norm, non-combinability is the exception.
         */
        val combinable: Boolean = true,
        val validFrom: String,
        val validUntil: String? = null,
    ) {
        /**
         * Structural rules a promotion must satisfy to be usable at all.
         *
         * These were previously enforced only when a cashier applied the promotion to a bill, which
         * meant a manager could save a configuration that could never work and only learn about it
         * from a failed checkout. The price-dependent rule (a FIXED_AMOUNT above the target
         * product's price) needs a database lookup and therefore lives in the service.
         */
        fun validate(): List<String> =
            buildList {
                if (name.isBlank()) add("name is required")

                // A FREE_ITEM discount is defined as "this item costs nothing", so it needs an item.
                // BillServiceImpl rejects the BILL_WIDE combination outright.
                if (discountType == DiscountType.FREE_ITEM.name && scope == PromotionScope.BILL_WIDE.name) {
                    add("FREE_ITEM promotions must target a specific item - choose ITEM_SPECIFIC scope")
                }
                if (scope == PromotionScope.ITEM_SPECIFIC.name && targetProductId == null) {
                    add("targetProductId is required for an ITEM_SPECIFIC promotion")
                }
                if (discountType == DiscountType.FREE_ITEM.name && targetProductId == null) {
                    add("targetProductId is required for a FREE_ITEM promotion")
                }

                addAll(validityWindowErrors(validFrom, validUntil))
            }
    }

    @Serializable
    data class UpdatePromotionRequestDto(
        val name: String? = null,
        val description: String? = null,
        /** Whether the SAME promotion may repeat within one bill. Contrast [combinable]. */
        val isMultiUse: Boolean? = null,
        /** Whether this promotion may share a bill with OTHER promotions. Contrast [isMultiUse]. */
        val combinable: Boolean? = null,
        val validFrom: String? = null,
        val validUntil: String? = null,
        val isActive: Boolean? = null,
    ) {
        /**
         * Only the validity window is checkable here: an update carries no discountType,
         * discountValue, scope or targetProductId, so those stay as validated at creation. When the
         * request changes only one end of the window the service compares against the stored value.
         */
        fun validate(): List<String> =
            buildList {
                if (name != null && name.isBlank()) add("name must not be blank")
                addAll(validityWindowErrors(validFrom, validUntil))
            }
    }

    /**
     * `validUntil` must be after `validFrom`. Unparseable values are ignored here - the repository's
     * `LocalDateTime.parse` reports those, and guessing at a malformed string would only produce a
     * second, more confusing message for the same mistake.
     */
    fun validityWindowErrors(
        validFrom: String?,
        validUntil: String?,
    ): List<String> =
        buildList {
            if (validFrom == null || validUntil == null) return@buildList
            val from = runCatching { LocalDateTime.parse(validFrom) }.getOrNull() ?: return@buildList
            val until = runCatching { LocalDateTime.parse(validUntil) }.getOrNull() ?: return@buildList
            if (until <= from) add("validUntil ($validUntil) must be after validFrom ($validFrom)")
        }

    @Serializable
    data class PromotionUsageDto(
        val promotionId: Long,
        val timesUsed: Long,
        val totalDiscountGiven: String,
    )

    @Serializable
    data class PromotionApplyRequestDto(
        val promotionId: Long,
        val orderItemId: Long? = null,
    )

    @Serializable
    data class BillPromotionResponseDto(
        val id: Long,
        val promotionId: Long,
        val promotionName: String,
        val orderItemId: Long?,
        val deductedAmount: String,
        val appliedBy: String,
        val appliedAt: String,
    )
}
