/**
 * Manages the lifecycle of an embedded PostgreSQL instance for self-contained deployments.
 *
 * Starts a bundled PostgreSQL process using the zonky embedded-postgres library,
 * creates the application user and database on first run, and provides HikariCP
 * configuration for the connection pool. Data is persisted across restarts in
 * a configurable directory (default: ~/POS Server/data/postgresql/).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.database

import com.zaxxer.hikari.HikariConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.example.project.utils.OSType
import org.example.project.utils.OsUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Embedded PostgreSQL lifecycle manager.
 *
 * Wraps the zonky embedded-postgres library to provide a persistent, self-contained
 * PostgreSQL instance. On first start it runs initdb and creates the app user/database.
 * On subsequent starts it reuses the existing data directory without data loss.
 *
 * @param port TCP port for the embedded PostgreSQL (default: 5433, avoids conflict with system PG on 5432)
 * @param dataDirectory Persistent data directory. Empty/null means platform default.
 * @param databaseName Application database name
 * @param username Application database user
 * @param password Application database password
 */
class EmbeddedDatabaseManager(
    private val port: Int = 5435,
    private val dataDirectory: String = "",
    private val databaseName: String = "pos_db",
    private val username: String = "pos",
    private val password: String = "pos",
) {
    private val logger = LoggerFactory.getLogger(EmbeddedDatabaseManager::class.java)

    private var embeddedPostgres: EmbeddedPostgres? = null

    /** Cached path to the extracted PG bin/ directory (contains pg_dump, pg_restore, etc.) */
    private var cachedBinDirectory: File? = null

    /** PID of the postgres postmaster process, resolved after start */
    private var postmasterPid: Long? = null

    /** Watchdog thread that monitors the postgres process */
    private var watchdogThread: Thread? = null

    @Volatile
    private var watchdogRunning = false

    /**
     * Resolves the persistent data directory for PostgreSQL storage.
     *
     * If [dataDirectory] is blank, falls back to ~/POS Server/data/postgresql/.
     *
     * @return Resolved data directory as a [File]
     */
    private fun resolveDataDirectory(): File {
        val path =
            dataDirectory.ifBlank {
                "${OsUtils.getAppDataDirectory()}${File.separator}.data${File.separator}postgresql"
            }
        val dir = File(path)
        // On Windows, dot-prefix doesn't hide directories — set the hidden attribute explicitly
        if (OsUtils.detectOS() == OSType.WINDOWS) {
            val dotDataDir = dir.parentFile // .data directory
            if (dotDataDir != null && dotDataDir.name.startsWith(".")) {
                dotDataDir.mkdirs()
                try {
                    Files.setAttribute(dotDataDir.toPath(), "dos:hidden", true)
                } catch (_: Exception) {
                    // Ignore if attribute can't be set (e.g., non-NTFS filesystem)
                }
            }
        }
        return dir
    }

    /**
     * Starts the embedded PostgreSQL instance.
     *
     * If the data directory already contains a previous database (detected via postgresql.conf),
     * the existing data is reused. Otherwise initdb runs automatically.
     * After the server starts, [setupDatabaseAndUser] creates the app user and database
     * if they don't exist yet.
     *
     * @throws RuntimeException if PostgreSQL fails to start
     */
    fun start() {
        // Fail here, with a sentence an operator can act on, rather than letting zonky throw
        // "Missing embedded postgres binaries" and a Maven coordinate three frames deeper.
        check(EmbeddedPostgresSupport.isSupported()) { EmbeddedPostgresSupport.unsupportedMessage() }

        val dataDir = resolveDataDirectory()

        val isFirstRun = !File(dataDir, "postgresql.conf").exists()
        if (isFirstRun) {
            logger.info("First run detected — initdb will create a new data directory at: ${dataDir.absolutePath}")
        } else {
            logger.info("Reusing existing PostgreSQL data directory: ${dataDir.absolutePath}")
        }

        logger.info("Starting embedded PostgreSQL on port $port...")

        embeddedPostgres =
            EmbeddedPostgres
                .builder()
                .setPort(port)
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false) // Preserve data across restarts
                .start()

        logger.info("Embedded PostgreSQL started successfully on port $port")

        // Resolve the postmaster PID for watchdog monitoring
        resolvePostmasterPid()

        // Resolve and cache the bin directory for pg_dump/pg_restore
        resolveBinDirectory()

        // Create app user and database if needed
        setupDatabaseAndUser()

        // Start watchdog to detect postgres process death
        startWatchdog()
    }

    /**
     * Creates the application user and database if they don't already exist.
     *
     * Connects as the default superuser (postgres) to execute DDL statements.
     * This is idempotent — repeated calls are safe.
     */
    private fun setupDatabaseAndUser() {
        val pg = embeddedPostgres ?: throw IllegalStateException("Embedded PostgreSQL not started")

        logger.info("Setting up application user '$username' and database '$databaseName'...")

        pg.getPostgresDatabase().connection.use { conn: Connection ->
            conn.autoCommit = true

            // Create user if not exists
            conn.createStatement().use { stmt ->
                val rs =
                    stmt.executeQuery(
                        "SELECT 1 FROM pg_roles WHERE rolname = '${username.replace("'", "''")}'",
                    )
                if (!rs.next()) {
                    logger.info("Creating database user: $username")
                    stmt.execute(
                        "CREATE USER \"${username.replace("\"", "\"\"")}\" WITH SUPERUSER PASSWORD '${password.replace("'", "''")}'",
                    )
                } else {
                    // Ensure existing user has superuser privileges (needed for backup restore)
                    stmt.execute(
                        "ALTER USER \"${username.replace("\"", "\"\"")}\" WITH SUPERUSER",
                    )
                    logger.debug("User '$username' already exists, ensured superuser")
                }
            }

            // Create database if not exists
            conn.createStatement().use { stmt ->
                val rs =
                    stmt.executeQuery(
                        "SELECT 1 FROM pg_database WHERE datname = '${databaseName.replace("'", "''")}'",
                    )
                if (!rs.next()) {
                    logger.info("Creating database: $databaseName")
                    stmt.execute(
                        "CREATE DATABASE \"${databaseName.replace("\"", "\"\"")}\" OWNER \"${username.replace("\"", "\"\"")}\"",
                    )
                } else {
                    logger.debug("Database '$databaseName' already exists")
                }
            }

            // Grant all privileges on the database to the user
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "GRANT ALL PRIVILEGES ON DATABASE \"${databaseName.replace("\"", "\"\"")}\" TO \"${username.replace("\"", "\"\"")}\"",
                )
            }
        }

        logger.info("Database setup completed — user: $username, database: $databaseName")
    }

    /**
     * Creates a [HikariConfig] pointing at the embedded PostgreSQL instance.
     *
     * @param poolConfig Optional pre-loaded pool settings (timeouts, pool size).
     *        If null, sensible defaults are used.
     * @return Configured [HikariConfig] ready for [DatabaseFactory.init]
     */
    fun createHikariConfig(poolConfig: HikariConfig? = null): HikariConfig {
        val jdbcUrl = "jdbc:postgresql://localhost:$port/$databaseName"

        return HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = this@EmbeddedDatabaseManager.username
            this.password = this@EmbeddedDatabaseManager.password
            driverClassName = "org.postgresql.Driver"

            // Apply pool settings from config or use defaults
            if (poolConfig != null) {
                maximumPoolSize = poolConfig.maximumPoolSize
                connectionTimeout = poolConfig.connectionTimeout
                idleTimeout = poolConfig.idleTimeout
                maxLifetime = poolConfig.maxLifetime
                isAutoCommit = poolConfig.isAutoCommit
            } else {
                maximumPoolSize = 10
                connectionTimeout = 30_000
                idleTimeout = 600_000
                maxLifetime = 1_000_000
                isAutoCommit = false
            }

            minimumIdle = 5
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validationTimeout = 3000
            leakDetectionThreshold = 60_000
            poolName = "PosServer-Embedded-HikariCP"
        }
    }

    /**
     * Resolves the bin directory containing pg_dump, pg_restore, and other PG tools.
     *
     * The zonky library extracts PostgreSQL binaries to an internal directory.
     * This method locates that directory via reflection on the [EmbeddedPostgres] instance.
     */
    private fun resolveBinDirectory() {
        val pg = embeddedPostgres ?: return

        try {
            // The zonky EmbeddedPostgres class stores the binary directory in a private field
            val field = pg.javaClass.getDeclaredField("pgBinDir")
            field.isAccessible = true
            val binDir = field.get(pg) as? File
            if (binDir != null && binDir.isDirectory) {
                cachedBinDirectory = binDir
                logger.info("Resolved embedded PG bin directory: ${binDir.absolutePath}")
                return
            }
        } catch (e: NoSuchFieldException) {
            logger.debug("pgBinDir field not found, trying alternative resolution")
        } catch (e: Exception) {
            logger.debug("Reflection-based bin dir resolution failed: ${e.message}")
        }

        // Fallback: search the system temp directory for extracted PG binaries
        try {
            val tmpDir = File(System.getProperty("java.io.tmpdir"))
            val pgDump =
                tmpDir
                    .walkTopDown()
                    .maxDepth(5)
                    .firstOrNull { it.name == "pg_dump" && it.canExecute() }
            if (pgDump != null) {
                cachedBinDirectory = pgDump.parentFile
                logger.info("Found embedded PG bin directory via search: ${cachedBinDirectory?.absolutePath}")
                return
            }
        } catch (e: Exception) {
            logger.debug("Temp directory search failed: ${e.message}")
        }

        logger.warn("Could not resolve embedded PG bin directory — backup/restore will use system PATH")
    }

    /**
     * Returns the path to the directory containing pg_dump, pg_restore, etc.
     *
     * @return Bin directory path, or null if not resolved (fallback to system PATH)
     */
    fun getBinDirectory(): String? = cachedBinDirectory?.absolutePath

    /**
     * Returns the TCP port of the running embedded PostgreSQL instance.
     */
    fun getPort(): Int = embeddedPostgres?.getPort() ?: port

    /**
     * Checks whether the embedded PostgreSQL process is running.
     */
    fun isRunning(): Boolean = embeddedPostgres != null

    /**
     * Returns the configured database name.
     */
    fun getDatabaseName(): String = databaseName

    /**
     * Returns the configured username.
     */
    fun getUsername(): String = username

    /**
     * Returns the configured password.
     */
    fun getPassword(): String = password

    /**
     * Resolves the PID of the postgres postmaster process via reflection on [EmbeddedPostgres].
     */
    private fun resolvePostmasterPid() {
        val pg = embeddedPostgres ?: return
        try {
            val field = pg.javaClass.getDeclaredField("postmaster")
            field.isAccessible = true
            val process = field.get(pg) as? Process
            if (process != null) {
                postmasterPid = process.pid()
                logger.info("Resolved postgres postmaster PID: $postmasterPid")
                return
            }
        } catch (e: Exception) {
            logger.debug("Could not resolve postmaster PID via reflection: ${e.message}")
        }

        // Fallback: find postgres process by port
        try {
            val handle =
                ProcessHandle
                    .allProcesses()
                    .filter {
                        it
                            .info()
                            .command()
                            .orElse("")
                            .contains("postgres")
                    }.filter {
                        it
                            .info()
                            .arguments()
                            .orElse(emptyArray())
                            .any { arg -> arg == "-p" || arg == port.toString() }
                    }.findFirst()
                    .orElse(null)
            if (handle != null) {
                postmasterPid = handle.pid()
                logger.info("Resolved postgres postmaster PID via process scan: $postmasterPid")
            } else {
                logger.warn("Could not resolve postgres postmaster PID — watchdog will use connection checks")
            }
        } catch (e: Exception) {
            logger.warn("Failed to scan for postgres process: ${e.message}")
        }
    }

    /**
     * Starts a daemon thread that periodically checks whether the postgres process is alive.
     * If the process dies unexpectedly, the JVM is terminated so that systemd can restart
     * the entire service via Restart=on-failure.
     */
    private fun startWatchdog() {
        watchdogRunning = true
        watchdogThread =
            thread(isDaemon = true, name = "pg-watchdog") {
                logger.info("Postgres watchdog started (check interval: ${WATCHDOG_INTERVAL_MS}ms)")

                // Wait before first check to let startup complete
                Thread.sleep(WATCHDOG_INITIAL_DELAY_MS)

                var consecutiveFailures = 0

                while (watchdogRunning) {
                    try {
                        Thread.sleep(WATCHDOG_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }

                    if (!watchdogRunning) break

                    val alive = isPostgresAlive()
                    if (alive) {
                        if (consecutiveFailures > 0) {
                            logger.info("Postgres watchdog: connection recovered after $consecutiveFailures failures")
                        }
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        logger.error("Postgres watchdog: health check failed ($consecutiveFailures/$WATCHDOG_MAX_FAILURES)")

                        if (consecutiveFailures >= WATCHDOG_MAX_FAILURES) {
                            logger.error("Postgres process is dead — terminating JVM for systemd restart")
                            exitProcess(1)
                        }
                    }
                }
            }
    }

    /**
     * Checks if the postgres process is still alive, first by PID then by connection test.
     */
    private fun isPostgresAlive(): Boolean {
        // Fast check: is the process still running?
        val pid = postmasterPid
        if (pid != null) {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle == null || !handle.isAlive) {
                logger.error("Postgres postmaster PID $pid is no longer alive")
                return false
            }
            return true
        }

        // Fallback: try a simple connection
        return try {
            embeddedPostgres?.getPostgresDatabase()?.connection?.use { conn ->
                conn.createStatement().use { it.execute("SELECT 1") }
            }
            true
        } catch (e: Exception) {
            logger.warn("Postgres watchdog connection check failed: ${e.message}")
            false
        }
    }

    /**
     * Stops the embedded PostgreSQL process.
     *
     * Data is preserved in the data directory and will be reused on next [start].
     * This should be called during application shutdown, after closing the connection pool.
     */
    fun stop() {
        watchdogRunning = false
        watchdogThread?.interrupt()
        watchdogThread = null

        try {
            embeddedPostgres?.close()
            embeddedPostgres = null
            logger.info("Embedded PostgreSQL stopped (data preserved in ${resolveDataDirectory().absolutePath})")
        } catch (e: Exception) {
            logger.error("Error stopping embedded PostgreSQL", e)
        }
    }

    companion object {
        /** Delay before first watchdog check (let startup complete) */
        private const val WATCHDOG_INITIAL_DELAY_MS = 30_000L

        /** Interval between watchdog health checks */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /** Consecutive failures before terminating the JVM */
        private const val WATCHDOG_MAX_FAILURES = 3

        /**
         * Singleton reference to the running manager instance.
         *
         * Set by [Application.main] at startup so that other components
         * (e.g., [BackupRestoreServiceImpl]) can access embedded PG config.
         */
        @Volatile
        var instance: EmbeddedDatabaseManager? = null
            private set

        /**
         * Registers the active embedded database manager instance.
         */
        fun setInstance(manager: EmbeddedDatabaseManager) {
            instance = manager
        }

        /**
         * Clears the singleton reference (called during shutdown).
         */
        fun clearInstance() {
            instance = null
        }
    }
}
