package org.example.project.admin.features.dashboard.presentation.sections

/**
 * The TSE's runtime state, not its setup state.
 *
 * The setup checklist answers "is a TSE commissioned" once, at onboarding, then goes away. This
 * answers "is it signing right now", which is the question every other day asks.
 *
 * [operational] requires both a healthy device and an empty signing backlog: a device reporting
 * healthy while transactions sit unsigned is not a device an operator should be told is fine.
 */
data class TseHealthState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val healthy: Boolean = false,
    val unsignedCount: Long = 0,
) {
    val operational: Boolean get() = healthy && unsignedCount == 0L
}
