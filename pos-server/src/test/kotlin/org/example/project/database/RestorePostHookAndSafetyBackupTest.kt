package org.example.project.database

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.backup.domain.service.PostRestoreHook
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real restore on PostgreSQL: the post-restore hooks run after the commit with failures logged and
 * not thrown, and the pre-restore safety backup is taken exactly when an order row exists (probed by
 * table name, without the order feature's Exposed table).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Restore post-restore hooks and safety-backup activity probe — real PostgreSQL (zonky)")
class RestorePostHookAndSafetyBackupTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        initDB(pg.hikariConfig(), runMigrations = true)
        // The embedded database outlives a failed run; start with no order row.
        exec("DELETE FROM t_order")
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) {
            DatabaseFactory.close()
            pg.close()
        }
    }

    private fun service(
        safetyDir: File,
        hooks: List<PostRestoreHook>,
    ) = BackupRestoreServiceImpl(
        ConfigurationRepository(),
        PathsConfigManager(
            Files
                .createTempDirectory("posrestore-docs")
                .toFile()
                .apply { deleteOnExit() }
                .absolutePath,
            safetyDir.absolutePath,
        ),
        LiveDataSourceProvider { DatabaseFactory.getDataSource() },
        postRestoreHooks = hooks,
    ) { error("BuiltInJobsSeeder is never used by the restore path") }

    private fun exec(sql: String) =
        pg.dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { st -> st.execute(sql) }
        }

    private fun safetyBackups(dir: File): List<File> = dir.listFiles { f -> f.name.startsWith("pre-restore_") }.orEmpty().toList()

    @Test
    fun `test_post_restore_hooks_run_after_commit_and_failures_are_logged_not_thrown`() {
        val safetyDir = Files.createTempDirectory("posrestore-safety").toFile().apply { deleteOnExit() }
        val calls = mutableListOf<String>()
        val failing =
            PostRestoreHook {
                calls += "first"
                error("boom")
            }
        val second = PostRestoreHook { calls += "second" }
        val svc = service(safetyDir, listOf(failing, second))

        val archive = runBlocking { svc.createBackup(null) }
        val result = runBlocking { svc.restoreBackup(archive) }

        assertTrue(result.success, "a failing hook must not turn the committed restore into a failure")
        assertEquals(listOf("first", "second"), calls, "the second hook still runs after the first throws")
    }

    @Test
    fun `test_safety_backup_taken_when_orders_exist_without_importing_ecr_tables`() {
        val emptyDir = Files.createTempDirectory("posrestore-safety-none").toFile().apply { deleteOnExit() }
        exec("DELETE FROM t_order")
        val noOrders = service(emptyDir, emptyList())
        runBlocking { noOrders.restoreBackup(runBlocking { noOrders.createBackup(null) }) }
        assertTrue(safetyBackups(emptyDir).isEmpty(), "no orders: no safety backup")

        val withOrdersDir = Files.createTempDirectory("posrestore-safety-orders").toFile().apply { deleteOnExit() }
        val withOrders = service(withOrdersDir, emptyList())
        // The archive is taken while no order exists, so restoring it leaves no dangling reference
        // from the placeholder row inserted next (the restore truncates it away).
        val archive = runBlocking { withOrders.createBackup(null) }
        pg.dataSource.connection.use {
            it.autoCommit = true
            insertOrderRow(it)
        }
        try {
            runBlocking { withOrders.restoreBackup(archive) }
        } finally {
            exec("DELETE FROM t_order")
        }
        assertEquals(1, safetyBackups(withOrdersDir).size, "an order exists: the pre-restore safety backup is taken")
    }

    /** Foreign keys of the placeholder row are not satisfiable; the archive probe only needs the row. */
    private fun insertOrderRow(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("ALTER TABLE t_order DISABLE TRIGGER ALL")
            val cols = mutableListOf<String>()
            val vals = mutableListOf<String>()
            conn
                .prepareStatement(
                    "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema='public' " +
                        "AND table_name='t_order' AND is_nullable='NO' AND column_default IS NULL AND is_identity='NO'",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val type = rs.getString("data_type")
                            cols += "\"${rs.getString("column_name")}\""
                            vals +=
                                when {
                                    type.contains("char") || type == "text" -> "'x'"
                                    type == "boolean" -> "FALSE"
                                    type == "uuid" -> "'00000000-0000-0000-0000-000000000000'"
                                    type.startsWith("timestamp") || type == "date" -> "NOW()"
                                    else -> "0"
                                }
                        }
                    }
                }
            st.execute("INSERT INTO t_order (${cols.joinToString()}) VALUES (${vals.joinToString()})")
            st.execute("ALTER TABLE t_order ENABLE TRIGGER ALL")
        }
    }

    private class PathsConfigManager(
        private val documentStoragePath: String,
        private val backupPath: String,
    ) : IShopConfigurationManager by ShopConfigurationManager {
        override suspend fun getDocumentStoragePath(): String = documentStoragePath

        override suspend fun getDefaultBackupPath(): String? = backupPath

        override suspend fun refreshConfiguration(): Boolean = true

        override suspend fun getRestoredPathsPendingReview(): Map<RestoredPathSetting, String> = emptyMap()
    }
}
