package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TseEntityType.
 *
 * @param language The target language for localization
 * @return Localized display name for the TSE entity type
 */
fun TseEntityType.getName(language: Language): String =
    when (this) {
        TseEntityType.BILL -> getString(EnumKeys.tseEntityTypeBill, language)
        TseEntityType.CANCELLATION -> getString(EnumKeys.tseEntityTypeCancellation, language)
        TseEntityType.ORDER -> getString(EnumKeys.tseEntityTypeOrder, language)
        TseEntityType.VOUCHER -> getString(EnumKeys.tseEntityTypeVoucher, language)
        TseEntityType.KASSENBUCH -> getString(EnumKeys.tseEntityTypeKassenbuch, language)
    }
