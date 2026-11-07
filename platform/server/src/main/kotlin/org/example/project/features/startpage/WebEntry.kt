package org.example.project.features.startpage

import org.example.project.core.domain.enums.setup.StartPageTarget

/** One page linked from `/`. [target] is non-null for pages that may be a default (admin, pos). */
data class WebEntry(
    val id: String,
    val path: String,
    val labelKey: String,
    val hintKey: String?,
    val target: StartPageTarget?,
)
