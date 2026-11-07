package org.example.project.admin.features.customexport.presentation.components

import org.example.project.core.domain.dto.customexport.CustomExportDto

/**
 * Splits a subject's offered columns into the picker's sections.
 *
 * Root fields first with no header, then one section per group, in first-appearance order.
 *
 * **Order is the server's, and is not re-sorted.** The catalogue emits fields in the schema graph's
 * deterministic traversal order — the nearest relation first — and sorting the headings
 * alphabetically here would throw that away for a shape that means nothing: "Bankverbindung" before
 * "Kreditor-Rolle" says only that B precedes K.
 *
 * A group whose fields are not adjacent in the input still yields exactly one section. The server now
 * guarantees that two routes to one table carry two different headings — `ExportRelationLabels` names
 * the foreign keys, so the Kassenbuch subject shows "Kasse" and "Gegenkasse (Geldtransit)" rather than
 * two blocks both headed "Kasse" — so this merge no longer papers over an ambiguity. It stays because
 * one heading must still mean one section whatever order the fields arrive in.
 *
 * Pure and free of Compose on purpose: the ordering is the part that has to be right, and the wasm
 * test leg can assert on a list where it cannot assert on a canvas.
 */
fun groupFields(fields: List<CustomExportDto.FieldDto>): List<FieldSection> {
    val rootFields = fields.filter { it.group == null }
    val grouped = fields.filter { it.group != null }.groupBy { it.group }

    val rootSection = if (rootFields.isEmpty()) emptyList() else listOf(FieldSection(null, rootFields))
    return rootSection + grouped.map { (group, groupFields) -> FieldSection(group, groupFields) }
}
