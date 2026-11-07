package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this PrinterType.
 *
 * @param language The target language for localization
 * @return Localized display name for the printer type
 */
fun PrinterType.getName(language: Language): String =
    when (this) {
        PrinterType.USB -> getString(EnumKeys.printerTypeUsb, language)
        PrinterType.NETWORK -> getString(EnumKeys.printerTypeNetwork, language)
        PrinterType.IPP -> getString(EnumKeys.printerTypeIpp, language)
    }
