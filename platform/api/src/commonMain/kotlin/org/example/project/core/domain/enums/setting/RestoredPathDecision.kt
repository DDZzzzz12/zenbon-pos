package org.example.project.core.domain.enums.setting

import kotlinx.serialization.Serializable

/** An administrator's decision on one restored path flagged as belonging to another installation. */
@Serializable
enum class RestoredPathDecision {
    /** Keep the restored path as configured — it is reachable and intended from this installation. */
    KEEP,

    /** Replace it with what this installation would use from its own app-data directory. */
    USE_DEFAULT,
}
