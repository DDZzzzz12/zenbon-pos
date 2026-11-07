package org.example.project.features.tse.domain

import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.infrastructure.errors.PosException

/**
 * What the caller must do after a sign attempt. The caller already holds the
 * TseTransaction (if any) and attaches it itself; this only decides status/flow.
 */
data class TseSigningDecision(
    val status: TseSigningStatus,
    val enqueue: Boolean,
    val rethrow: PosException?,
)
