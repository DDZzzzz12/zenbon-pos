package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/** What the TANR continuity check found. */
@Serializable
enum class TseFindingKind {
    /** TANRs the device issued that no POS row records. */
    GAP,

    /** The same (serial, TANR) recorded more than once, e.g. in two serial encodings. */
    DUPLICATE,
}
