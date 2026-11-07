package org.example.project.core.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.models.RTable
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableStatusCacheWriterTest {
    private fun db(): PosDatabase {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PosDatabase.Schema.create(d)
        return PosDatabase(
            d,
            local_choice_group_optionsAdapter =
                Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
            local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
            local_product_extrasAdapter =
                Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
        )
    }

    @Test
    fun `applyStatusUpdate updates volatile fields and preserves layout`() =
        runTest {
            val mgr = RTableDataManager(db())
            mgr.upsertAll(
                listOf(
                    RTable(
                        id = 1,
                        tableNumber = "T-1",
                        seats = 6,
                        locationTypeId = 1,
                        locationTypeName = "Indoor",
                        tableStatus = "FREE",
                        currentSessionId = null,
                        gridX = 3,
                        gridY = 4,
                        rotation = 90f,
                        gridWidth = 2,
                        gridHeight = 2,
                        isActive = true,
                    ),
                ),
            )
            val writer = TableStatusCacheWriter(mgr, CacheChangeBus())

            writer.applyStatusUpdate(
                tableId = 1,
                status = "OCCUPIED",
                employeeNumber = "07",
                sessionId = 9,
                isTraining = false,
                openAmount = "12.00",
            )

            val row = mgr.selectById(1)!!
            assertEquals("OCCUPIED", row.table_status)
            assertEquals("07", row.current_employee_number)
            assertEquals(9L, row.current_session_id)
            assertEquals("12.00", row.open_amount)
            assertEquals(6L, row.seats)
            assertEquals(3L, row.grid_x)
            assertEquals(90.0, row.rotation)
        }

    @Test
    fun `applyStatusUpdate is a no-op when the table is not cached`() =
        runTest {
            val mgr = RTableDataManager(db())
            TableStatusCacheWriter(mgr, CacheChangeBus())
                .applyStatusUpdate(999, "OCCUPIED", null, null, false, null)
            assertNull(mgr.selectById(999))
        }
}
