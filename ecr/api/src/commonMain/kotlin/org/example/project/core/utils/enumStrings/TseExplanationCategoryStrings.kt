package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/** The on-screen name of this explanation category. The CSV export keeps writing [name]. */
fun TseExplanationCategory.getName(language: Language): String =
    when (this) {
        TseExplanationCategory.TANR_WITHOUT_POS_RECORD -> getString(EnumKeys.tseExplTanrWithoutPos, language)
        TseExplanationCategory.ORPHAN_ABORT -> getString(EnumKeys.tseExplOrphanAbort, language)
        TseExplanationCategory.DUPLICATE_SIGNATURE -> getString(EnumKeys.tseExplDuplicateSignature, language)
        TseExplanationCategory.SUPERSEDED -> getString(EnumKeys.tseExplSuperseded, language)
        TseExplanationCategory.DUPLICATE_TANR -> getString(EnumKeys.tseExplDuplicateTanr, language)
        TseExplanationCategory.SIGNATURE_COUNTER_GAP -> getString(EnumKeys.tseExplSignatureCounterGap, language)
        TseExplanationCategory.TSE_OUTAGE -> getString(EnumKeys.tseExplTseOutage, language)
        TseExplanationCategory.OTHER -> getString(EnumKeys.tseExplOther, language)
    }
