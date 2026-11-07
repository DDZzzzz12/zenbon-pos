package org.example.project.features.bill.domain.service

import org.example.project.core.domain.enums.tse.TseEntityType

/** One sale Vorgang to sign: what it is, its process data, and the entity and client it belongs to. */
data class SaleSigningRequest(
    val kind: SaleSigningKind,
    val processData: String,
    val referenceId: String,
    val clientId: String,
    val entityType: TseEntityType,
)
