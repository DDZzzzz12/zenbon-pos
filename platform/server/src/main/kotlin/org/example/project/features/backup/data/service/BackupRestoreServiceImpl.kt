/**
 * Implementation of BackupRestoreService using JDBC COPY protocol.
 *
 * Provides database backup and restore functionality using PostgreSQL's
 * COPY protocol via [CopyManager] for reliable, self-contained backups
 * with no external binary dependencies (no pg_dump/pg_restore required).
 *
 * Backup format: ZIP archive containing metadata.json and per-table CSV files.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 11.
 *
 * I/O failures (Postgres COPY protocol, ZIP read/write, filesystem) are wrapped
 * as PosException(INTERNAL_ERROR, InternalServerError, ..., cause = e), preserving
 * the original cause for diagnostics.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.backup.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.example.project.core.config.BackupConfigJson
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.config.restoredPathNeedsReview
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.BackupCopyPathRules
import org.example.project.database.LiveDataSourceProvider
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.backup.domain.service.PostRestoreHook
import org.example.project.features.backup.domain.service.RestoredPathReviewService
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.BackupUtils
import org.example.project.utils.FileSystemUtils
import org.example.project.utils.OsUtils
import org.example.project.utils.dbQuery
import org.example.project.utils.quoteSqlIdentifier
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * JDBC-based backup and restore service implementation.
 *
 * Uses PostgreSQL's COPY protocol via [CopyManager] to export/import table data
 * as CSV files within a ZIP archive. Schema is managed by Flyway migrations,
 * so only data is backed up and restored.
 *
 * Backup ZIP structure:
 * ```
 * pos_backup_YYYYMMDD_HHmmss.zip
 * ├── metadata.json       (backup date, PG version, table list, Flyway version)
 * └── data/
 *     ├── employees.csv
 *     ├── product.csv
 *     └── ...              (one CSV per table)
 * ```
 *
 * @param configRepository Repository for shop configuration settings
 * @param postRestoreHooks Run one after another after the restore commits; each failure is logged at ERROR, never thrown.
 *   Unordered: Koin's `getAll` returns map iteration order, not declaration order (`KoinGetAllOrderTest`),
 *   so a second hook that must run before or after another needs an explicit order key on the port.
 * @param restoredPathReview Told about every stored backup path, so a pending post-restore review
 *   entry is resolved (or shows the new path) when the operator replaces the restored one
 */
class BackupRestoreServiceImpl(
    private val configRepository: ConfigurationRepository,
    private val configManager: IShopConfigurationManager,
    private val liveDataSource: LiveDataSourceProvider,
    private val appDataDirectory: () -> String = { OsUtils.getAppDataDirectory() },
    private val restoredPathReview: RestoredPathReviewService =
        RestoredPathReviewServiceImpl(configRepository, configManager, appDataDirectory),
    private val postRestoreHooks: List<PostRestoreHook>,
    private val seederProvider: () -> org.example.project.features.scheduler.data.service.BuiltInJobsSeeder,
) : BackupRestoreService {
    private companion object {
        /**
         * Physical order table, probed by name so backup does not import the order feature's tables. Used unquoted:
         * a constant lowercase name resolves on Postgres (folds to lower) and on the H2 test database (folds to upper).
         */
        const val ORDER_TABLE = "t_order"
    }

    private val logger = LoggerFactory.getLogger(BackupRestoreServiceImpl::class.java)
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto?): ByteArray {
        logger.info("createBackup: starting JDBC-based database backup")
        val backupData =
            try {
                executeBackupToBytes()
            } catch (e: PosException) {
                throw e
            } catch (e: IOException) {
                throw backupIoFailed("ZIP write failed: ${e.message}", e)
            } catch (e: Exception) {
                throw backupIoFailed("Backup failed: ${e.message}", e)
            }
        logger.info("createBackup: backup created size={} bytes", backupData.size)
        updateLastBackupTimestamp()
        return backupData
    }

    override suspend fun restoreBackup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto {
        logger.warn("restoreBackup: starting restore from ZIP archive - THIS WILL REPLACE ALL DATA")
        createSafetyBackupIfNeeded()
        val outcome =
            try {
                executeRestore(ByteArrayInputStream(backupData))
            } catch (e: PosException) {
                throw e
            } catch (e: IOException) {
                throw restoreFailed("ZIP read failed: ${e.message}", e)
            } catch (e: Exception) {
                throw restoreFailed("Restore failed: ${e.message}", e)
            }
        return restoreResult(outcome, source = null)
    }

    override suspend fun restoreBackup(backupFile: File): BackupRestoreDto.RestoreResultDto {
        logger.warn("restoreBackup: starting restore from file - THIS WILL REPLACE ALL DATA")
        createSafetyBackupIfNeeded()
        val outcome =
            try {
                executeRestore(FileInputStream(backupFile))
            } catch (e: PosException) {
                throw e
            } catch (e: IOException) {
                throw restoreFailed("ZIP read failed: ${e.message}", e)
            } catch (e: Exception) {
                throw restoreFailed("Restore failed: ${e.message}", e)
            }
        return restoreResult(outcome, source = null)
    }

    override suspend fun getMaxUploadBytes(): Long = configManager.getBackupMaxUploadBytes()

    override suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto =
        BackupRestoreDto.BackupConfigDto(
            defaultBackupPath = configManager.getDefaultBackupPath(),
            lastBackupAt = configManager.getLastBackupAt(),
            autoEnabled = configManager.getBackupAutoEnabled(),
            cadence = configManager.getBackupCadence(),
            retentionCount = configManager.getBackupRetentionCount(),
            runHour = configManager.getBackupRunHour(),
            maxUploadBytes = configManager.getBackupMaxUploadBytes(),
            copyTo = configManager.getBackupCopyTarget(),
            copyRetentionCount = configManager.getBackupCopyRetentionCount(),
            lastCopyAt = configManager.getLastBackupCopyAt(),
            lastCopyStatus = configManager.getLastBackupCopyStatus(),
            lastCopyError = configManager.getLastBackupCopyError(),
        )

    override suspend fun updateBackupConfig(
        shopId: Long,
        config: BackupRestoreDto.UpdateBackupConfigDto,
    ): BackupRestoreDto.BackupConfigDto {
        try {
            config.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "invalid format")
        }
        requireCopyTargetOutsideBackupFolder(config)
        // Before anything is stored, like the DATEV export path (SET-05): an absolute path naming a
        // writable (or creatable) directory, never a file and never relative to the working directory.
        config.defaultBackupPath?.let { FileSystemUtils.ensureAbsoluteWritableDirectory(it) }

        val response =
            dbQuery {
                val existingJson = configRepository.getSetting(shopId, ConfigKey.BACKUP_CONFIG.key) as? JsonObject
                val updatedJson = BackupConfigJson.updated(existingJson, config)

                configRepository.setSetting(
                    shopId = shopId,
                    configKey = ConfigKey.BACKUP_CONFIG,
                    value = Json.encodeToString(JsonObject.serializer(), updatedJson),
                )

                BackupConfigJson.toDto(updatedJson)
            }

        configManager.refreshConfiguration()
        restoredPathReview.onPathConfigured(RestoredPathSetting.BACKUP_PATH, config.defaultBackupPath.orEmpty())
        restoredPathReview.onPathConfigured(RestoredPathSetting.BACKUP_COPY_PATH, config.copyTo?.path.orEmpty())
        dbQuery { seederProvider().seedBackup() }
        return response
    }

    /** The copy target must not be the local backup folder (or inside it), including the unset default. */
    private suspend fun requireCopyTargetOutsideBackupFolder(config: BackupRestoreDto.UpdateBackupConfigDto) {
        val target = config.copyTo?.path ?: return
        val folder =
            config.defaultBackupPath
                ?: RestoredPathPolicy.defaultPath(RestoredPathSetting.BACKUP_PATH, appDataDirectory())
        if (BackupCopyPathRules.isSameOrInside(target, folder)) {
            throw validationFailed("copyTo.path: must not be inside the backup folder ($folder)")
        }
    }

    /**
     * Creates a pre-restore safety backup when the database has real activity (any order rows).
     * The file is named `pre-restore_<timestamp>.zip` so the scheduler's prune skips it.
     * If the safety backup fails, the restore is aborted — caller never sees a half-state.
     */
    private suspend fun createSafetyBackupIfNeeded() {
        val hasOrders =
            dbQuery {
                TransactionManager.current().exec("SELECT 1 FROM $ORDER_TABLE LIMIT 1") { rs -> rs.next() } ?: false
            }
        if (!hasOrders) {
            logger.info("createSafetyBackupIfNeeded: skipping (no orders found)")
            return
        }

        val savePath =
            getDefaultBackupPath()
                ?: throw validationFailed(
                    "Cannot create pre-restore safety backup: BACKUP_CONFIG.defaultBackupPath is not set",
                )
        requireBackupTargetReviewed(savePath, "Pre-restore safety backup")
        val saveDir = File(savePath)
        if (!saveDir.exists()) {
            saveDir.mkdirs()
        }
        if (!saveDir.isDirectory || !saveDir.canWrite()) {
            throw validationFailed("Pre-restore safety backup path not writable: $savePath")
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val safetyFile = File(saveDir, "pre-restore_$timestamp.zip")
        logger.warn("createSafetyBackupIfNeeded: writing safety backup to {}", safetyFile.absolutePath)
        try {
            executeBackupToFile(safetyFile)
        } catch (e: PosException) {
            throw e
        } catch (e: IOException) {
            throw backupIoFailed("Pre-restore safety backup failed: ${e.message}", e)
        } catch (e: Exception) {
            throw backupIoFailed("Pre-restore safety backup failed: ${e.message}", e)
        }
        logger.info("createSafetyBackupIfNeeded: safety backup written ({} bytes)", safetyFile.length())
    }

    /**
     * Updates the lastBackupAt timestamp in configuration after successful backup.
     * Non-critical: failures are logged but never propagated (don't fail the backup).
     */
    private suspend fun updateLastBackupTimestamp() {
        try {
            dbQuery {
                val shopId = 1L

                val existingJson = configRepository.getSetting(shopId, ConfigKey.BACKUP_CONFIG.key) as? JsonObject

                // Preserve all existing fields; only overwrite lastBackupAt.
                val updatedJson =
                    buildJsonObject {
                        existingJson?.forEach { (k, v) -> if (k != "lastBackupAt") put(k, v) }
                        put("lastBackupAt", LocalDateTime.now().format(dateTimeFormatter))
                    }

                configRepository.setSetting(
                    shopId = shopId,
                    configKey = ConfigKey.BACKUP_CONFIG,
                    value = Json.encodeToString(JsonObject.serializer(), updatedJson),
                )
            }
        } catch (e: Exception) {
            logger.warn("updateLastBackupTimestamp: failed (non-critical)", e)
        }
    }

    override suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto): BackupRestoreDto.BackupToServerResultDto {
        logger.info("createBackupToServer: starting JDBC-based database backup to server")

        val savePath = request.savePath ?: getDefaultBackupPath()
        if (savePath.isNullOrBlank()) {
            throw validationFailed("savePath is required")
        }
        requireBackupTargetReviewed(savePath, "Backup to server")

        val saveDir = File(savePath)
        try {
            if (!saveDir.exists()) {
                saveDir.mkdirs()
            }
        } catch (e: SecurityException) {
            throw backupIoFailed("Cannot create save directory: ${e.message}", e)
        }
        if (!saveDir.isDirectory || !saveDir.canWrite()) {
            throw validationFailed("savePath must be a writable directory: $savePath")
        }

        val timestamp =
            LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"),
            )
        val backupFile = BackupArchiveNames.reserve(saveDir, timestamp)

        logger.debug("createBackupToServer: creating backup to path={}", backupFile.absolutePath)

        try {
            executeBackupToFile(backupFile)
        } catch (e: PosException) {
            throw e
        } catch (e: IOException) {
            throw backupIoFailed("ZIP write failed for ${backupFile.absolutePath}: ${e.message}", e)
        } catch (e: Exception) {
            throw backupIoFailed("Backup to file failed: ${e.message}", e)
        }

        val fileSize = backupFile.length()
        val createdAt = LocalDateTime.now().format(dateTimeFormatter)

        logger.info(
            "createBackupToServer: backup saved path={}, size={} bytes",
            backupFile.absolutePath,
            fileSize,
        )

        updateLastBackupTimestamp()

        return BackupRestoreDto.BackupToServerResultDto(
            success = true,
            filePath = backupFile.absolutePath,
            filename = backupFile.name,
            sizeBytes = fileSize,
            createdAt = createdAt,
        )
    }

    override suspend fun restoreFromServerFile(filePath: String): BackupRestoreDto.RestoreResultDto {
        logger.warn("restoreFromServerFile: starting database restore from file={}", filePath)

        val backupFile = File(filePath)
        if (!backupFile.exists() || !backupFile.canRead()) {
            throw backupNotFound(filePath)
        }

        createSafetyBackupIfNeeded()

        val outcome =
            try {
                executeRestore(FileInputStream(backupFile))
            } catch (e: PosException) {
                throw e
            } catch (e: IOException) {
                throw restoreFailed("ZIP read failed for $filePath: ${e.message}", e)
            } catch (e: Exception) {
                throw restoreFailed("Restore from $filePath failed: ${e.message}", e)
            }
        return restoreResult(outcome, source = filePath)
    }

    /**
     * The restore's response. When the restored config carried folder paths of another
     * installation the message says so and lists them, so an operator driving the API sees that
     * exports and backups to those paths are paused until reviewed.
     */
    private fun restoreResult(
        outcome: RestoreOutcome,
        source: String?,
    ): BackupRestoreDto.RestoreResultDto {
        val restoredAt = LocalDateTime.now().format(dateTimeFormatter)
        val base = if (outcome.filesRestored) "Database and files restored successfully" else "Database restored successfully"
        val withSource = if (source != null) "$base from $source" else base
        val review = RestoredPathPolicy.toDtos(outcome.pathsNeedingReview, appDataDirectory())
        val message =
            if (review.isEmpty()) {
                withSource
            } else {
                "$withSource. Restored paths need review before exports and backups write there: " +
                    review.joinToString("; ") { "${it.setting}=${it.configuredPath}" }
            }
        logger.info(
            "restore: completed at {}, source={}, filesRestored={}, pathsNeedingReview={}",
            restoredAt,
            source ?: "upload",
            outcome.filesRestored,
            outcome.pathsNeedingReview.keys,
        )
        return BackupRestoreDto.RestoreResultDto(
            success = true,
            message = message,
            restoredAt = restoredAt,
            restoredPathsNeedingReview = review,
        )
    }

    override suspend fun listDirectory(path: String?): BackupRestoreDto.DirectoryListingDto {
        val targetPath = path ?: System.getProperty("user.home") ?: "/"
        val directory = File(targetPath)

        if (!directory.exists() || !directory.isDirectory) {
            throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Directory not found: $targetPath",
            )
        }

        val entries = mutableListOf<BackupRestoreDto.DirectoryEntryDto>()

        if (path == null || targetPath == System.getProperty("user.home")) {
            BackupUtils.getExternalDrives().forEach { (name, drivePath) ->
                val driveDir = File(drivePath)
                entries.add(
                    BackupRestoreDto.DirectoryEntryDto(
                        name = name,
                        path = drivePath,
                        isDirectory = true,
                        isReadable = driveDir.canRead(),
                        isWritable = driveDir.canWrite(),
                    ),
                )
            }
        }

        try {
            directory
                .listFiles()
                ?.filter { file ->
                    file.isDirectory &&
                        !file.name.startsWith(".") &&
                        !BackupUtils.isTseDevice(file)
                }?.sortedBy { it.name.lowercase() }
                ?.forEach { file ->
                    entries.add(
                        BackupRestoreDto.DirectoryEntryDto(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            isReadable = file.canRead(),
                            isWritable = file.canWrite(),
                        ),
                    )
                }
        } catch (e: SecurityException) {
            logger.error("listDirectory: access denied path={}", path, e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.Forbidden,
                "Access denied listing directory: $targetPath",
                cause = e,
            )
        }

        return BackupRestoreDto.DirectoryListingDto(
            currentPath = directory.absolutePath,
            parentPath = directory.parentFile?.absolutePath,
            entries = entries,
            canWrite = directory.canWrite(),
        )
    }

    override suspend fun listBackups(directory: String?): BackupRestoreDto.BackupListDto {
        val targetDir =
            directory ?: getDefaultBackupPath() ?: System.getProperty("user.home")
                ?: throw backupIoFailed("Cannot resolve a directory to scan for backups")
        val dir = File(targetDir)

        if (!dir.exists() || !dir.isDirectory) {
            return BackupRestoreDto.BackupListDto(
                backups = emptyList(),
                directory = targetDir,
            )
        }

        val backups =
            try {
                dir
                    .listFiles { file ->
                        file.isFile && (file.name.endsWith(".zip") || file.name.endsWith(".dump"))
                    }?.sortedByDescending { it.lastModified() }
                    ?.map { file ->
                        BackupRestoreDto.BackupFileDto(
                            filename = file.name,
                            filePath = file.absolutePath,
                            sizeBytes = file.length(),
                            createdAt =
                                LocalDateTime
                                    .ofInstant(
                                        java.time.Instant.ofEpochMilli(file.lastModified()),
                                        java.time.ZoneId.systemDefault(),
                                    ).format(dateTimeFormatter),
                        )
                    } ?: emptyList()
            } catch (e: SecurityException) {
                throw backupIoFailed("Access denied listing backups in $targetDir: ${e.message}", e)
            }

        return BackupRestoreDto.BackupListDto(
            backups = backups,
            directory = targetDir,
        )
    }

    /**
     * Refuses a backup into the restored backup folder of another installation while it awaits
     * review. Only that folder (and anything beneath it) is refused — an operator who explicitly
     * picks a different folder is not blocked by an unrelated pending path.
     */
    private suspend fun requireBackupTargetReviewed(
        savePath: String,
        operation: String,
    ) {
        val pending = configManager.getRestoredPathsPendingReview()[RestoredPathSetting.BACKUP_PATH] ?: return
        if (RestoredPathPolicy.isWithin(savePath, pending)) {
            throw restoredPathNeedsReview(RestoredPathSetting.BACKUP_PATH, pending, operation)
        }
    }

    /**
     * Gets the default backup path from cached shop configuration.
     */
    private suspend fun getDefaultBackupPath(): String? = configManager.getDefaultBackupPath()

    // ========================================================================
    // JDBC COPY Protocol Implementation
    // ========================================================================

    /**
     * Executes backup using JDBC COPY TO STDOUT and returns a ZIP byte array.
     */
    private suspend fun executeBackupToBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zipOut ->
            writeBackupToZip(zipOut)
        }
        return baos.toByteArray()
    }

    /**
     * Executes backup using JDBC COPY TO STDOUT and writes directly to a file.
     */
    private suspend fun executeBackupToFile(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zipOut ->
            writeBackupToZip(zipOut)
        }
    }

    /**
     * Core backup logic: discovers tables, exports each as CSV into the ZIP stream.
     */
    private suspend fun writeBackupToZip(zipOut: ZipOutputStream) {
        val documentStoragePath = configManager.getDocumentStoragePath()
        val documentStorageDir = File(documentStoragePath)
        val filesIncluded = documentStorageDir.exists() && documentStorageDir.isDirectory

        liveDataSource.liveDataSource().connection.use { conn ->
            val pgConn = conn.unwrap(BaseConnection::class.java)
            val copyManager = CopyManager(pgConn)

            val tables = ArchiveTables.discover(conn)
            logger.info("writeBackupToZip: backing up tableCount={}, tables={}", tables.size, tables)

            val pgVersion = conn.metaData.databaseProductVersion
            val flywayVersion = getFlywayVersion(conn)

            val metadata =
                buildJsonObject {
                    put("backupDate", LocalDateTime.now().format(dateTimeFormatter))
                    put("pgVersion", pgVersion)
                    put("flywayVersion", flywayVersion)
                    put("tableCount", tables.size)
                    put(
                        "tables",
                        kotlinx.serialization.json.JsonArray(
                            tables.map { JsonPrimitive(it) },
                        ),
                    )
                    put("format", "jdbc-copy-csv")
                    put("filesIncluded", filesIncluded)
                    put("documentStoragePath", documentStoragePath)
                }

            zipOut.putNextEntry(ZipEntry("metadata.json"))
            zipOut.write(Json.encodeToString(JsonObject.serializer(), metadata).toByteArray())
            zipOut.closeEntry()

            for (table in tables) {
                zipOut.putNextEntry(ZipEntry("data/$table.csv"))
                copyManager.copyOut("COPY \"$table\" TO STDOUT WITH (FORMAT csv, HEADER true)", zipOut)
                zipOut.closeEntry()
                logger.debug("writeBackupToZip: exported table={}", table)
            }
        }

        if (filesIncluded) {
            writeFilesToZip(zipOut, documentStorageDir)
        }
    }

    /**
     * Recursively adds all files from the document storage directory into the ZIP
     * under the `files/` prefix, preserving the directory structure.
     */
    private fun writeFilesToZip(
        zipOut: ZipOutputStream,
        storageDir: File,
    ) {
        val basePath = storageDir.toPath()
        var fileCount = 0

        Files.walk(basePath).use { stream ->
            stream.forEach { path ->
                val file = path.toFile()
                if (file.isFile) {
                    val relativePath = basePath.relativize(path).toString()
                    zipOut.putNextEntry(ZipEntry("files/$relativePath"))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                    fileCount++
                }
            }
        }

        logger.info(
            "writeFilesToZip: backed up fileCount={} files from {}",
            fileCount,
            storageDir.absolutePath,
        )
    }

    /**
     * Core restore logic: reads ZIP archive and imports CSV data via COPY FROM STDIN.
     *
     * Steps:
     * 1. Read metadata.json to get table list
     * 2. Read all CSV data entries from ZIP into memory
     * 3. Disable FK triggers on all tables
     * 4. Truncate all tables
     * 5. Import CSV data for each table via COPY FROM STDIN
     * 6. Reset sequences to match imported data
     * 7. Re-enable FK triggers
     */
    private suspend fun executeRestore(inputStream: java.io.InputStream): RestoreOutcome {
        var pathsNeedingReview: Map<RestoredPathSetting, String> = emptyMap()
        val csvDataMap = mutableMapOf<String, ByteArray>()
        val fileDataMap = mutableMapOf<String, ByteArray>()
        var metadataJson: String? = null

        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                when {
                    entry.name == "metadata.json" -> {
                        metadataJson = zipIn.readBytes().decodeToString()
                    }
                    entry.name.startsWith("data/") && entry.name.endsWith(".csv") -> {
                        val tableName =
                            entry.name
                                .removePrefix("data/")
                                .removeSuffix(".csv")
                        csvDataMap[tableName] = zipIn.readBytes()
                    }
                    entry.name.startsWith("files/") && !entry.isDirectory -> {
                        val relativePath = entry.name.removePrefix("files/")
                        if (relativePath.isNotEmpty()) {
                            fileDataMap[relativePath] = zipIn.readBytes()
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        if (metadataJson == null) {
            throw backupValidationFailed("missing metadata.json")
        }

        val metadata =
            try {
                Json.decodeFromString(JsonObject.serializer(), metadataJson!!)
            } catch (e: Exception) {
                throw backupValidationFailed("metadata.json is malformed: ${e.message}")
            }
        val backupDate = (metadata["backupDate"] as? JsonPrimitive)?.content ?: "unknown"
        logger.info(
            "executeRestore: restoring backup from date={}, tableCount={}",
            backupDate,
            csvDataMap.size,
        )
        val archiveTables = upgradeIfOlder((metadata["flywayVersion"] as? JsonPrimitive)?.contentOrNull, csvDataMap)

        liveDataSource.liveDataSource().connection.use { conn ->
            val pgConn = conn.unwrap(BaseConnection::class.java)
            val copyManager = CopyManager(pgConn)

            val originalAutoCommit = conn.autoCommit
            conn.autoCommit = false

            try {
                val stmt = conn.createStatement()

                val existingTables = ArchiveTables.discover(conn)
                val tablesToRestore = archiveTables.keys.filter { it in existingTables }

                logger.info(
                    "executeRestore: restoring tablesToRestore={} (of {} in backup)",
                    tablesToRestore.size,
                    archiveTables.size,
                )

                for (table in existingTables) {
                    stmt.execute("ALTER TABLE ${quoteSqlIdentifier(table)} DISABLE TRIGGER ALL")
                }

                // Every table, not only the restorable ones. [RestoreRepairs] depends on it:
                // `repairCashBooks` treats an empty `cash_book` as proof the archive predates the
                // dimension, which only holds if a live table the archive cannot replace has been
                // emptied first. Narrowing this to `tablesToRestore` would leave the live rows in
                // place, the repair would decline, and a pre-V84 archive would abort instead.
                // Seed tables emptied here are put back by [RestoreRepairs] for the same reason.
                if (existingTables.isNotEmpty()) {
                    val truncateSql = existingTables.joinToString(", ") { quoteSqlIdentifier(it) }
                    stmt.execute("TRUNCATE $truncateSql RESTART IDENTITY CASCADE")
                    logger.debug("executeRestore: truncated all tables={}", truncateSql)
                }

                for (table in tablesToRestore) {
                    ArchiveTables.load(conn, copyManager, table, archiveTables[table] ?: continue)
                }

                ArchiveTables.resetSequences(conn)

                // Both run INSIDE this transaction, on purpose. Repairs reconstruct values the
                // archive could not carry; the check then refuses to commit a database that
                // violates its own foreign keys. Doing it here makes repair+verification atomic
                // with the load — the catch below already rolls back, so a violation returns the
                // operator to exactly the state they started in.
                //
                // Order matters: repair first, so a repairable orphan is fixed rather than
                // rejected. After resetSequences, so inserts draw from a settled sequence.
                RestoreRepairs.apply(conn)

                val violations = ForeignKeyIntegrityCheck.findViolations(conn)
                if (violations.isNotEmpty()) {
                    val detail =
                        violations.joinToString("; ") {
                            "${it.constraint} (${it.childTable}.${it.childColumns.joinToString(",")}): " +
                                "${it.orphanCount} orphaned row(s)"
                        }
                    // The restore loads with triggers disabled, so nothing else would catch this.
                    // Failing loudly beats committing a fiscal database that is quietly broken:
                    // a failed restore surfaces now, corruption surfaces during a Kassenprüfung.
                    throw IllegalStateException(
                        "Restore aborted: the restored data violates $detail. " +
                            "The restored data was rolled back and the database still holds its " +
                            "previous rows; sequences were realigned with them.",
                    )
                }

                // In this transaction, so the restored paths and the pause on any that belong to
                // another installation commit together — see [RestoredPathReviewStep].
                pathsNeedingReview = RestoredPathReviewStep.apply(conn, appDataDirectory())
                // The archive's files are extracted under THIS installation's storage root; legacy
                // absolute rows into the original root would otherwise miss them.
                RestoredDocumentPaths.apply(
                    conn,
                    (metadata["documentStoragePath"] as? JsonPrimitive)?.contentOrNull,
                )

                for (table in existingTables) {
                    stmt.execute("ALTER TABLE ${quoteSqlIdentifier(table)} ENABLE TRIGGER ALL")
                }

                conn.commit()
                logger.info("executeRestore: restore committed successfully")
            } catch (e: Exception) {
                conn.rollback()
                logger.error("executeRestore: restore failed, rolled back transaction", e)
                realignSequencesAfterRollback(conn, e)
                throw e
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }

        // The archive's KASSEN_MELDUNG_CONFIG names the software that WROTE it, not the one running.
        // Boot rewrites it, but a restore into a running server has no boot after it, so the next
        // Z-Bon froze the archive's version into its Stamm_Kassen snapshot (QA emu21 FAIL-D).
        runPostRestoreHooks()

        // The cache still holds the pre-restore configuration. Reload it before the files are
        // written, so they land where the restored config (and its review state) says documents
        // live — a document path awaiting review resolves to this installation's default.
        configManager.refreshConfiguration()

        val filesRestored = fileDataMap.isNotEmpty()
        if (filesRestored) {
            restoreFiles(fileDataMap)
        }
        return RestoreOutcome(filesRestored, pathsNeedingReview)
    }

    /**
     * Runs every [PostRestoreHook] against the just-committed data. A failure is logged at ERROR,
     * not thrown: the restore itself is committed and reporting it as failed would be false. One
     * hook failing does not stop the next.
     */
    internal suspend fun runPostRestoreHooks() {
        postRestoreHooks.forEach { hook ->
            try {
                hook.afterRestoreCommitted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "executeRestore: post-restore hook ${hook::class.qualifiedName ?: hook.toString()} failed — the restore is " +
                        "committed; restart the server so the hook's follow-up work runs",
                    e,
                )
            }
        }
    }

    /**
     * An archive older than this version's migrations is first brought up to date in a staging
     * database, exactly as an in-place upgrade would ([RestoreStaging]); a current one loads as is.
     * Runs before the live database is touched, so a migration that refuses the archive leaves the
     * operator where they started.
     */
    private fun upgradeIfOlder(
        archiveFlywayVersion: String?,
        csvByTable: Map<String, ByteArray>,
    ): Map<String, ByteArray> {
        if (!RestoreStaging.isNeeded(archiveFlywayVersion)) return csvByTable
        logger.info("executeRestore: archive is at Flyway V{}, migrating it before the load", archiveFlywayVersion)
        return try {
            RestoreStaging.migrate(liveDataSource.liveDataSource(), csvByTable)
        } catch (e: Exception) {
            logger.error("executeRestore: the archive could not be migrated; nothing was restored", e)
            throw restoreFailed(
                "the backup (database version $archiveFlywayVersion) could not be upgraded to this version, " +
                    "so nothing was restored and the database is unchanged. Cause: ${e.rootMessage()}",
                e,
            )
        }
    }

    private fun Throwable.rootMessage(): String = generateSequence(this) { it.cause }.last().message ?: toString()

    private data class RestoreOutcome(
        val filesRestored: Boolean,
        val pathsNeedingReview: Map<RestoredPathSetting, String>,
    )

    /**
     * Restores document storage files from the backup.
     *
     * Clears the existing document storage directory and extracts all backed-up
     * files, preserving directory structure. Called after DB restore commits
     * successfully so that files are only touched when the DB is consistent.
     */
    private suspend fun restoreFiles(fileDataMap: Map<String, ByteArray>) {
        val documentStoragePath = configManager.getDocumentStoragePath()
        val storageDir = File(documentStoragePath)

        logger.info(
            "restoreFiles: restoring fileCount={} files to {}",
            fileDataMap.size,
            storageDir.absolutePath,
        )

        try {
            if (storageDir.exists()) {
                storageDir.deleteRecursively()
                logger.debug("restoreFiles: cleared existing document storage directory")
            }

            var restoredCount = 0
            for ((relativePath, data) in fileDataMap) {
                val targetFile = File(storageDir, relativePath)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(data)
                restoredCount++
            }

            logger.info("restoreFiles: restored restoredCount={} files successfully", restoredCount)
        } catch (e: Exception) {
            logger.error("restoreFiles: failed to restore files (DB restore was successful)", e)
            throw restoreFailed(
                "Database restored successfully but file restore failed: ${e.message}",
                e,
            )
        }
    }

    /**
     * Gets the current Flyway migration version from the schema history table.
     */
    private fun getFlywayVersion(conn: java.sql.Connection): String =
        try {
            val stmt = conn.createStatement()
            val rs =
                stmt.executeQuery(
                    """
                    SELECT version FROM flyway_schema_history
                    WHERE success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """.trimIndent(),
                )
            val version = if (rs.next()) rs.getString("version") else "unknown"
            rs.close()
            stmt.close()
            version
        } catch (e: Exception) {
            logger.warn("getFlywayVersion: could not read Flyway version", e)
            "unknown"
        }

    /**
     * Realigns every sequence with the rows that survived a rolled-back restore.
     *
     * `setval` is **not** transactional in PostgreSQL: on its own, a `ROLLBACK` restores the
     * operator's original rows but leaves every sequence sitting at the *backup's* maximum, and if
     * the live database was larger than the archive every subsequent insert then fails with
     * `duplicate key value violates unique constraint` until the sequence crawls past the live
     * maximum.
     *
     * Measured caveat (see `RestoreAbortSequenceRealignmentTest`): on *this* path the rollback
     * happens to cover the sequences anyway, because [executeRestore] runs
     * `TRUNCATE … RESTART IDENTITY` over every table first, which gives each owned sequence a
     * transaction-local relfilenode that the rollback discards along with the `setval`. So this is
     * a guard, not the thing that currently saves the path — it holds the invariant "sequences
     * match the data actually in the database" even for a sequence the TRUNCATE does not cover, and
     * keeps holding it if that TRUNCATE is ever narrowed.
     *
     * The connection is still usable after a rollback, but `autoCommit` is false — so this commits
     * explicitly. Any failure here is attached to [original] as a suppressed exception rather than
     * replacing it: the caller must still see why the restore failed.
     */
    private fun realignSequencesAfterRollback(
        conn: java.sql.Connection,
        original: Exception,
    ) {
        try {
            logger.warn(
                "executeRestore: realigning sequences with the rolled-back data — setval is not " +
                    "transactional, so the rollback left them at the backup's maximum",
            )
            ArchiveTables.resetSequences(conn)
            conn.commit()
        } catch (e: Exception) {
            logger.error("executeRestore: failed to realign sequences after rollback", e)
            original.addSuppressed(e)
        }
    }

    // ========================================================================
    // PosException helpers
    // ========================================================================

    private fun backupNotFound(path: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Backup file not found: $path",
        )

    private fun backupValidationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "Backup validation failed: $msg",
        )

    private fun backupIoFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "Backup I/O failed: $msg",
        cause = cause,
    )

    private fun restoreFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "Restore failed: $msg",
        cause = cause,
    )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
