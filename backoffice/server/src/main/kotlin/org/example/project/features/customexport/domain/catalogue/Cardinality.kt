package org.example.project.features.customexport.domain.catalogue

/**
 * How many rows of the far table one row of the near table can reach.
 *
 * Derived from the schema, never declared. [MANY] is discovered and then refused: joining it would
 * multiply the export's rows, duplicating every other column, with nothing in the file to say so.
 * To-many belongs to a later slice as a correlated subquery, which leaves the row count alone.
 */
enum class Cardinality {
    ONE,
    MANY,
}
