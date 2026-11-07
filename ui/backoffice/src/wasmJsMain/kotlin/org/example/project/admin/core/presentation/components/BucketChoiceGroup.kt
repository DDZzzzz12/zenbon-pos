package org.example.project.admin.core.presentation.components

/**
 * One captioned group of buckets in a [BucketFilterPanel] ("Erfassung", "Zahlung", "Status"). The
 * groups of one panel together form a single choice: picking a bucket in one group deselects the rest.
 */
data class BucketChoiceGroup<B>(
    val caption: String,
    val buckets: List<B>,
)
