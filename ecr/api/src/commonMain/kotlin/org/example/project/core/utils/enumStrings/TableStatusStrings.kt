package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TableStatus.
 *
 * @param language The target language for localization
 * @return Localized display name for the table status
 */
fun TableStatus.getName(language: Language): String =
    when (this) {
        TableStatus.FREE -> getString(EnumKeys.tableStatusFree, language)
        TableStatus.OCCUPIED -> getString(EnumKeys.tableStatusOccupied, language)
        TableStatus.RESERVED -> getString(EnumKeys.tableStatusReserved, language)
    }
