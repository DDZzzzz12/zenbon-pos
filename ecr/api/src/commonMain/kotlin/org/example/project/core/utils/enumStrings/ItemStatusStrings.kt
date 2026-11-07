package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this ItemStatus.
 *
 * @param language The target language for localization
 * @return Localized display name for the item status
 */
fun ItemStatus.getName(language: Language): String =
    when (this) {
        ItemStatus.OPEN -> getString(EnumKeys.itemStatusOpen, language)
        ItemStatus.PAID -> getString(EnumKeys.itemStatusPaid, language)
        ItemStatus.CANCELLED -> getString(EnumKeys.itemStatusCancelled, language)
    }
