package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.security.JwtConfig
import org.example.project.database.DatabaseMode
import org.example.project.database.EmbeddedDatabaseManager
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.auth.data.ApprovalRequestExpirySweeper
import org.example.project.features.auth.domain.service.RefreshTokenService
import org.example.project.features.cardTerminal.domain.service.CardTerminalService
import org.example.project.features.maintenance.MaintainerPasswordHashCli
import org.example.project.features.maintenance.MaintenancePolicy
import org.example.project.features.scheduler.data.service.SchedulerEngine
import org.example.project.features.tse.data.provider.TseSdkAvailability
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.data.service.TsePendingSweeper
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.session.TseAdminSessionReaper
import org.example.project.features.update.data.service.UpdateBootReconciler
import org.example.project.features.update.domain.repository.UpdateSettingsRepository
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.infrastructure.plugins.assertSecureAuthConfig
import org.example.project.infrastructure.plugins.initDB
import org.example.project.infrastructure.server.ServerConfig
import org.example.project.infrastructure.server.ServerManager
import org.example.project.infrastructure.server.plainHttpExposureWarning
import org.example.project.utils.OsUtils
import org.example.project.utils.config.YamlConfigLoader
import org.example.project.utils.dbQuery
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.getValue
import kotlin.system.exitProcess

/**
 * Main application entry point and module configuration.
 *
 * Sets up the Ktor server with database initialization, routing, and middleware.
 * Handles application lifecycle including database connection management and
 * graceful shutdown procedures.
 *
 * Supports two database modes:
 * - **EMBEDDED**: Starts a bundled PostgreSQL instance (no external installation required)
 * - **EXTERNAL**: Connects to a separately managed PostgreSQL server
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Initializes the log directory system property before Logback is loaded.
 * Must be referenced before any LoggerFactory.getLogger() call.
 */
private object LogDirectoryInitializer {
    init {
        val logDir =
            OsUtils.resolveLogDirectory(
                System.getenv("LOG_DIR") ?: System.getProperty("LOG_DIR"),
                OsUtils.getAppDataDirectory(),
            )
        java.io.File(logDir).mkdirs()
        System.setProperty("LOG_DIR", logDir)
    }
}

@Suppress("unused")
private val logDirInit = LogDirectoryInitializer

val logger = LoggerFactory.getLogger("Application")

/**
 * Application entry point.
 *
 * Initializes and starts the embedded Netty server using ServerManager
 * with YAML configuration loading and error handling. Sets up graceful
 * shutdown hooks to ensure database connections are properly closed.
 *
 * Includes interactive terminal setup wizard for first-time initialization.
 */
fun main(args: Array<String>) {
    // `--hash-maintainer-password` only prints a hash. It must not start the server, open the
    // database or read any other configuration.
    if (MaintainerPasswordHashCli.FLAG in args) exitProcess(MaintainerPasswordHashCli.runInteractive())

    try {
        logger.info("Starting Kotlin Project Server...")

        val config = YamlConfigLoader.load()

        // Fail fast, BEFORE the database is touched (checklist 1.10): a server that refuses to boot
        // over its JWT configuration must not first have run Flyway against the database. The guard
        // reads only configuration (JwtConfig + environment), so it needs neither the database nor
        // Koin. `Application.module` still calls it too, for the embedded/test entry points.
        assertSecureAuthConfig()

        // Fail fast on a malformed MAINTAINER_PASSWORD_HASH, before the database is touched.
        MaintenancePolicy.logStartupState()

        // Determine database mode from configuration
        val databaseModeStr = config.getString("database.mode").uppercase()
        val databaseMode =
            try {
                DatabaseMode.valueOf(databaseModeStr)
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid database.mode value: '$databaseModeStr'. Must be 'embedded' or 'external'.")
                exitProcess(1)
            }

        logger.info("Database mode: $databaseMode")

        when (databaseMode) {
            DatabaseMode.EMBEDDED -> {
                // Start bundled PostgreSQL — no external installation required
                val embeddedConfig = config.getConfig("database.embedded")
                val embeddedManager =
                    EmbeddedDatabaseManager(
                        port = embeddedConfig.getInt("port"),
                        dataDirectory = embeddedConfig.getString("dataDirectory"),
                        databaseName = embeddedConfig.getString("databaseName"),
                        username = embeddedConfig.getString("username"),
                        password = embeddedConfig.getString("password"),
                    )

                embeddedManager.start()
                EmbeddedDatabaseManager.setInstance(embeddedManager)

                // Initialize database connection via embedded PG
                logger.info("Initializing database connection (embedded)...")
                initDB(mode = DatabaseMode.EMBEDDED, embeddedManager = embeddedManager)
                logger.info("Database initialization completed successfully")
            }

            DatabaseMode.EXTERNAL -> {
                // External PostgreSQL is operator-managed. The server connects to whatever
                // is configured at database.external.url and fails loudly if unreachable.
                // Operators are expected to provision the database/user (manually or via
                // their preferred container/orchestrator) before starting the server.
                logger.info("Initializing database connection (external)...")
                initDB(mode = DatabaseMode.EXTERNAL)
                logger.info("Database initialization completed successfully")
            }
        }

        // Initialize dependency injection if not already started
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(serializationModule, reposModule, servicesModule, httpClientModule)
            }
            logger.info("Dependency injection initialized")
        } else {
            logger.info("Dependency injection already initialized, skipping")
        }

        // Settle the outcome of an update that swapped the jar and exited. The reconciler is the
        // only place the server can learn it was rolled back — restart-guard.sh does that in shell
        // and tells the application nothing. It never throws, but the call is guarded anyway: a
        // bookkeeping step must not be able to stop the boot.
        runBlocking {
            runCatching { updateBootReconciler().reconcile() }
                .onFailure { logger.warn("Update boot reconciliation skipped", it) }
        }

        // Start the application-wide coroutine scope used for background jobs
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Periodically purge expired refresh tokens from the database (hourly).
        // The coroutine is tied to applicationScope and is cancelled on shutdown.
        val refreshTokenService = GlobalContext.get().get<RefreshTokenService>()
        applicationScope.launch {
            while (isActive) {
                delay(60 * 60 * 1000L) // hourly
                runCatching { refreshTokenService.cleanupExpired() }
                    .onFailure { logger.warn("Refresh token cleanup failed", it) }
            }
        }
        logger.info("Refresh token cleanup job started")

        // Tell a till when nobody answered its approval request. The window is three minutes, so
        // the tick is short enough that the cashier learns within about a window's tolerance rather
        // than never (E2E-2026-09-20 #8). Cancelled with applicationScope on shutdown.
        applicationScope.launch {
            while (isActive) {
                delay(30 * 1000L)
                runCatching { ApprovalRequestExpirySweeper.sweep() }
                    .onFailure { logger.warn("Approval request expiry sweep failed", it) }
            }
        }
        logger.info("Approval request expiry sweeper started")

        // Get port and host from YAML configuration
        val host = config.getString("ktor.deployment.host")
        val port =
            runBlocking {
                ShopConfigurationManager.getServerPort()
            }

        logger.info("Server will start on $host:$port")
        plainHttpExposureWarning(host, JwtConfig.isDevelopmentMode)?.let { logger.error(it) }

        // Create server configuration
        val serverConfig =
            ServerConfig(
                host = host,
                port = port,
                enableLogging = true,
            )

        // Add shutdown hook to gracefully close database connections and embedded PG
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutdown hook triggered, stopping server...")
                TseSigningRetryService.getInstance()?.shutdown()
                TsePendingSweeper.getInstance()?.shutdown()
                GlobalContext.getOrNull()?.get<SchedulerEngine>()?.shutdown()
                ServerManager.stopServer()

                // Stop embedded PostgreSQL after the server and connection pool are closed
                val embeddedManager = EmbeddedDatabaseManager.instance
                if (embeddedManager != null) {
                    logger.info("Stopping embedded PostgreSQL...")
                    embeddedManager.stop()
                    EmbeddedDatabaseManager.clearInstance()
                }

                logger.info("Server stopped successfully")
            },
        )

        // Start server using ServerManager
        val started = ServerManager.startServer(serverConfig)

        // Auto-connect card terminals (non-blocking)
        val cardTerminalService = GlobalContext.get().get<CardTerminalService>()
        applicationScope.launch {
            try {
                cardTerminalService.initializeOnStartup()
            } catch (e: Exception) {
                logger.warn("Card terminal auto-connect failed", e)
            }
        }

        // Reap lapsed TSE admin sittings: the operator sitting is a server-side concept,
        // independent of the device's own state.
        run {
            val provider by inject<TseAdminProvider>(TseAdminProvider::class.java)
            TseAdminSessionReaper(
                scope = applicationScope,
                logoutAdmin = { provider.logoutAdmin() },
            ).start()
        }

        // LocalTseLifecycleManager holds a TseStatus, which is typed on a Swissbit class.
        // Resolving it without the SDK throws NoClassDefFoundError and kills the process
        // even though the server is already serving, so skip local hardware bring-up
        // entirely. Signing then runs through UnavailableTseSignProvider and degrades via
        // the configured TseFailureBehavior.
        if (!TseSdkAvailability.isPresent()) {
            logger.error(
                "The TSE SDK is missing — skipping TSE hardware initialization. " +
                    "Install the Swissbit WormAPI.jar at native/WormAPI.jar next to pos-server.jar and restart.",
            )
        } else {
            val manager by inject<LocalTseLifecycleManager>(LocalTseLifecycleManager::class.java)
            // Resolves the register the driver works for itself (TseDriverClient). Without it
            // TseStatus.clientId stays null, and on TSE 1.x the self test has nothing to run against.
            manager.initialize()
        }

        if (!started) {
            logger.error("Failed to start server")
            exitProcess(1)
        }

        // Keep the application running
        logger.info("Server started successfully, waiting...")
        Thread.currentThread().join()
    } catch (e: Exception) {
        logger.error("Failed to start server", e)
        exitProcess(1)
    }
}

/**
 * Builds the boot reconciler against the real install directory and the version actually running.
 *
 * The install directory is where `pos-server.jar` lives, so it is derived from the location of the
 * running code rather than from the working directory — systemd sets `WorkingDirectory` to the
 * install dir today, but a manual `java -jar` from elsewhere must still find its own marker.
 */
private fun updateBootReconciler(): UpdateBootReconciler =
    UpdateBootReconciler(
        installDir = resolveInstallDir(),
        runningVersion = BuildConfig.APP_VERSION,
        recordAudit = ::recordUpdateAudit,
        recordStatus = { GlobalContext.get().get<UpdateSettingsRepository>().recordCheck(it) },
    )

internal fun resolveInstallDir(): File {
    val codeSource =
        runCatching {
            File(
                UpdateBootReconciler::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        }.getOrNull()
    return codeSource?.takeIf { it.isFile }?.parentFile ?: File(System.getProperty("user.dir"))
}

/**
 * Writes one audit row for an update outcome. Nothing here reads or logs the GitHub token — the
 * marker never carried it and this path must never reach for it.
 */
private suspend fun recordUpdateAudit(
    operation: String,
    detail: String,
) {
    val now = Instant.now().toString()
    dbQuery {
        TransactionAuditLog.new {
            this.transactionId = "update-${UUID.randomUUID()}"
            this.employeeId = "SYSTEM"
            this.employeeNumber = "SYSTEM"
            this.authorizationType = "SYSTEM"
            this.operation = operation
            this.entityType = "SOFTWARE_UPDATE"
            this.entityId = BuildConfig.APP_VERSION
            this.changesSummary = detail
            this.timestamp = now
            this.createdAt = now
        }
        Unit
    }
}
