package org.example.project.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.money.Decimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins `7.sqm`, which moves `local_products.price` from INTEGER cents to exact decimal TEXT.
 *
 * A `.sqm` is one-way: a bug here corrupts every cached price on every device rather than merely
 * displaying one wrongly. So this does not assert the arithmetic in Kotlin — it builds a real
 * version-7 SQLite database, seeds it with cent values, runs the migration through the generated
 * [PosDatabase.Schema] (the same code path the app uses), and reads the result back.
 *
 * It deliberately hand-writes the version-7 DDL instead of calling `Schema.create`. `create`
 * always builds the *current* schema, so a create-then-migrate test would start passing for the
 * wrong reason — and then break — the moment `LocalProducts.sq` is edited.
 */
class ProductPriceMigrationTest {
    private lateinit var driver: SqlDriver

    /**
     * `local_products` and its FK target exactly as they stood at schema version 7. Verified
     * against the SQLDelight-generated `7.db`, not transcribed from the plan: the plan's draft
     * had `product_number` nullable and omitted the FOREIGN KEY.
     */
    private val schemaV7 =
        listOf(
            """
            CREATE TABLE local_product_categories (
                id INTEGER NOT NULL PRIMARY KEY,
                name TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE local_products (
                id INTEGER NOT NULL PRIMARY KEY,
                product_number TEXT NOT NULL,
                name TEXT NOT NULL,
                en_name TEXT,
                ch_name TEXT,
                description TEXT,
                price INTEGER NOT NULL,
                product_type TEXT NOT NULL,
                category_id INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                allow_custom_price INTEGER NOT NULL DEFAULT 0,
                image_url TEXT,
                FOREIGN KEY (category_id) REFERENCES local_product_categories (id)
            )
            """.trimIndent(),
            "CREATE INDEX idx_local_products_category_id ON local_products(category_id)",
            "CREATE INDEX idx_local_products_is_active ON local_products(is_active)",
            "CREATE INDEX idx_local_products_product_number ON local_products(product_number)",
            "CREATE INDEX idx_local_products_name ON local_products(name)",
        )

    /**
     * Cent values chosen to cover every branch of the conversion: a normal price, a sub-10c
     * value needing the leading zero, zero itself, a whole euro, a large value, and negatives.
     *
     * `price` is non-negative today, but the same expression is the template for later slices
     * where `price_delta` IS signed, and SQLite truncates integer division toward zero: the
     * shorter form without an explicit sign yields "0.99" for -99 — valid-looking, sign lost.
     */
    private val cases =
        listOf(
            Triple(1L, 1690L, "16.90"),
            Triple(2L, 5L, "0.05"),
            Triple(3L, 0L, "0.00"),
            Triple(4L, 100L, "1.00"),
            Triple(5L, 999999L, "9999.99"),
            Triple(6L, 99L, "0.99"),
            Triple(7L, 1L, "0.01"),
            Triple(8L, -5L, "-0.05"),
            Triple(9L, -99L, "-0.99"),
            Triple(10L, -100L, "-1.00"),
            Triple(11L, -1L, "-0.01"),
            Triple(12L, -250L, "-2.50"),
            Triple(13L, -999999L, "-9999.99"),
        )

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        schemaV7.forEach { driver.execute(null, it, 0) }
        driver.execute(null, "INSERT INTO local_product_categories(id, name) VALUES (7, 'Drinks')", 0)
        cases.forEach { (id, cents, _) ->
            driver.execute(
                null,
                """
                INSERT INTO local_products(
                    id, product_number, name, en_name, ch_name, description, price,
                    product_type, category_id, is_active, allow_custom_price, image_url
                ) VALUES (
                    $id, 'P-$id', 'Product $id', 'EN $id', NULL, NULL, $cents,
                    'FOOD', 7, 1, 0, NULL
                )
                """.trimIndent(),
                0,
            )
        }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun migrate() {
        PosDatabase.Schema.migrate(driver, oldVersion = 7L, newVersion = 8L).value
    }

    private fun rows(
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        driver
            .executeQuery(
                identifier = null,
                sql = sql,
                mapper = { cursor ->
                    val acc = mutableListOf<List<String?>>()
                    while (cursor.next().value) {
                        acc.add((0 until columns).map { cursor.getString(it) })
                    }
                    QueryResult.Value(acc)
                },
                parameters = 0,
            ).value

    @Test
    fun schemaVersionIsAtLeastWhatTheseFixturesNeed() {
        // The intent, from the original comment: "if this reads LESS than 10, a migration file is
        // missing entirely and every other assertion below would be vacuous." That is a floor, so
        // assertEquals was the wrong operator -- it also failed when a LATER migration was added,
        // which says nothing about these fixtures. 10.sqm did exactly that and left three tests
        // red until the suite was next compiled.
        //
        // 7.sqm migrates 7 -> 8, 8.sqm 8 -> 9, 9.sqm 9 -> 10, so these fixtures need at least 10.
        assertTrue(
            PosDatabase.Schema.version >= 10L,
            "a migration file is missing; every assertion below would be vacuous " +
                "(schema version ${PosDatabase.Schema.version})",
        )
    }

    @Test
    fun convertsCentsToExactDecimalText() {
        migrate()
        val actual =
            rows("SELECT id, price FROM local_products ORDER BY id", 2)
                .associate { it[0] to it[1] }
        cases.forEach { (id, cents, expected) ->
            assertEquals(expected, actual[id.toString()], "cents $cents converted wrongly")
        }
    }

    @Test
    fun storedTextRoundTripsThroughDecimal() {
        migrate()
        // The stored form must be readable by DecimalColumnAdapter, which throws on malformed
        // input rather than substituting a placeholder.
        rows("SELECT id, price FROM local_products ORDER BY id", 2).forEach { (id, price) ->
            val parsed = Decimal.parse(price!!)
            assertEquals(price, parsed.toPlainString(), "price of row $id is not canonical")
            // A bare integer would also parse, so pin the decimal form explicitly.
            assertEquals(2, price.substringAfter('.', "").length, "price of row $id lost its scale: $price")
        }
    }

    @Test
    fun preservesEveryOtherColumnUnchanged() {
        migrate()
        val row =
            rows(
                """
                SELECT id, product_number, name, en_name, ch_name, description,
                       product_type, category_id, is_active, allow_custom_price, image_url
                FROM local_products WHERE id = 1
                """.trimIndent(),
                11,
            ).single()
        assertEquals(
            listOf("1", "P-1", "Product 1", "EN 1", null, null, "FOOD", "7", "1", "0", null),
            row,
        )
    }

    @Test
    fun keepsTheFullColumnListWithOnlyPriceRetyped() {
        migrate()
        val info = rows("PRAGMA table_info(local_products)", 6)
        // name, declared type, notnull, default
        val actual = info.map { listOf(it[1], it[2], it[3], it[4]) }
        assertEquals(
            listOf(
                listOf("id", "INTEGER", "1", null),
                listOf("product_number", "TEXT", "1", null),
                listOf("name", "TEXT", "1", null),
                listOf("en_name", "TEXT", "0", null),
                listOf("ch_name", "TEXT", "0", null),
                listOf("description", "TEXT", "0", null),
                listOf("price", "TEXT", "1", null),
                listOf("product_type", "TEXT", "1", null),
                listOf("category_id", "INTEGER", "1", null),
                listOf("is_active", "INTEGER", "1", "1"),
                // allow_custom_price is the other INTEGER column with "price" in its name.
                // It is a boolean flag and must NOT be retyped.
                listOf("allow_custom_price", "INTEGER", "1", "0"),
                listOf("image_url", "TEXT", "0", null),
            ),
            actual,
        )
    }

    @Test
    fun keepsTheForeignKey() {
        migrate()
        val fks = rows("PRAGMA foreign_key_list(local_products)", 5)
        assertEquals(1, fks.size, "the FOREIGN KEY to local_product_categories was dropped")
        assertEquals("local_product_categories", fks.single()[2])
        assertEquals("category_id", fks.single()[3])
        assertEquals("id", fks.single()[4])
    }

    @Test
    fun recreatesAllFourIndexes() {
        migrate()
        val names =
            rows(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='local_products' ORDER BY name",
                1,
            ).map { it[0] }
        assertEquals(
            listOf(
                "idx_local_products_category_id",
                "idx_local_products_is_active",
                "idx_local_products_name",
                "idx_local_products_product_number",
            ),
            names,
        )
    }

    @Test
    fun dropsTheScratchTable() {
        migrate()
        val leftovers =
            rows(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'local_products%'",
                1,
            ).map { it[0] }
        assertEquals(listOf("local_products"), leftovers)
    }

    @Test
    fun survivesForeignKeyEnforcement() {
        // Neither driver enables foreign_keys today, but the table is recreated, so the
        // migration must not depend on enforcement being off.
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        migrate()
        val violations = rows("PRAGMA foreign_key_check", 4)
        assertTrue(violations.isEmpty(), "migration left dangling foreign keys: $violations")
        assertEquals(cases.size.toString(), rows("SELECT count(*) FROM local_products", 1).single()[0])
    }
}
