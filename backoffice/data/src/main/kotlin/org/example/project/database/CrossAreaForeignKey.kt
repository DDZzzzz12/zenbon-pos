package org.example.project.database

/**
 * A foreign key between two areas of the suite, declared by the host instead of by the Exposed table
 * that holds the column.
 *
 * A table in a lower area (ecr) may not import a table of a higher one (fiscal, accounting), so such a
 * column is a plain id column in its table object and the constraint lives here, in the one module that
 * sees both tables. A fresh install builds its schema from the Exposed objects, so this declaration must
 * produce exactly the constraint Exposed generated while the column was still a `reference` — same
 * name, same rules. `FreshInstallForeignKeySnapshotTest` and its PostgreSQL twin are the authority.
 *
 * @property name the constraint name, as Exposed generated it
 * @property table the referencing (child) table
 * @property column the referencing column of [table]
 * @property refTable the referenced (parent) table
 * @property refColumn the referenced column of [refTable]
 * @property onDelete the `ON DELETE` rule as an Exposed `ReferenceOption` name, or null where the
 *   original `reference` named none (Exposed then emits the dialect's default, as it always did)
 */
data class CrossAreaForeignKey(
    val name: String,
    val table: String,
    val column: String,
    val refTable: String,
    val refColumn: String,
    val onDelete: String?,
)
