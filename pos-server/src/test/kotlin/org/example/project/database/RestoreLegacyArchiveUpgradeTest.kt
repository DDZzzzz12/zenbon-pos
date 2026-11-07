package org.example.project.database

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.project.BuildConfig
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.backup.data.service.RestoreStaging
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.zBon.domain.KassenStammdatenSnapshot
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Restoring a v1.0.10 archive onto this version must leave the data exactly as an in-place upgrade
 * would. Restored straight into the current schema it did not: none of V77..Vmax's data steps ran,
 * so the first new Z-Bon claimed the whole order history (QA FAIL-5), DSFinV-K counted the legacy
 * closing's Kassenbuch aggregate as a second turnover (FAIL-6), and the stored TSE Admin PIN, PUK and
 * credential seed survived.
 *
 * The archive here is built the way v1.0.10 builds one, from a database created out of the frozen
 * v1.0.10 schema, with synthetic rows only. The assertions are on the three data steps; each would
 * fail on the direct load.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("A v1.0.10 archive is migrated like an upgrade before it is restored — real PostgreSQL")
class RestoreLegacyArchiveUpgradeTest {
    private lateinit var pg: PostgresTestDatabase
    private val thisInstall: File = Files.createTempDirectory("legacy-restore-appdata").toFile()

    private val configManager =
        object : IShopConfigurationManager by ShopConfigurationManager {
            override suspend fun getDocumentStoragePath(): String = File(thisInstall, "documents").absolutePath

            override suspend fun getDefaultBackupPath(): String? = File(thisInstall, "backups").absolutePath

            override suspend fun refreshConfiguration(): Boolean = true

            override suspend fun getRestoredPathsPendingReview(): Map<RestoredPathSetting, String> = emptyMap()
        }

    private val service by lazy {
        BackupRestoreServiceImpl(
            ConfigurationRepository(),
            configManager,
            org.example.project.database
                .LiveDataSourceProvider {
                    org.example.project.database.DatabaseFactory
                        .getDataSource()
                },
            { thisInstall.absolutePath },
            postRestoreHooks =
                listOf(
                    org.example.project.features.zBon.domain
                        .KassenSwVersionPostRestoreHook(ConfigurationRepository()),
                ),
        ) {
            error("BuiltInJobsSeeder is never used by the restore path")
        }
    }

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        initDB(pg.hikariConfig(), runMigrations = true)
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) {
            DatabaseFactory.close()
            pg.close()
        }
        thisInstall.deleteRecursively()
    }

    @Test
    @DisplayName("orders get their closing, the Z-Bon aggregate is flagged and the TSE admin secrets are gone")
    fun legacyArchiveIsUpgradedBeforeTheLoad() {
        val archive = legacyArchive(source = "legacy_ok") { conn -> seedShop(conn) }

        runBlocking { service.restoreBackup(archive) }

        live { conn ->
            assertEquals(7L, conn.long("SELECT zbon_id FROM t_order WHERE order_number = 'O-1'"), "V79 links the covered order")
            assertNull(
                conn.longOrNull("SELECT zbon_id FROM t_order WHERE order_number = 'O-2'"),
                "an order after the last closing stays open for the next one, as on an upgrade",
            )
            assertTrue(
                conn.bool("SELECT is_zbon_aggregate FROM kassenbuch_header WHERE belegtext = 'ZBon Nr. 7'"),
                "V77 flags the closing's own Kassenbuch aggregate, so DSFinV-K does not count it twice",
            )
            val tseConfig = conn.text("SELECT value::text FROM shop_configuration WHERE key = 'tse_config'")
            listOf("adminPin", "adminPuk", "credentialSeed").forEach {
                assertFalse(tseConfig.contains(it), "V79 strips $it from the restored TSE configuration")
            }
            assertTrue(tseConfig.contains("timeAdminPin"), "the rest of the TSE configuration survives")
        }
        assertEquals(emptyList(), stagingDatabases(), "the staging database is dropped")
    }

    @Test
    @DisplayName("the archive's Kassenmeldung software version is replaced by the running one — QA emu21 FAIL-D")
    fun restoredSoftwareVersionIsTheRunningOne() {
        val archive =
            legacyArchive(source = "legacy_swversion") { conn ->
                seedShop(conn)
                conn.createStatement().execute(
                    """
                    INSERT INTO shop_configuration (shop_id, key, value)
                    VALUES (1, 'kassen_meldung_config',
                            '{"kasseSeriennr":"SN-1","kasseBrand":"Zenbon","kasseModell":"Desktop/Server POS","kasseSwBrand":"Zenbon","kasseSwVersion":"1.0.10"}');
                    """.trimIndent(),
                )
            }
        check(BuildConfig.APP_VERSION != "1.0.10") { "the test needs a running version other than the archive's" }

        runBlocking { service.restoreBackup(archive) }

        val config =
            runBlocking { dbQuery { ConfigurationRepository().getSettingAsString(1L, ConfigKey.KASSEN_MELDUNG_CONFIG.key) } }!!
                .let { Json.decodeFromString<ShopConfigurationDto.KassenMeldungConfigDto>(it) }
        assertEquals(BuildConfig.APP_VERSION, config.kasseSwVersion, "the live Kassenmeldung names the running build")
        assertEquals("SN-1", config.kasseSeriennr, "the rest of the Kassenmeldung is the archive's")

        live { conn ->
            assertEquals(
                "1.0.10",
                conn.text("SELECT stamm_kasse_sw_version FROM zbon WHERE zbon_id = 7"),
                "the restored closing keeps exporting the version it was taken under",
            )
            conn.createStatement().execute(
                """
                INSERT INTO zbon (zbon_id, shop_info_id, employee_id, start_time, end_time, cancelled_amount,
                                  total_pos_revenue, opening_balance, expenses, cash_revenue, closing_balance, created_at, zbon_nummer)
                VALUES (8, 1, '11111111-1111-1111-1111-111111111111', '2026-09-02 08:00', '2026-09-02 22:00',
                        0, 5, 10, 0, 5, 15, '2026-09-02 22:00', 8);
                """.trimIndent(),
            )
        }
        // What ZBonRepositoryImpl.createReport does for the first closing after the restore.
        transaction(DatabaseFactory.getDatabase()) { KassenStammdatenSnapshot.applyTo(ZBon[8L], 1L) }

        live { conn ->
            assertEquals(
                BuildConfig.APP_VERSION,
                conn.text("SELECT stamm_kasse_sw_version FROM zbon WHERE zbon_id = 8"),
                "the first closing after the restore freezes the software that booked it",
            )
        }
    }

    @Test
    @DisplayName("an archive a migration refuses is not restored, and the live data is untouched")
    fun refusedArchiveLeavesTheLiveDatabaseAlone() {
        live { conn -> conn.createStatement().execute("CREATE TABLE IF NOT EXISTS restore_sentinel (v int); INSERT INTO restore_sentinel VALUES (1)") }
        val sentinelBefore = live { it.long("SELECT count(*) FROM restore_sentinel") }
        val employeesBefore = live { it.long("SELECT count(*) FROM employees") }
        // V85 refuses a legacy EXCEPTION_ grant held by a non-admin, on an upgrade and so on a restore.
        val archive =
            legacyArchive(source = "legacy_refused") { conn ->
                seedShop(conn)
                conn.createStatement().execute(
                    """
                    INSERT INTO employees (employee_id, username, password_hash, employee_number, role)
                    VALUES ('22222222-2222-2222-2222-222222222222', 'cashier', 'x', 'K001', 'MANAGER');
                    INSERT INTO permission (id, code, name) VALUES (1, 'EXCEPTION_VOID', 'Storno');
                    INSERT INTO employee_permissions (employee_id, permission_id)
                    VALUES ('22222222-2222-2222-2222-222222222222', 1);
                    """.trimIndent(),
                )
            }

        val error = assertFailsWith<PosException> { runBlocking { service.restoreBackup(archive) } }

        assertTrue(error.message!!.contains("could not be upgraded"), error.message)
        assertTrue(error.message!!.contains("V110"), "the migration's own reason reaches the operator: ${error.message}")
        assertEquals(sentinelBefore, live { it.long("SELECT count(*) FROM restore_sentinel") })
        assertEquals(employeesBefore, live { it.long("SELECT count(*) FROM employees") })
        assertEquals(emptyList(), stagingDatabases(), "the staging database is dropped on failure too")
    }

    @Test
    @DisplayName("only archives at or below the frozen base version are staged")
    fun stagingIsDecidedByTheArchiveVersion() {
        assertTrue(RestoreStaging.isNeeded("76"))
        assertTrue(RestoreStaging.isNeeded("70"))
        assertFalse(RestoreStaging.isNeeded("77"))
        assertFalse(RestoreStaging.isNeeded("90"))
        assertFalse(RestoreStaging.isNeeded("unknown"))
        assertFalse(RestoreStaging.isNeeded(null))
        assertEquals(
            "jdbc:postgresql://localhost:5432/other?user=postgres",
            RestoreStaging.withDatabase("jdbc:postgresql://localhost:5432/pos_db?user=postgres", "other"),
        )
    }

    // ------------------------------------------------------------------------------------------

    /** A coherent v1.0.10 shop: one closing, one order inside it, one after it, the closing's aggregate. */
    private fun seedShop(conn: Connection) {
        conn.createStatement().execute(
            """
            INSERT INTO location_types (id, name, english_name, chinese_name, created_at, updated_at)
            VALUES (1, 'Innen', 'Indoor', 'Innen', now(), now());
            INSERT INTO shop_info (shop_id, shop_name, shop_address, shop_city, zip_code, shop_country, shop_phone, create_at)
            VALUES (1, 'Testladen', 'Teststr. 1', 'Teststadt', 12345, 'DE', '000', now());
            INSERT INTO employees (employee_id, username, password_hash, employee_number, role)
            VALUES ('11111111-1111-1111-1111-111111111111', 'owner', 'x', 'A001', 'ADMIN');
            INSERT INTO r_table (table_id, table_number, location_type_id, created_at) VALUES (1, '1', 1, now());
            INSERT INTO table_session (table_session_id, table_id, employee_id)
            VALUES (1, 1, '11111111-1111-1111-1111-111111111111');
            INSERT INTO zbon (zbon_id, shop_info_id, employee_id, start_time, end_time, cancelled_amount,
                              total_pos_revenue, opening_balance, expenses, cash_revenue, closing_balance, created_at)
            VALUES (7, 1, '11111111-1111-1111-1111-111111111111', '2026-09-01 08:00', '2026-09-01 22:00',
                    0, 10, 0, 0, 10, 10, '2026-09-01 22:00');
            INSERT INTO t_order (id, order_number, employee_id, table_session_id, order_status, order_time, order_total)
            VALUES (1, 'O-1', '11111111-1111-1111-1111-111111111111', 1, 'COMPLETED', '2026-09-01 12:00', 10),
                   (2, 'O-2', '11111111-1111-1111-1111-111111111111', 1, 'COMPLETED', '2026-09-02 10:00', 5);
            INSERT INTO kassenbuch_header (id, shop_id, employee_id, entry_type, beleg_nr, beleg_datum, belegtext,
                                           total_amount, current_balance, zbon_id)
            VALUES (1, 1, '11111111-1111-1111-1111-111111111111', 'CASH_SALE', '1', '01.09.2026', 'ZBon Nr. 7', 10, 10, 7);
            INSERT INTO shop_configuration (shop_id, key, value)
            VALUES (1, 'tse_config', '{"mode":"NORMAL","adminPin":"123456","adminPuk":"1234567890","credentialSeed":"seed","timeAdminPin":"12345678"}');
            """.trimIndent(),
        )
    }

    /**
     * An archive as v1.0.10 writes one: metadata at Flyway 76, one CSV per table. The rows come from
     * a scratch database built out of the frozen v1.0.10 schema, which is dropped again.
     */
    private fun legacyArchive(
        source: String,
        seed: (Connection) -> Unit,
    ): ByteArray {
        pg.dataSource.connection.use { it.createStatement().execute("CREATE DATABASE $source") }
        try {
            return connect(source).use { conn ->
                conn.createStatement().execute(resource("db/restore-base/base_schema_v76.sql"))
                seed(conn)
                zipOf(conn)
            }
        } finally {
            pg.dataSource.connection.use { it.createStatement().execute("DROP DATABASE $source WITH (FORCE)") }
        }
    }

    private fun zipOf(conn: Connection): ByteArray {
        val tables =
            conn.createStatement().executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        val copy = CopyManager(conn.unwrap(BaseConnection::class.java))
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(
                """{"backupDate":"2026-09-30T00:00:00","flywayVersion":"76","tableCount":${tables.size},"format":"jdbc-copy-csv","filesIncluded":false}"""
                    .toByteArray(),
            )
            zip.closeEntry()
            for (table in tables) {
                zip.putNextEntry(ZipEntry("data/$table.csv"))
                copy.copyOut("COPY \"$table\" TO STDOUT WITH (FORMAT csv, HEADER true)", zip)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun stagingDatabases(): List<String> =
        pg.dataSource.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT datname FROM pg_database WHERE datname LIKE 'pos_restore_staging_%'").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun connect(database: String): Connection = DriverManager.getConnection(RestoreStaging.withDatabase(pg.jdbcUrl, database), "postgres", "postgres")

    private fun <T> live(block: (Connection) -> T): T =
        DatabaseFactory.getDataSource().connection.use { conn ->
            block(conn).also {
                if (!conn.autoCommit) conn.commit()
            }
        }

    private fun resource(path: String): String = javaClass.classLoader.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() }

    private fun Connection.longOrNull(sql: String): Long? =
        createStatement().executeQuery(sql).use { rs ->
            check(rs.next()) { "no row for $sql" }
            rs.getLong(1).takeUnless { rs.wasNull() }
        }

    private fun Connection.long(sql: String): Long = longOrNull(sql) ?: error("null for $sql")

    private fun Connection.bool(sql: String): Boolean = createStatement().executeQuery(sql).use { rs -> rs.next() && rs.getBoolean(1) }

    private fun Connection.text(sql: String): String =
        createStatement().executeQuery(sql).use { rs ->
            check(rs.next())
            rs.getString(1)
        }
}
