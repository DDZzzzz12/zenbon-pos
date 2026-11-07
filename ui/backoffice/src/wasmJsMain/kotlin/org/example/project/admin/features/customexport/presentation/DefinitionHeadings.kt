package org.example.project.admin.features.customexport.presentation

import org.example.project.core.domain.dto.customexport.CustomExportDto

/**
 * The heading to **show** for a stored column.
 *
 * Column headings are user-facing content: they are the first row of the operator's CSV. A stored
 * heading that is blank, or is only the raw field key (`produkt.product_number`), or the tail of one
 * (`product_number`, `count`), was never written by a person, so the form shows the catalogue's own
 * localized label instead. Anything else is the operator's text and is returned untouched.
 *
 * This is a **display** rule. It never edits a stored definition and a run uses the stored heading;
 * the CSV changes only when the operator saves the form. Without a [catalogueLabel] (the field is
 * gone from the catalogue) the stored heading is kept as it is.
 */
fun defaultHeading(
    fieldKey: String,
    storedHeading: String,
    catalogueLabel: String?,
): String {
    if (catalogueLabel == null) return storedHeading
    val trimmed = storedHeading.trim()
    return if (trimmed.isEmpty() || trimmed in rawKeyForms(fieldKey)) catalogueLabel else storedHeading
}

/** The key itself and each tail of it a default heading could have been cut from. */
private fun rawKeyForms(fieldKey: String): Set<String> =
    setOf(
        fieldKey,
        fieldKey.substringAfterLast('.'),
        fieldKey.substringAfterLast('~'),
        fieldKey.substringAfterLast(':'),
        fieldKey.substringAfterLast('.').substringBefore('~'),
    ).filter { it.isNotEmpty() }.toSet()

/** A stored definition's columns as the draft displays them, using [subject]'s labels. */
fun displayedFields(
    definition: CustomExportDto.DefinitionDto,
    subject: CustomExportDto.SubjectDto?,
): List<DraftField> {
    val labels = subject?.fields.orEmpty().associate { it.key to it.label }
    return definition.fields.map { DraftField(it.fieldKey, defaultHeading(it.fieldKey, it.heading, labels[it.fieldKey])) }
}

/**
 * Whether [draft] differs from what is stored.
 *
 * A new draft is always dirty. For a saved one, the stored headings are put through
 * [defaultHeading] first, so a heading that differs only because the form shows the catalogue label
 * for a raw stored key is **not** a change: the operator has done nothing, and a run (which uses the
 * stored definition) is still what the form describes. Date bounds compare by their date part, as
 * the form only ever shows that.
 */
fun draftDiffersFromStored(
    draft: DefinitionDraft,
    stored: CustomExportDto.DefinitionDto?,
    subject: CustomExportDto.SubjectDto?,
): Boolean {
    if (draft.id == null || stored == null) return true
    return draft.name != stored.name ||
        draft.subjectKey != stored.subjectKey ||
        draft.dateFrom.datePartOrEmpty() != stored.dateFrom.datePartOrEmpty() ||
        draft.dateTo.datePartOrEmpty() != stored.dateTo.datePartOrEmpty() ||
        draft.enabledToggles != stored.enabledToggles.toSet() ||
        draft.fields != displayedFields(stored, subject)
}

private fun String?.datePartOrEmpty(): String = this?.substringBefore('T').orEmpty()
