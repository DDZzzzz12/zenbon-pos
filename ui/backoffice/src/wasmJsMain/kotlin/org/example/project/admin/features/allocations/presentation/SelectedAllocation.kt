package org.example.project.admin.features.allocations.presentation

/** One candidate the operator has put an amount on: its draft [key], its target id and the canonical [amount]. */
data class SelectedAllocation(
    val key: String,
    val targetId: Long,
    val amount: String,
)
