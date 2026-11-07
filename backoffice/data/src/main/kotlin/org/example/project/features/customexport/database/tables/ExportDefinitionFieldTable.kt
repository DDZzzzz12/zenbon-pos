package org.example.project.features.customexport.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * One chosen column of a saved export, in the order it appears in the CSV.
 *
 * A child table rather than a serialized list on the parent, because [position] and [heading] are
 * per-field state an operator edits one at a time, and because reordering a list encoded in a text
 * column is exactly the kind of write that goes wrong halfway.
 *
 * ## Why [fieldKey] is a string and not a foreign key
 *
 * Same reason as `ExportDefinitionTable.subjectKey`: the field catalogue is Kotlin, so the database
 * has nothing to reference. A key that no longer resolves makes the whole definition **broken** — the
 * operator is told, and fixes or deletes it. It is never run with the surviving columns only: a CSV
 * that is quietly one column short is worse than no CSV.
 */
object ExportDefinitionFieldTable : LongIdTable("export_definition_field", "id") {
    /** CASCADE: a field selection has no meaning without the definition that owns it. */
    val definitionId = reference("definition_id", ExportDefinitionTable, onDelete = ReferenceOption.CASCADE)

    /** `ExportField.key`. A catalogue key, not a foreign key — see the class KDoc. */
    val fieldKey = varchar("field_key", 100)

    /** Column order in the output. Reads must `orderBy` this — insertion order proves nothing. */
    val position = integer("position")

    /** The operator's heading for this column, which is why the field's catalogue label is not enough. */
    val heading = varchar("heading", 100)

    init {
        index("idx_export_definition_field_definition", false, definitionId)
    }
}
