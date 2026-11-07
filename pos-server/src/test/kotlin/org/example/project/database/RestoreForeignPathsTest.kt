package org.example.project.database

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.scheduler.data.repository.JobExecutionRepositoryImpl
import org.example.project.features.scheduler.data.repository.ScheduledJobRepositoryImpl
import org.example.project.features.scheduler.data.service.SchedulerEngine
import org.example.project.features.scheduler.database.tables.JobExecutionEntity
import org.example.project.features.scheduler.database.tables.JobExecutions
import org.example.project.features.scheduler.database.tables.ScheduledJobEntity
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.initDB
import org.example.project.pdf.util.FileStorageHelper
import org.example.project.testutils.PostgresTestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The QA finding, end to end on real PostgreSQL: a backup restored onto ANOTHER installation carried
 * the original shop's absolute DATEV export path, and the overdue DATEV catch-up ran straight into
 * it before anyone could look.
 *
 * Drives the real restore ([BackupRestoreServiceImpl.restoreBackup]) and the real scheduler engine
 * against a job whose executor writes into the configured folder, and asserts on that folder: after
 * the restore nothing may be written there, and an explicit export answers 409 — while a restore
 * whose paths are this installation's own changes nothing.
 *
 * "This installation" is a temp app-data directory injected into the service, so nothing here reads
 * or writes the real `user.home`. Synthetic data only.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Restore onto another installation pauses writers on its foreign paths — real PostgreSQL")
class RestoreForeignPathsTest {
    private lateinit var pg: PostgresTestDatabase
    private val thisInstall: File = Files.createTempDirectory("this-install-appdata").toFile()
    private val originalShop: File = Files.createTempDirectory("original-shop-exports").toFile()
    private val localDocs = File(thisInstall, "documents").apply { mkdirs() }

    /** The effective document root the service sees; the backup leg points it at the ORIGINAL shop's. */
    private var documentRoot: File = localDocs

    /** Reads the pause from the database, as the real manager's cache does after its refresh. */
    private val configManager =
        object : IShopConfigurationManager by ShopConfigurationManager {
            override suspend fun getDocumentStoragePath(): String = documentRoot.absolutePath

            override suspend fun getDefaultBackupPath(): String? = File(thisInstall, "backups").absolutePath

            override suspend fun refreshConfiguration(): Boolean = true

            override suspend fun getRestoredPathsPendingReview(): Map<RestoredPathSetting, String> =
                pg.dataSource.connection.use { conn -> RestoredPathPolicy.decodeReview(readReviewRow(conn)) }
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
        originalShop.deleteRecursively()
    }

    @Test
    @DisplayName("a foreign DATEV path is flagged in the restore commit, the catch-up is skipped and writes nothing, an export is a 409")
    fun foreignPathPausesWriters() {
        // The ARCHIVE: the original shop's absolute export folder, plus a stale review row that a
        // backup taken mid-review would carry — it must not survive onto the new install.
        writeConfig(
            datevExportPath = originalShop.absolutePath,
            backupPath = File(thisInstall, "backups").absolutePath,
            staleReview = """{"DOCUMENT_STORAGE_PATH":"/stale/from/the/backup"}""",
        )
        val archive = runBlocking { service.createBackup(null) }

        val result = runBlocking { service.restoreBackup(archive) }

        assertEquals(
            listOf(RestoredPathSetting.DATEV_EXPORT_PATH),
            result.restoredPathsNeedingReview.map { it.setting },
            "only the foreign path is flagged; the in-install backup path and the stale row are not",
        )
        assertEquals(originalShop.absolutePath, result.restoredPathsNeedingReview.single().configuredPath)
        assertEquals(File(thisInstall, "exports").path, result.restoredPathsNeedingReview.single().defaultPath)
        assertTrue(result.message.contains("need review"), "the API caller must read it: ${result.message}")
        assertEquals(
            mapOf(RestoredPathSetting.DATEV_EXPORT_PATH to originalShop.absolutePath),
            runBlocking { configManager.getRestoredPathsPendingReview() },
            "the pause is committed with the restore",
        )

        // The catch-up that would run right after the restore: due, and its executor writes into
        // the configured folder.
        val jobId = seedDueDatevJob()
        runBlocking { engine().processDueJobs() }
        assertEquals(listOf(JobStatus.SKIPPED.name), awaitStatuses(jobId))
        assertTrue(originalShop.listFiles().orEmpty().isEmpty(), "nothing may be written into the original shop's folder")

        // An operator's explicit export is refused at the server.
        val refusal =
            assertFailsWith<PosException> {
                runBlocking {
                    DatevXmlExportServiceImpl(unused(), unused(), unused(), configManager, unused(), unused())
                        .export("2026-08-01", "2026-08-31", UUID.randomUUID(), "2026-08", DatevExportSource.MANUAL, null)
                }
            }
        assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code)
        assertTrue(originalShop.listFiles().orEmpty().isEmpty())
    }

    @Test
    @DisplayName("a foreign document path offers only 'use default' and a legacy absolute document path is readable afterwards")
    fun foreignDocumentPathKeepsDocumentsReadable() {
        // The ORIGINAL installation: its own document root, one relative and one legacy absolute row.
        val originalDocs = Files.createTempDirectory("original-shop-documents").toFile()
        File(originalDocs, "invoices/2026-08").mkdirs()
        File(originalDocs, "invoices/2026-08/relative.pdf").writeText("%PDF relative")
        File(originalDocs, "invoices/2026-08/legacy.pdf").writeText("%PDF legacy")
        writeConfig(datevExportPath = "", backupPath = "", staleReview = null, documentPath = originalDocs.absolutePath)
        val legacyAbsolute = File(originalDocs, "invoices/2026-08/legacy.pdf").absolutePath
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM documents") }
            val employee = insertMinimalRow(conn, "employees", idColumn = "employee_id")
            insertDocument(conn, employee, "invoices/2026-08/relative.pdf")
            insertDocument(conn, employee, legacyAbsolute)
        }
        documentRoot = originalDocs
        val archive = runBlocking { service.createBackup(null) }

        // THIS installation: the restore extracts files under its own root.
        documentRoot = localDocs
        val result = runBlocking { service.restoreBackup(archive) }

        val flagged = result.restoredPathsNeedingReview.single()
        assertEquals(RestoredPathSetting.DOCUMENT_STORAGE_PATH, flagged.setting)
        assertEquals(false, flagged.keepAllowed, "only 'use default' may be offered for the document path")
        val stored =
            pg.dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT file_path FROM documents ORDER BY file_path").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        assertEquals(listOf("invoices/2026-08/legacy.pdf", "invoices/2026-08/relative.pdf"), stored, "the legacy absolute row is relative now")
        stored.forEach { path ->
            val resolved = File(FileStorageHelper.resolveStoredPath(path, localDocs.absolutePath))
            assertTrue(resolved.isFile, "restored document must be readable under this installation's root: $resolved")
            assertTrue(FileStorageHelper.isPathSafe(resolved.path, localDocs.absolutePath))
        }
        originalDocs.deleteRecursively()
    }

    @Test
    @DisplayName("a restore whose paths are this installation's own flags nothing and the export job runs as before")
    fun ownPathsChangeNothing() {
        val ownExports = File(thisInstall, "exports").apply { mkdirs() }
        writeConfig(datevExportPath = ownExports.absolutePath, backupPath = "", staleReview = null)
        val archive = runBlocking { service.createBackup(null) }

        val result = runBlocking { service.restoreBackup(archive) }

        assertTrue(result.restoredPathsNeedingReview.isEmpty())
        assertTrue(runBlocking { configManager.getRestoredPathsPendingReview() }.isEmpty())
        val jobId = seedDueDatevJob(target = ownExports)
        runBlocking { engine(target = ownExports).processDueJobs() }
        assertEquals(listOf(JobStatus.SUCCESS.name), awaitStatuses(jobId))
        assertEquals(1, ownExports.listFiles().orEmpty().size, "the export ran and wrote its file")
    }

    // ------------------------------------------------------------------------------------------

    private fun engine(target: File = originalShop) =
        SchedulerEngine(
            ScheduledJobRepositoryImpl(),
            JobExecutionRepositoryImpl(),
            listOf(writingDatevExecutor(target)),
            pendingRestoredPaths = { configManager.getRestoredPathsPendingReview().keys },
        )

    /** Stands in for the DATEV catch-up: writes one file into the export folder when it runs. */
    private fun writingDatevExecutor(target: File) =
        object : ScheduledJobExecutor {
            override val type = ScheduledJobType.DATEV_AUTO_EXPORT
            override val historyRetention = 10
            override val labelKey = "TEST"
            override val restoredPathsWritten = setOf(RestoredPathSetting.DATEV_EXPORT_PATH)

            override suspend fun execute(parametersJson: String): String {
                File(target, "datev_${UUID.randomUUID()}.zip").writeText("export")
                return "exported"
            }

            override fun validateParameters(parametersJson: String) {}

            override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
        }

    private fun writeConfig(
        datevExportPath: String,
        backupPath: String,
        staleReview: String?,
        documentPath: String = "",
    ) {
        documentRoot = localDocs
        pg.dataSource.connection.use { conn ->
            val shopId = ensureShop(conn)
            upsertConfig(
                conn,
                shopId,
                ConfigKey.DATEV_XML_CONFIG.key,
                """{"enabled":true,"exportPath":${Json.encodeToString(String.serializer(), datevExportPath)}}""",
            )
            upsertConfig(conn, shopId, ConfigKey.BACKUP_CONFIG.key, """{"defaultBackupPath":${Json.encodeToString(String.serializer(), backupPath)}}""")
            upsertConfig(
                conn,
                shopId,
                ConfigKey.DOCUMENT_STORAGE_CONFIG.key,
                """{"path":${Json.encodeToString(String.serializer(), documentPath)}}""",
            )
            if (staleReview != null) upsertConfig(conn, shopId, ConfigKey.RESTORED_PATHS_REVIEW.key, staleReview)
        }
    }

    private fun insertDocument(
        conn: Connection,
        employeeId: String,
        filePath: String,
    ) {
        insertMinimalRow(
            conn,
            "documents",
            mapOf(
                "file_path" to "'${filePath.replace("'", "''")}'",
                "uploaded_by" to "'$employeeId'",
                "content_hash" to "'${UUID.randomUUID()}'",
            ),
        )
    }

    /** The cheapest row [table] accepts: a placeholder for every NOT-NULL column without a default. */
    private fun insertMinimalRow(
        conn: Connection,
        table: String,
        overrides: Map<String, String> = emptyMap(),
        idColumn: String = "id",
    ): String {
        val values = LinkedHashMap<String, String>()
        conn
            .prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' " +
                    "AND table_name = ? AND is_nullable = 'NO' AND column_default IS NULL AND is_identity = 'NO'",
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    while (rs.next()) values[rs.getString("column_name")] = placeholderFor(rs.getString("data_type"))
                }
            }
        values.putAll(overrides)
        val sql =
            "INSERT INTO \"$table\" (${values.keys.joinToString(", ") { "\"$it\"" }}) " +
                "VALUES (${values.values.joinToString(", ")}) RETURNING \"$idColumn\""
        return conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next().let { rs.getString(1) } } }
    }

    private fun placeholderFor(type: String): String =
        when {
            type.contains("char") || type == "text" -> "'x${UUID.randomUUID().toString().take(8)}'"
            type == "boolean" -> "FALSE"
            type.startsWith("timestamp") || type == "date" -> "NOW()"
            type == "uuid" -> "gen_random_uuid()"
            else -> "0"
        }

    private fun readReviewRow(conn: Connection): JsonElement? =
        conn.prepareStatement("SELECT value::text FROM shop_configuration WHERE key = ?").use { ps ->
            ps.setString(1, ConfigKey.RESTORED_PATHS_REVIEW.key)
            ps.executeQuery().use { rs -> if (rs.next()) Json.parseToJsonElement(rs.getString(1)) else null }
        }

    private fun upsertConfig(
        conn: Connection,
        shopId: Long,
        key: String,
        json: String,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO shop_configuration (shop_id, key, value, updated_at) VALUES (?, ?, CAST(? AS jsonb), now()) " +
                    "ON CONFLICT (shop_id, key) DO UPDATE SET value = EXCLUDED.value",
            ).use { ps ->
                ps.setLong(1, shopId)
                ps.setString(2, key)
                ps.setString(3, json)
                ps.executeUpdate()
            }
    }

    private fun ensureShop(conn: Connection): Long {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT MIN(shop_id) FROM shop_info").use { rs ->
                if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() }?.let { return it }
            }
        }
        val values = LinkedHashMap<String, String>()
        conn
            .prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' " +
                    "AND table_name = 'shop_info' AND is_nullable = 'NO' AND column_default IS NULL AND is_identity = 'NO'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val type = rs.getString("data_type")
                        values[rs.getString("column_name")] =
                            when {
                                type.contains("char") || type == "text" -> "'x'"
                                type == "boolean" -> "FALSE"
                                type.startsWith("timestamp") || type == "date" -> "NOW()"
                                else -> "0"
                            }
                    }
                }
            }
        val sql =
            "INSERT INTO shop_info (${values.keys.joinToString(", ") { "\"$it\"" }}) " +
                "VALUES (${values.values.joinToString(", ")}) RETURNING shop_id"
        return conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next().let { rs.getLong(1) } } }
    }

    private fun seedDueDatevJob(target: File = originalShop): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        transaction {
            ScheduledJobEntity.new(id) {
                name = "DATEV auto export → ${target.name}"
                type = ScheduledJobType.DATEV_AUTO_EXPORT.name
                enabled = true
                frequency = JobFrequency.DAILY.name
                timeOfDay = "03:00"
                dayOfWeek = null
                dayOfMonth = null
                parametersJson = "{}"
                timezone = "Europe/Berlin"
                nextRunAt = now.minusSeconds(3600).toUtcLocal()
                lastRunAt = null
                lastStatus = null
                createdAt = now.toUtcLocal()
                updatedAt = now.toUtcLocal()
            }
        }
        return id
    }

    private fun awaitStatuses(jobId: UUID): List<String> {
        repeat(50) {
            val statuses = transaction { JobExecutionEntity.find { JobExecutions.jobId eq jobId }.map { it.status } }
            if (statuses.isNotEmpty() && JobStatus.RUNNING.name !in statuses) return statuses
            Thread.sleep(100)
        }
        error("job $jobId did not finish")
    }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${T::class.simpleName}.${method.name} must not be reached")
        } as T

    private fun Instant.toUtcLocal() =
        kotlinx.datetime.Instant
            .fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.UTC)
}
