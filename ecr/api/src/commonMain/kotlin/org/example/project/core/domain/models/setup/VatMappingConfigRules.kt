package org.example.project.core.domain.models.setup

import org.example.project.core.domain.enums.product.ProductType

/**
 * Gets VAT rules for a specific product type.
 *
 * @param productType The product type to get rules for
 * @return VAT rules for the specified product type
 */
fun VatMappingConfig.getRulesFor(productType: ProductType): VatRules =
    when (productType) {
        ProductType.FOOD -> FOOD
        ProductType.DRINKS -> DRINKS
        ProductType.RETAIL -> RETAIL
        ProductType.SERVICE -> SERVICE
        ProductType.VOUCHER -> VOUCHER
        ProductType.OTHERS -> OTHERS
    }
