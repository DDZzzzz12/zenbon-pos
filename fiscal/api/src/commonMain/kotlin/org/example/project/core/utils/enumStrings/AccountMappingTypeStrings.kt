package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.kassenbuch.AccountMappingType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this AccountMappingType.
 *
 * @param language The target language for localization
 * @return Localized display name for the account mapping type
 */
fun AccountMappingType.getName(language: Language): String =
    when (this) {
        AccountMappingType.PAYMENT_TYPE -> getString(EnumKeys.accountMappingTypePaymentType, language)
        AccountMappingType.PRODUCT_CATEGORY -> getString(EnumKeys.accountMappingTypeProductCategory, language)
        AccountMappingType.EXPENSE_CATEGORY -> getString(EnumKeys.accountMappingTypeExpenseCategory, language)
        AccountMappingType.VAT_RATE -> getString(EnumKeys.accountMappingTypeVatRate, language)
        AccountMappingType.EMPLOYEE_TYPE -> getString(EnumKeys.accountMappingTypeEmployeeType, language)
        AccountMappingType.TIP_CATEGORY -> getString(EnumKeys.accountMappingTypeTipCategory, language)
        AccountMappingType.DISCOUNT_TYPE -> getString(EnumKeys.accountMappingTypeDiscountType, language)
        AccountMappingType.CASH_REGISTER -> getString(EnumKeys.accountMappingTypeCashRegister, language)
        AccountMappingType.LOCATION -> getString(EnumKeys.accountMappingTypeLocation, language)
        AccountMappingType.SPECIAL_TRANSACTION -> getString(EnumKeys.accountMappingTypeSpecialTransaction, language)
    }
