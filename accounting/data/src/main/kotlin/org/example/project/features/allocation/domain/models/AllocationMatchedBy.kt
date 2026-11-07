package org.example.project.features.allocation.domain.models

/**
 * How this allocation came about, so a wrong rule can be found and undone later.
 *
 * `AUTO_RULE` is unused until the suggestion engine lands, and is declared now so the column's
 * meaning does not change when it does.
 *
 * Stored as its [name] in `payment_allocation.matched_by` — renaming a constant orphans every row
 * that used the old name, so `AllocationKindTest` pins these.
 */
enum class AllocationMatchedBy {
    MANUAL,
    SUGGESTION_ACCEPTED,
    AUTO_RULE,
}
