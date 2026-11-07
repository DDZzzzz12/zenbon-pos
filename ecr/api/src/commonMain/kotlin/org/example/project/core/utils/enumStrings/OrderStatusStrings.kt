package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this OrderStatus.
 *
 * @param language The target language for localization
 * @return Localized display name for the order status
 */
fun OrderStatus.getName(language: Language): String =
    when (this) {
        OrderStatus.DRAFT -> getString(EnumKeys.orderStatusDraft, language)
        OrderStatus.CONFIRMED -> getString(EnumKeys.orderStatusConfirmed, language)
        OrderStatus.IN_PREPARATION -> getString(EnumKeys.orderStatusInPreparation, language)
        OrderStatus.READY -> getString(EnumKeys.orderStatusReady, language)
        OrderStatus.SERVED -> getString(EnumKeys.orderStatusServed, language)
        OrderStatus.COMPLETED -> getString(EnumKeys.orderStatusCompleted, language)
        OrderStatus.CANCELLED -> getString(EnumKeys.orderStatusCancelled, language)
        OrderStatus.REFUNDED -> getString(EnumKeys.orderStatusRefunded, language)
    }
