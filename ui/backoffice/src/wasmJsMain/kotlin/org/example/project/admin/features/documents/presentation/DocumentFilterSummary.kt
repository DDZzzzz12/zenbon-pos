package org.example.project.admin.features.documents.presentation

import org.example.project.admin.features.documents.presentation.components.documentAppliedFilters

/** Pure counting and clearing rules of the Belege filter bar, kept out of the composable so they are testable. */
object DocumentFilterSummary {
    /**
     * The Filter button's badge: one per applied panel filter ([documentAppliedFilters]) plus the
     * document type where the panel holds it ([typeInPanel], a phone). The period is not counted: it
     * always has a value, like [DocumentSearchFilter.hasAny] says.
     */
    fun activeCount(
        applied: DocumentSearchFilter,
        typeInPanel: Boolean,
    ): Int = documentAppliedFilters(applied).size + if (typeInPanel && applied.fileType != null) 1 else 0

    /**
     * [applied] with the panel's fields cleared — the seven advanced fields, the statuses and
     * include-inactive, plus the type when [clearType]. Search and period are kept: search is not a
     * panel field and the period always has a value.
     */
    fun cleared(
        applied: DocumentSearchFilter,
        clearType: Boolean,
    ): DocumentSearchFilter =
        applied.clearAllAdvanced().copy(
            includeInactive = false,
            fileType = if (clearType) null else applied.fileType,
        )

    /**
     * Whether the panel shows the period: where the bar has no room for it ([periodInPanel]), or once
     * the [draft] changed it. A resize that moves the stepper back into the bar while the panel is
     * open must not hide an edit that the apply button would still send.
     */
    fun showsPeriod(
        periodInPanel: Boolean,
        applied: DocumentSearchFilter,
        draft: DocumentSearchFilter,
    ): Boolean =
        periodInPanel ||
            draft.granularity != applied.granularity ||
            draft.anchor != applied.anchor ||
            draft.customFrom != applied.customFrom ||
            draft.customTo != applied.customTo

    /** Whether the panel shows the type: on a phone ([typeInPanel]), or once the [draft] changed it. */
    fun showsType(
        typeInPanel: Boolean,
        applied: DocumentSearchFilter,
        draft: DocumentSearchFilter,
    ): Boolean = typeInPanel || draft.fileType != applied.fileType
}
