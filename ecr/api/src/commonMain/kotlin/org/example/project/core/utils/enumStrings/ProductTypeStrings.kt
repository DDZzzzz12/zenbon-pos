package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this ProductType.
 *
 * @param language The target language for localization
 * @return Localized display name for the product type
 */
fun ProductType.getName(language: Language): String =
    when (this) {
        ProductType.FOOD -> getString(EnumKeys.productTypeFood, language)
        ProductType.DRINKS -> getString(EnumKeys.productTypeDrinks, language)
        ProductType.RETAIL -> getString(EnumKeys.productTypeRetail, language)
        ProductType.SERVICE -> getString(EnumKeys.productTypeService, language)
        ProductType.OTHERS -> getString(EnumKeys.productTypeOthers, language)
        ProductType.VOUCHER -> getString(EnumKeys.productTypeVoucher, language)
    }
