package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TipRecipient.
 *
 * @param language The target language for localization
 * @return Localized display name for the tip recipient
 */
fun TipRecipient.getName(language: Language): String =
    when (this) {
        TipRecipient.EMPLOYEE -> getString(EnumKeys.tipRecipientEmployee, language)
        TipRecipient.OWNER -> getString(EnumKeys.tipRecipientOwner, language)
    }
