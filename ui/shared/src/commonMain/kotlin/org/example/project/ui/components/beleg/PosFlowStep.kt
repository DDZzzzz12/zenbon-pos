package org.example.project.ui.components.beleg

/**
 * One entry of a [PosFlowTimeline]: its localized [label], whether the document has reached it
 * ([done]), whether it is the step waiting to be done next ([current]) and an optional small
 * [detail] line under the label ("02.10.2026 · Max Muster").
 */
data class PosFlowStep(
    val label: String,
    val done: Boolean,
    val detail: String? = null,
    val current: Boolean = false,
)
