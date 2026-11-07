package org.example.project.testutils.fakes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.cache.TableStatusCacheWriter
import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase

/**
 * A [TableStatusCacheWriter] backed by an empty in-memory cache. Since the writer is a
 * no-op when the target table is not cached, this is safe to use in VM tests that do not
 * exercise the cache write-through path.
 */
fun noOpTableStatusCacheWriter(): TableStatusCacheWriter {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    PosDatabase.Schema.create(driver)
    return TableStatusCacheWriter(
        RTableDataManager(
            PosDatabase(
                driver,
                local_choice_group_optionsAdapter =
                    Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
                local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
                local_product_extrasAdapter =
                    Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
            ),
        ),
        CacheChangeBus(),
    )
}
