package org.example.project.features.supplier.data.service

/** Counters from one [SupplierBackfill] sweep. */
data class SupplierBackfillReport(
    val linked: Int,
    val skippedAlreadyLinked: Int,
    val skippedNoSeller: Int,
    val failed: List<Long>,
)
