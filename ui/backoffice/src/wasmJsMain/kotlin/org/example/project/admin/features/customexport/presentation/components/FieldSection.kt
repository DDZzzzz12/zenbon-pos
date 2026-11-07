package org.example.project.admin.features.customexport.presentation.components

import org.example.project.core.domain.dto.customexport.CustomExportDto

/**
 * One block of the available-fields list: the columns of one area, under one heading.
 *
 * @property heading the server's German label for the related table, or null for the block holding
 *   the subject's own columns. Null rather than a translated "Stammdaten" heading because the root
 *   block needs no name: it is what the operator picked the subject for, and labelling it would put
 *   a heading on the obvious while the sections that actually carry information sit below it.
 */
data class FieldSection(
    val heading: String?,
    val fields: List<CustomExportDto.FieldDto>,
)
