package org.example.project.database

import org.example.project.testutils.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.Statement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executes the migration carrying the **legacy flat extras over to choice groups** against a real
 * PostgreSQL (zonky embedded PG) and pins what that carry-over produces.
 *
 * ## Why the version is derived, not written down
 *
 * These statements have already moved twice. They were written as `V78__add_choice_groups`; the
 * terminal-wasm merge reused the V78 slot for the fiscal realignment and forward-ported them to
 * `V83__item_extras_group_and_legacy_extras`; the v1.1.0 squash then folded V83 into
 * `V81__schema_catalog_and_staff.sql`, and a later rename pass will move that file again. Each move
 * broke a version literal here: `target = 78` silently ran the *fiscal* migration, and `target = 83`
 * then failed outright with "No migration with a target version 83 could be found".
 *
 * So the target is resolved by **what the script does** — the `DO $$` block that INSERTs into
 * `choice_group` — exactly as `CashBookMigrationRealDataCheck` resolves its slice by matching
 * `CREATE TABLE cash_book`. See [resolveLegacyExtrasMigration]. Renumbering, squashing or
 * re-splitting the carry-over changes nothing in this file.
 *
 * ## Why a test of its own
 *
 * [MigrationChainTest] runs the whole chain and so does execute this migration — but its seed
 * carries no `product_extras_assignment` rows, so the conversion loop never iterates there and
 * proves nothing about what the conversion produces. The fixture below is the point: four products
 * spanning active, inactive and absent extras. The default H2 suite builds its schema from Exposed
 * with `runMigrations = false`, so no migration SQL executes there either.
 *
 * Same approach as [MigrationChainTest] and for the same documented reason: the migrations are
 * incremental `ALTER`s over an Exposed-created base, so a from-empty run fails long before this
 * point. [seedPreCarryOverState] hand-materialises the pre-migration shape of every table the
 * carrying migration touches — which, since the squash, is more than the extras tables alone: the
 * merged file also indexes `item_transfer`, re-points the `employees`/`employee_info` FK and adds a
 * column to `promotion`. Flyway is then driven with the derived baseline and target so **only that
 * one file** executes on top.
 *
 * Every assertion fails if the conversion block is removed. Synthetic seed only — no real data.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Legacy product extras become choice groups (real PostgreSQL)")
class ProductExtrasToChoiceGroupMigrationTest {
    private val migrationLocation = "classpath:db/migration"

    private lateinit var pg: PostgresTestDatabase
    private lateinit var migrate: MigrateResult

    /** The migration under test — resolved from the SQL, see [resolveLegacyExtrasMigration]. */
    private lateinit var slice: LegacyExtrasMigration

    /**
     * The one migration that carries the legacy extras over, plus the version directly below it on
     * disk. Nothing here is a literal, so a renumbering, a squash or a re-split of the carry-over
     * changes these values without touching this file.
     */
    private data class LegacyExtrasMigration(
        val baselineVersion: String,
        val targetVersion: String,
    )

    @BeforeAll
    fun setUpAndMigrate() {
        pg = PostgresTestDatabase.start()
        pg.dataSource.connection.use { seedPreCarryOverState(it) }
        slice = resolveLegacyExtrasMigration()

        migrate =
            Flyway
                .configure()
                .dataSource(pg.dataSource)
                .locations(migrationLocation)
                .baselineOnMigrate(true)
                .baselineVersion(slice.baselineVersion)
                .baselineDescription("pre-choice-group base (hand-seeded)")
                .target(slice.targetVersion)
                .validateMigrationNaming(true)
                .load()
                .migrate()
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    @DisplayName("The carry-over migration genuinely executes (a skipped migration would make every assertion vacuous)")
    fun carryOverMigrationExecutes() {
        assertTrue(migrate.success, "Flyway migrate() must succeed")
        assertTrue(
            migrate.migrations.any { it.version == slice.targetVersion },
            "V${slice.targetVersion} must be EXECUTED, not baselined away; " +
                "executed=${migrate.migrations.map { "V" + it.version }}",
        )
    }

    // ----- the group_id snapshot column ---------------------------------------------------------

    @Test
    @DisplayName("item_extras gains a nullable group_id so a captured option can be re-priced")
    fun itemExtrasHasNullableGroupId() {
        pg.dataSource.connection.use { c ->
            c
                .createStatement()
                .executeQuery(
                    """
                    SELECT data_type, is_nullable
                    FROM information_schema.columns
                    WHERE table_name = 'item_extras' AND column_name = 'group_id'
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next(), "item_extras.group_id must exist")
                    assertEquals("integer", rs.getString("data_type"))
                    assertEquals("YES", rs.getString("is_nullable"), "legacy flat extras have no group")
                }
        }
    }

    // ----- the assignments -> choice group conversion --------------------------------------------

    @Test
    @DisplayName("a product's ACTIVE assigned extras become one optional group at the same prices")
    fun activeAssignmentsBecomeAGroup() {
        pg.dataSource.connection.use { c ->
            val groupId = singleGroupIdFor(c, productId = 1L)

            c
                .createStatement()
                .executeQuery(
                    "SELECT name, min_selections, max_selections, is_active FROM choice_group WHERE id = $groupId",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Extras - Burger", rs.getString("name"))
                    assertEquals(0, rs.getInt("min_selections"), "the old checkbox list was optional")
                    assertEquals(2, rs.getInt("max_selections"), "any subset of the 2 ACTIVE extras")
                    assertTrue(rs.getBoolean("is_active"))
                }

            // Priced from product_extras.price — exactly what resolveLegacyExtras charged, so no
            // line changes price as a result of the migration.
            val options = mutableMapOf<String, String>()
            c
                .createStatement()
                .executeQuery(
                    """
                    SELECT pe.name, cgo.price_delta
                    FROM choice_group_option cgo
                    JOIN product_extras pe ON pe.id = cgo.option_id
                    WHERE cgo.group_id = $groupId
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) options[rs.getString("name")] = rs.getBigDecimal("price_delta").toPlainString()
                }
            assertEquals(mapOf("Cheese" to "1.0000", "Bacon" to "1.5000"), options)
        }
    }

    @Test
    @DisplayName("an INACTIVE assigned extra is not revived as a selectable option")
    fun inactiveExtrasAreNotCarriedOver() {
        pg.dataSource.connection.use { c ->
            val groupId = singleGroupIdFor(c, productId = 1L)
            assertEquals(
                0,
                countQuery(
                    c,
                    """
                    SELECT COUNT(*) FROM choice_group_option cgo
                    JOIN product_extras pe ON pe.id = cgo.option_id
                    WHERE cgo.group_id = $groupId AND NOT pe.is_active
                    """.trimIndent(),
                ),
                "a retired extra must not come back on the order screen",
            )
        }
    }

    @Test
    @DisplayName("a product whose only assigned extra is inactive gets no group at all")
    fun productWithOnlyInactiveExtrasGetsNoGroup() {
        // Also guards chk_choice_group_sel: such a group would need max_selections = 0.
        pg.dataSource.connection.use { c ->
            assertNull(groupIdOrNullFor(c, productId = 3L))
        }
    }

    @Test
    @DisplayName("a product with no assigned extras gets no group")
    fun productWithoutAssignmentsGetsNoGroup() {
        pg.dataSource.connection.use { c ->
            assertNull(groupIdOrNullFor(c, productId = 4L))
        }
    }

    @Test
    @DisplayName("the assignment rows survive — the migration adds, it does not destroy")
    fun assignmentRowsArePreserved() {
        pg.dataSource.connection.use { c ->
            assertEquals(
                4,
                countQuery(c, "SELECT COUNT(*) FROM product_extras_assignment"),
                "the inventory screen still manages these; dropping them would be irreversible",
            )
        }
    }

    // ----- helpers --------------------------------------------------------------------------------

    private fun singleGroupIdFor(
        c: Connection,
        productId: Long,
    ): Int = groupIdOrNullFor(c, productId) ?: error("expected exactly one group for product $productId")

    private fun groupIdOrNullFor(
        c: Connection,
        productId: Long,
    ): Int? {
        val ids = mutableListOf<Int>()
        c
            .createStatement()
            .executeQuery(
                "SELECT group_id FROM product_choice_group WHERE product_id = $productId",
            ).use { rs -> while (rs.next()) ids += rs.getInt(1) }
        assertTrue(ids.size <= 1, "expected at most one group for product $productId, got $ids")
        return ids.firstOrNull()
    }

    private fun countQuery(
        c: Connection,
        sql: String,
    ): Int =
        c.createStatement().executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }

    /**
     * Resolves the migration under test by **what its SQL does**, never by its number: the one
     * script on the classpath whose body carries the legacy-extras carry-over — the `DO $$` block
     * that INSERTs into `choice_group`.
     *
     * The baseline is the version directly below it on disk, so exactly one migration executes and
     * [carryOverMigrationExecutes] can prove it was that one. Same idiom, and the same reason, as
     * `CashBookMigrationRealDataCheck.resolveCashBookMigrationChain`.
     *
     * Fails loudly rather than silently running nothing if the carry-over is ever moved out of
     * `db/migration` or split across two files — either would mean this test has lost its subject.
     */
    private fun resolveLegacyExtrasMigration(): LegacyExtrasMigration {
        val onDisk =
            Flyway
                .configure()
                .dataSource(pg.dataSource)
                .locations(migrationLocation)
                .load()
                .info()
                .all()
                .mapNotNull { info -> info.version?.let { it to info.script } }
                .sortedBy { it.first }
        check(onDisk.isNotEmpty()) { "no versioned migrations on the classpath at $migrationLocation" }

        val matches = onDisk.indices.filter { carriesLegacyExtrasOver(onDisk[it].second) }
        check(matches.size == 1) {
            "expected exactly ONE migration at $migrationLocation to carry the legacy extras over " +
                "to choice groups, found ${matches.map { onDisk[it].second }}. Splitting it across " +
                "files, or removing it, leaves this test with nothing to verify."
        }
        val index = matches.single()
        check(index > 0) {
            "the carry-over migration ${onDisk[index].second} is the LOWEST on disk, so there is no " +
                "version to baseline at below it"
        }
        return LegacyExtrasMigration(
            baselineVersion = onDisk[index - 1].first.version,
            targetVersion = onDisk[index].first.version,
        )
    }

    /**
     * True when [script]'s SQL contains the **per-product** legacy-extras carry-over: an
     * `INSERT INTO choice_group (...)` that names each group `'Extras - ' || <product name>`.
     *
     * Both halves are required. An older, superseded carry-over in the V77 release marker inserts
     * into `choice_group` too, but builds a single shared `'Extras'` / `'Add-ons'` group for every
     * product — matching on the INSERT alone found two migrations and this resolver refused to
     * guess. The per-product naming is precisely the behaviour the assertions below pin
     * (`"Extras - Burger"`), so it is the right discriminator.
     */
    private fun carriesLegacyExtrasOver(script: String): Boolean {
        val sql =
            javaClass.getResourceAsStream("/db/migration/$script")?.use { it.readBytes().decodeToString() }
                ?: error("migration script '$script' is on Flyway's classpath but not readable as a resource")
        return CHOICE_GROUP_INSERT.containsMatchIn(sql) && PER_PRODUCT_GROUP_NAME.containsMatchIn(sql)
    }

    /**
     * The pre-migration shape of exactly the tables the carrying migration reads or alters.
     * Deliberately minimal: only the columns it references, so this seed cannot silently drift with
     * unrelated schema.
     *
     * `choice_group` and its two link tables are created by the V77 release marker, which the
     * derived baseline marks as already applied — so nothing in this run creates them and they are
     * built here.
     *
     * The squash widened what "the carrying migration" touches beyond the extras tables, so the
     * seed grew with it — see [seedCollateralTablesTheMergedFileTouches].
     *
     * Products 1 and 2 carry active assigned extras; product 3 carries only an inactive one;
     * product 4 carries none.
     */
    private fun seedPreCarryOverState(c: Connection) {
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE product (
                    id   BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                );

                CREATE TABLE product_extras (
                    id        SERIAL PRIMARY KEY,
                    name      VARCHAR(255) NOT NULL,
                    price     DECIMAL(19,4) NOT NULL,
                    is_active BOOLEAN NOT NULL DEFAULT true
                );

                CREATE TABLE product_extras_assignment (
                    product_id BIGINT  NOT NULL REFERENCES product(id),
                    extras_id  INTEGER NOT NULL REFERENCES product_extras(id),
                    PRIMARY KEY (product_id, extras_id)
                );

                CREATE TABLE order_item (id BIGSERIAL PRIMARY KEY);

                -- Post-V77 item_extras, in the shape MigrationChainTest seeds. The merged file
                -- adds group_id to it AND sets DB-level defaults on the two snapshot columns, so
                -- those columns must exist and must be created WITHOUT defaults — that is the
                -- Exposed-built shape the SET DEFAULT statements exist to repair, so they do real
                -- work here instead of re-setting a default that was already present.
                CREATE TABLE item_extras (
                    id                   BIGSERIAL PRIMARY KEY,
                    order_item_id        BIGINT  NOT NULL REFERENCES order_item(id),
                    product_extras_id    INTEGER NOT NULL REFERENCES product_extras(id),
                    option_name_snapshot VARCHAR(255)  NOT NULL,
                    price_delta_snapshot DECIMAL(19,4) NOT NULL,
                    group_name_snapshot  VARCHAR(255),
                    quantity             INTEGER NOT NULL DEFAULT 1
                );

                CREATE TABLE choice_group (
                    id             SERIAL PRIMARY KEY,
                    name           VARCHAR(255) NOT NULL,
                    en_name        VARCHAR(255),
                    ch_name        VARCHAR(255),
                    min_selections INTEGER NOT NULL DEFAULT 0,
                    max_selections INTEGER NOT NULL DEFAULT 1,
                    is_active      BOOLEAN NOT NULL DEFAULT true,
                    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
                    -- The real V77 constraint, kept because one of the assertions below exists to
                    -- prove the conversion never produces a group that would violate it.
                    CONSTRAINT chk_choice_group_sel CHECK (
                        min_selections >= 0 AND max_selections >= 1 AND min_selections <= max_selections
                    )
                );

                CREATE TABLE choice_group_option (
                    group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
                    option_id     INTEGER NOT NULL REFERENCES product_extras(id) ON DELETE CASCADE,
                    price_delta   DECIMAL(19,4) NOT NULL DEFAULT 0,
                    sort_position INTEGER NOT NULL DEFAULT 0,
                    is_default    BOOLEAN NOT NULL DEFAULT false,
                    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (group_id, option_id)
                );

                CREATE TABLE product_choice_group (
                    product_id    BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                    group_id      INTEGER NOT NULL REFERENCES choice_group(id) ON DELETE CASCADE,
                    sort_position INTEGER NOT NULL DEFAULT 0,
                    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (product_id, group_id)
                );

                INSERT INTO product (id, name) VALUES
                    (1, 'Burger'), (2, 'Fries'), (3, 'Salad'), (4, 'Water');
                SELECT setval('product_id_seq', 4);

                INSERT INTO product_extras (id, name, price, is_active) VALUES
                    (1, 'Cheese',  1.0000, true),
                    (2, 'Bacon',   1.5000, true),
                    (3, 'Truffle', 9.0000, false),
                    (4, 'Ketchup', 0.5000, true);
                SELECT setval('product_extras_id_seq', 4);

                INSERT INTO product_extras_assignment (product_id, extras_id) VALUES
                    (1, 1),   -- Burger + Cheese  (active)
                    (1, 2),   -- Burger + Bacon   (active)
                    (1, 3),   -- Burger + Truffle (INACTIVE -> must be skipped)
                    (3, 3);   -- Salad  + Truffle (INACTIVE only -> no group at all)
                """.trimIndent(),
            )
            seedCollateralTablesTheMergedFileTouches(st)
        }
    }

    /**
     * Tables the carrying migration touches that have **nothing to do with the extras conversion**.
     *
     * They are needed because the v1.1.0 squash merged the carry-over into a "catalog and staff"
     * group alongside four unrelated sources: an index on `item_transfer.transfer_time`, the
     * `employees` → `employee_info` FK swap with its uniqueness rule, and `promotion.combinable`.
     * Retargeting this test at the merged file therefore means the whole file executes, and a
     * missing precondition would abort it before the conversion ran.
     *
     * All of them are Exposed base-schema tables that no migration creates, so a stub carrying only
     * the columns the migration names is the correct fixture — the same reasoning as
     * [MigrationChainTest]'s seed, from which these shapes are copied. `employees` carries a real
     * FK on `info_id` so the migration's lookup-and-drop has something to find; `promotion` is a
     * bare stub because nothing here inspects it.
     */
    private fun seedCollateralTablesTheMergedFileTouches(st: Statement) {
        st.execute("CREATE TABLE item_transfer (id BIGSERIAL PRIMARY KEY, transfer_time TIMESTAMP NOT NULL)")
        st.execute(
            """
            CREATE TABLE employees (
                employee_id UUID PRIMARY KEY,
                info_id     INTEGER
            )
            """.trimIndent(),
        )
        st.execute(
            """
            CREATE TABLE employee_info (
                employee_info_id SERIAL PRIMARY KEY,
                employee_id      UUID NOT NULL REFERENCES employees(employee_id)
            )
            """.trimIndent(),
        )
        st.execute(
            """
            ALTER TABLE employees
                ADD CONSTRAINT fk_employees_info_id__employee_info_id
                FOREIGN KEY (info_id) REFERENCES employee_info (employee_info_id)
            """.trimIndent(),
        )
        st.execute("CREATE TABLE promotion (id BIGSERIAL PRIMARY KEY)")
    }

    private companion object {
        /**
         * The statement that converts a legacy `product_extras_assignment` into a group. Anchored
         * on the INSERT rather than on `CREATE TABLE choice_group`, because the table itself is
         * created by an older migration this test deliberately baselines away.
         */
        private val CHOICE_GROUP_INSERT =
            Regex("""insert\s+into\s+"?choice_group"?\s*\(""", RegexOption.IGNORE_CASE)

        /**
         * The per-product group name the carry-over builds (`'Extras - ' || r.product_name`). This
         * is what separates the migration under test from the superseded shared-group carry-over
         * that the V77 release marker still carries.
         */
        private val PER_PRODUCT_GROUP_NAME = Regex("""'Extras - '""")
    }
}
