package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/** Why a TANR, or a range of them, has no ordinary POS record (design 2026-09-22 §4.7). */
@Serializable
enum class TseExplanationCategory {
    TANR_WITHOUT_POS_RECORD,
    ORPHAN_ABORT,
    DUPLICATE_SIGNATURE,
    SUPERSEDED,
    DUPLICATE_TANR,
    SIGNATURE_COUNTER_GAP,
    TSE_OUTAGE,
    OTHER,
}
