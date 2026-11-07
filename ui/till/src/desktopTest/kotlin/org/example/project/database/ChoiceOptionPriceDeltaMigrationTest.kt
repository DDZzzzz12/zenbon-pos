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

class ChoiceOptionPriceDeltaMigrationTest {
    private lateinit var driver: SqlDriver

    private val schemaV8 =
        listOf(
            """
            CREATE TABLE local_choice_group_options (
                group_id INTEGER NOT NULL,
                option_id INTEGER NOT NULL,
                price_delta INTEGER NOT NULL,
                sort_position INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (group_id, option_id)
            )
            """.trimIndent(),
        )

    private val cases =
        listOf(
            Triple(1L, 250L, "2.50"),
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
        schemaV8.forEach { driver.execute(null, it, 0) }
        cases.forEach { (optionId, cents, _) ->
            driver.execute(
                null,
                "INSERT INTO local_choice_group_options(group_id, option_id, price_delta, " +
                    "sort_position, is_default) VALUES (1, $optionId, $cents, ${optionId.toInt()}, 0)",
                0,
            )
        }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun migrate() {
        PosDatabase.Schema.migrate(driver, oldVersion = 8L, newVersion = 9L).value
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
    fun convertsSignedCentsToExactDecimalText() {
        migrate()
        val actual =
            rows(
                "SELECT option_id, price_delta FROM local_choice_group_options ORDER BY option_id",
                2,
            ).associate { it[0] to it[1] }
        cases.forEach { (optionId, cents, expected) ->
            assertEquals(expected, actual[optionId.toString()], "cents $cents converted wrongly")
        }
    }

    @Test
    fun storedTextRoundTripsThroughDecimalWithSign() {
        migrate()
        rows("SELECT option_id, price_delta FROM local_choice_group_options ORDER BY option_id", 2)
            .forEach { (optionId, delta) ->
                val parsed = Decimal.parse(delta!!)
                assertEquals(delta, parsed.toPlainString(), "delta of option $optionId not canonical")
                assertEquals(
                    2,
                    delta.substringAfter('.', "").length,
                    "delta of option $optionId lost its scale: $delta",
                )
            }
    }

    @Test
    fun keepsTheFullColumnListWithOnlyPriceDeltaRetyped() {
        migrate()
        val info = rows("PRAGMA table_info(local_choice_group_options)", 6)
        val actual = info.map { listOf(it[1], it[2], it[3], it[4], it[5]) }
        assertEquals(
            listOf(
                listOf("group_id", "INTEGER", "1", null, "1"),
                listOf("option_id", "INTEGER", "1", null, "2"),
                listOf("price_delta", "TEXT", "1", null, "0"),
                listOf("sort_position", "INTEGER", "1", "0", "0"),
                listOf("is_default", "INTEGER", "1", "0", "0"),
            ),
            actual,
        )
    }

    @Test
    fun dropsTheScratchTable() {
        migrate()
        val leftovers =
            rows(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'local_choice_group_options%'",
                1,
            ).map { it[0] }
        assertEquals(listOf("local_choice_group_options"), leftovers)
    }

    @Test
    fun preservesEveryRow() {
        migrate()
        assertEquals(
            cases.size.toString(),
            rows("SELECT count(*) FROM local_choice_group_options", 1).single()[0],
        )
    }
}
