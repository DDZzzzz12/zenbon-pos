package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this PaymentStatus.
 *
 * @param language The target language for localization
 * @return Localized display name for the payment status
 */
fun PaymentStatus.getName(language: Language): String =
    when (this) {
        PaymentStatus.COMPLETED -> getString(EnumKeys.paymentStatusCompleted, language)
        PaymentStatus.REFUNDED -> getString(EnumKeys.paymentStatusRefunded, language)
    }
