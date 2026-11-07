package org.example.project.features.customexport

import org.example.project.database.SchemaManager
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema graph finds a table's *incoming* foreign keys by scanning every table, so it needs the
 * same list `SchemaManager` creates from. One list, not two: a second copy would drift, and a table
 * missing from the graph's copy is a relation that silently never appears.
 */
@DisplayName("All tables visibility")
class AllTablesVisibilityTest {
    @Test
    fun `the table list is readable and holds the tables the graph walks`() {
        val tables = SchemaManager.allTables
        assertTrue(tables.isNotEmpty(), "The table list is empty")
        assertTrue(BusinessPartnerTable in tables)
        assertTrue(SupplierTable in tables)
    }

    @Test
    fun `no table is listed twice`() {
        val names = SchemaManager.allTables.map { it.tableName }
        assertEquals(names.size, names.toSet().size, "Duplicate table in the list: $names")
    }

    /** The list `getTableNames()` reports is the same one, not a parallel copy. */
    @Test
    fun `the public name list agrees with the table list`() {
        assertEquals(SchemaManager.allTables.map { it.tableName }, SchemaManager.getTableNames())
    }
}
