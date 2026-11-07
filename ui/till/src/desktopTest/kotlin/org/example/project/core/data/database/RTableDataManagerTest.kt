package org.example.project.core.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.toRTable
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class RTableDataManagerTest {
    private fun db(): PosDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PosDatabase.Schema.create(driver)
        return PosDatabase(
            driver,
            local_choice_group_optionsAdapter =
                Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
            local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
            local_product_extrasAdapter =
                Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
        )
    }

    private fun table(
        id: Long,
        loc: Long,
        status: String = "FREE",
    ) = RTable(
        id = id,
        tableNumber = "$id",
        seats = 4,
        locationTypeId = loc,
        locationTypeName = "Indoor",
        tableStatus = status,
        currentSessionId = null,
        gridX = 0,
        gridY = 0,
        rotation = 0f,
    )

    @Test
    fun `upsert then select by location`() {
        val m = RTableDataManager(db())
        m.upsertAll(listOf(table(1, loc = 1), table(2, loc = 1), table(3, loc = 2)))
        val loc1 = m.selectByLocationType(1).map { it.toRTable() }
        assertEquals(listOf(1L, 2L), loc1.map { it.id })
    }

    @Test
    fun `upsert replaces existing row`() {
        val m = RTableDataManager(db())
        m.upsertAll(listOf(table(1, loc = 1, status = "FREE")))
        m.upsertAll(listOf(table(1, loc = 1, status = "OCCUPIED")))
        assertEquals("OCCUPIED", m.selectByLocationType(1).single().table_status)
    }

    @Test
    fun `deleteById removes the row`() {
        val m = RTableDataManager(db())
        m.upsertAll(listOf(table(1, loc = 1), table(2, loc = 1)))
        m.deleteById(1)
        assertEquals(listOf(2L), m.selectByLocationType(1).map { it.id })
    }

    @Test
    fun `replaceByLocationType swaps only that location's rows`() {
        val m = RTableDataManager(db())
        m.upsertAll(listOf(table(1, loc = 1), table(2, loc = 1), table(9, loc = 2)))
        m.replaceByLocationType(1, listOf(table(3, loc = 1)))
        assertEquals(listOf(3L), m.selectByLocationType(1).map { it.id })
        assertEquals(listOf(9L), m.selectByLocationType(2).map { it.id }) // untouched
    }

    @Test
    fun `replaceAll wipes everything and inserts the new set`() {
        val m = RTableDataManager(db())
        m.upsertAll(listOf(table(1, loc = 1), table(9, loc = 2)))
        m.replaceAll(listOf(table(5, loc = 3)))
        assertEquals(emptyList<Long>(), m.selectByLocationType(1).map { it.id })
        assertEquals(emptyList<Long>(), m.selectByLocationType(2).map { it.id })
        assertEquals(listOf(5L), m.selectByLocationType(3).map { it.id })
    }
}
