package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this TseSigningStatus.
 *
 * @param language The target language for localization
 * @return Localized display name for the TSE signing status
 */
fun TseSigningStatus.getName(language: Language): String =
    when (this) {
        TseSigningStatus.PENDING -> getString(EnumKeys.tseSigningStatusPending, language)
        TseSigningStatus.PROCESSING -> getString(EnumKeys.tseSigningStatusProcessing, language)
        TseSigningStatus.QUEUED -> getString(EnumKeys.tseSigningStatusQueued, language)
        TseSigningStatus.SIGNED -> getString(EnumKeys.tseSigningStatusSigned, language)
        TseSigningStatus.FAILED -> getString(EnumKeys.tseSigningStatusFailed, language)
        TseSigningStatus.CANCELLED -> getString(EnumKeys.tseSigningStatusCancelled, language)
        TseSigningStatus.DISABLED -> getString(EnumKeys.tseSigningStatusDisabled, language)
        TseSigningStatus.SIMULATION -> getString(EnumKeys.tseSigningStatusSimulation, language)
        TseSigningStatus.NOT_APPLICABLE -> getString(EnumKeys.tseSigningStatusNotApplicable, language)
    }
