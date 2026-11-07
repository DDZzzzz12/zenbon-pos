package org.example.project.admin.core.presentation.components

/**
 * The filter-panel draft of a list whose one filter is a **mandatory single-choice bucket**
 * (Rechnungen, Ausgangsrechnungen): the server always lists exactly one bucket, so there is no
 * "unfiltered" state to clear back to. See [BucketFilterPanel].
 *
 * On a phone the buckets are a panel field, applied only by the panel's button; wider windows keep
 * them as chips above the list, so they have no panel. A resize that widens the window while the panel
 * is open keeps a bucket the draft already changed ([showsBuckets]), so the edit is never silently
 * dropped.
 */
data class BucketPanelFilter<B>(
    val bucket: B,
) {
    /** The buckets are a panel field on a phone, or once this draft changed the applied one. */
    fun showsBuckets(
        compact: Boolean,
        applied: B,
    ): Boolean = compact || bucket != applied

    /** The bucket the apply button must select, or null when the draft left it as applied. */
    fun toApply(applied: B): B? = bucket.takeIf { it != applied }

    /** The panel's reset: back to the screen's opening bucket. */
    fun cleared(default: B): BucketPanelFilter<B> = BucketPanelFilter(default)
}
