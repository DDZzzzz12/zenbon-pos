package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.bill.TransactionType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TransactionType.
 *
 * @param language The target language for localization
 * @return Localized display name for the transaction type
 */
fun TransactionType.getName(language: Language): String =
    when (this) {
        TransactionType.BILL -> getString(EnumKeys.transactionTypeBill, language)
    }
