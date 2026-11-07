package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.kassenbuch.DebitCreditIndicator
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this DebitCreditIndicator.
 *
 * @param language The target language for localization
 * @return Localized display name for the debit/credit indicator
 */
fun DebitCreditIndicator.getName(language: Language): String =
    when (this) {
        DebitCreditIndicator.DEBIT -> getString(EnumKeys.debitCreditIndicatorDebit, language)
        DebitCreditIndicator.CREDIT -> getString(EnumKeys.debitCreditIndicatorCredit, language)
    }
