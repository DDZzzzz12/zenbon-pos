package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized on-screen name for this voucher source.
 *
 * [VoucherSourceType.german] stays the label for printed documents; screens use this instead, so a
 * Z-Bon panel no longer shows the raw enum name ("INTERNAL").
 *
 * @param language The target language for localization
 */
fun VoucherSourceType.getName(language: Language): String =
    when (this) {
        VoucherSourceType.INTERNAL -> getString(EnumKeys.voucherSourceInternal, language)
        VoucherSourceType.LEGACY -> getString(EnumKeys.voucherSourceLegacy, language)
        VoucherSourceType.EXTERNAL -> getString(EnumKeys.voucherSourceExternal, language)
        VoucherSourceType.PROMOTIONAL -> getString(EnumKeys.voucherSourcePromotional, language)
        VoucherSourceType.GIFT_CARD -> getString(EnumKeys.voucherSourceGiftCard, language)
    }

/**
 * The name a NEW source of this type is pre-filled with, in [language] (the operator's UI language).
 *
 * The text is stored as typed (the till shows it on its redeem chips), so the dialog fills the field
 * with what it will save instead of a hidden English constant, and the operator can still change it.
 */
fun VoucherSourceType.defaultSourceName(language: Language): String =
    when (this) {
        VoucherSourceType.INTERNAL -> getString(EnumKeys.voucherSourceDefaultName, language)
        VoucherSourceType.LEGACY -> getString(EnumKeys.voucherSourceDefaultNameLegacy, language)
        VoucherSourceType.EXTERNAL -> getString(EnumKeys.voucherSourceDefaultNameExternal, language)
        VoucherSourceType.PROMOTIONAL -> getString(EnumKeys.voucherSourceDefaultNamePromotional, language)
        VoucherSourceType.GIFT_CARD -> getString(EnumKeys.voucherSourceDefaultNameGiftCard, language)
    }

/** The description a NEW source of this type is pre-filled with, in [language]; see [defaultSourceName]. */
fun VoucherSourceType.defaultSourceDescription(language: Language): String =
    when (this) {
        VoucherSourceType.INTERNAL -> getString(EnumKeys.voucherSourceDefaultDescription, language)
        VoucherSourceType.LEGACY -> getString(EnumKeys.voucherSourceDefaultDescriptionLegacy, language)
        VoucherSourceType.EXTERNAL -> getString(EnumKeys.voucherSourceDefaultDescriptionExternal, language)
        VoucherSourceType.PROMOTIONAL -> getString(EnumKeys.voucherSourceDefaultDescriptionPromotional, language)
        VoucherSourceType.GIFT_CARD -> getString(EnumKeys.voucherSourceDefaultDescriptionGiftCard, language)
    }
