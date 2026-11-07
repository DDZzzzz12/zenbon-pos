package org.example.project.features.tse.data.service

import org.example.project.core.domain.enums.tse.TseEntityType

/**
 * One Vorgang to sign. ([referenceId], [entityType]) is the Vorgang's identity. It must equal the
 * signing queue's key for the same Vorgang, because that key is how a retry finds a started TANR
 * (design 2026-09-22 §4.1).
 */
data class TseSignRequest(
    val referenceId: String,
    val entityType: TseEntityType,
    val processType: String,
    val processData: String,
    val clientId: String,
)
