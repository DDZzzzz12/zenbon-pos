package org.example.project.features.tse.findings

import org.example.project.core.domain.enums.tse.TseFindingKind

/** A TANR range with no ordinary POS record on one device. */
data class TanrFinding(
    val serialHex: String,
    val tanrFrom: Long,
    val tanrTo: Long,
    val kind: TseFindingKind,
)
