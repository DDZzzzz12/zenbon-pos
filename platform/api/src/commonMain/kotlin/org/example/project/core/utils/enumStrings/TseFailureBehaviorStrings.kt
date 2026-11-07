package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TseFailureBehavior.
 *
 * @param language The target language for localization
 * @return Localized display name for the TSE failure behavior
 */
fun TseFailureBehavior.getName(language: Language): String =
    when (this) {
        TseFailureBehavior.QUEUE -> getString(EnumKeys.tseFailureBehaviorQueue, language)
        TseFailureBehavior.FAIL -> getString(EnumKeys.tseFailureBehaviorFail, language)
    }
