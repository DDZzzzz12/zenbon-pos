package org.example.project.core.domain.enums.setting

import kotlinx.serialization.Serializable

@Serializable
enum class DatevExportSource {
    /** Produced by the cadence-driven DATEV_AUTO_EXPORT scheduled job. Counts toward catchup coverage. */
    AUTO,

    /** Produced by an ad-hoc Monthly Close export. Audit-only; catchup detection ignores. */
    MANUAL,
}
