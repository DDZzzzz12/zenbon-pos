/**
 * Server lifecycle management for embedded Ktor server.
 *
 * Provides start, stop, and pause functionality for the embedded server
 * with proper state management and error handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.infrastructure.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.example.project.core.config.DataDirectoryRebaseStep
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.database.DatabaseFactory
import org.example.project.features.maintenance.MaintenancePolicy
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.zBon.domain.KassenSwVersionSync
import org.example.project.infrastructure.plugins.*
import org.example.project.infrastructure.plugins.configureAccessControl
import org.example.project.infrastructure.plugins.configureStatusPages
import org.example.project.utils.dbQuery
import org.koin.core.context.GlobalContext
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory

/**
 * Server state enumeration.
 */
enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    ERROR,
}

/**
 * Server configuration data class.
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val enableLogging: Boolean = true,
)

private val logger = LoggerFactory.getLogger("ServerManager")

/**
 * Manages the lifecycle of the embedded Ktor server.
 *
 * Singleton object that controls server startup, shutdown, and state management.
 * Provides observable state flow for UI integration.
 */
object ServerManager {
    private val logger = LoggerFactory.getLogger(ServerManager::class.java)

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var currentConfig: ServerConfig? = null

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Starts the embedded server with the given configuration.
     *
     * @param config Server configuration parameters
     * @return true if server started successfully, false otherwise
     */
    fun startServer(config: ServerConfig): Boolean {
        if (_serverState.value == ServerState.RUNNING) {
            logger.warn("Server is already running")
            _errorMessage.value = "Server is already running"
            return false
        }

        return try {
            _serverState.value = ServerState.STARTING
            _errorMessage.value = null
            currentConfig = config

            logger.info("Starting embedded server on ${config.host}:${config.port}")

            // Initialize database if not already initialized
            try {
                initDB()
                // Initialize shop configuration manager for cached access
                val configInitialized = runBlocking { ShopConfigurationManager.initialize() }
                if (configInitialized) {
                    logger.info("Shop configuration manager initialized successfully")
                } else {
                    logger.warn("Shop configuration manager initialization failed - using defaults")
                }
                logger.info("Database initialized successfully")

                // Auto-update kasseSwVersion in KASSEN_MELDUNG_CONFIG for all shops
                try {
                    runBlocking {
                        updateKassenSwVersion()
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to auto-update kasseSwVersion in KASSEN_MELDUNG_CONFIG", e)
                }

                // Finish a --migrate-data-dir: rebase the stored absolute paths (no-op without the marker)
                try {
                    runBlocking {
                        rebaseDataDirectoryPaths()
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to rebase stored paths after a data-directory migration", e)
                }
            } catch (e: Exception) {
                logger.warn("Database already initialized or initialization failed", e)
            }

            // Create and configure server
            server =
                embeddedServer(
                    Netty,
                    port = config.port,
                    host = config.host,
                    module = { module(config.enableLogging) },
                )

            // Start server in a separate thread to avoid blocking
            Thread {
                try {
                    server?.start(wait = false)
                    _serverState.value = ServerState.RUNNING
                    logger.info("Server started successfully on ${config.host}:${config.port}")
                } catch (e: Exception) {
                    logger.error("Failed to start server", e)
                    _serverState.value = ServerState.ERROR
                    _errorMessage.value = "Failed to start server: ${e.message}"
                }
            }.start()

            // Wait for server to start with retry logic (max 5 seconds)
            var attempts = 0
            val maxAttempts = 10
            while (attempts < maxAttempts && _serverState.value == ServerState.STARTING) {
                Thread.sleep(500)
                attempts++
            }

            // Check final state
            val isRunning = _serverState.value == ServerState.RUNNING
            if (!isRunning && _serverState.value == ServerState.STARTING) {
                // Timeout - server took too long to start
                logger.error("Server startup timeout after ${maxAttempts * 500}ms")
                _serverState.value = ServerState.ERROR
                _errorMessage.value = "Server startup timeout"
            }

            isRunning
        } catch (e: Exception) {
            logger.error("Error starting server", e)
            _serverState.value = ServerState.ERROR
            _errorMessage.value = "Error starting server: ${e.message}"
            false
        }
    }

    /**
     * Stops the running server.
     *
     * @return true if server stopped successfully, false otherwise
     */
    fun stopServer(serverOnly: Boolean = false): Boolean {
        if (_serverState.value != ServerState.RUNNING && _serverState.value != ServerState.PAUSED) {
            logger.warn("Server is not running")
            _errorMessage.value = "Server is not running"
            return false
        }

        return try {
            _serverState.value = ServerState.STOPPING
            _errorMessage.value = null

            logger.info("Stopping embedded server...")

            server?.stop(1000, 5000)
            server = null

            if (!serverOnly) {
                DatabaseFactory.close()
                logger.info("Database connections closed")
            }

            _serverState.value = ServerState.STOPPED
            logger.info("Server stopped successfully")
            true
        } catch (e: Exception) {
            logger.error("Error stopping server", e)
            _serverState.value = ServerState.ERROR
            _errorMessage.value = "Error stopping server: ${e.message}"
            false
        }
    }

    /**
     * Pauses the server (stops accepting new requests but keeps connections alive).
     *
     * Note: This is a simplified implementation. Full pause/resume would require
     * more sophisticated request handling.
     *
     * @return true if server paused successfully, false otherwise
     */
    fun pauseServer(): Boolean {
        if (_serverState.value != ServerState.RUNNING) {
            logger.warn("Server is not running, cannot pause")
            _errorMessage.value = "Server is not running"
            return false
        }

        return try {
            _errorMessage.value = null
            _serverState.value = ServerState.PAUSED
            logger.info("Server paused")
            true
        } catch (e: Exception) {
            logger.error("Error pausing server", e)
            _serverState.value = ServerState.ERROR
            _errorMessage.value = "Error pausing server: ${e.message}"
            false
        }
    }

    /**
     * Resumes a paused server.
     *
     * @return true if server resumed successfully, false otherwise
     */
    fun resumeServer(): Boolean {
        if (_serverState.value != ServerState.PAUSED) {
            logger.warn("Server is not paused, cannot resume")
            _errorMessage.value = "Server is not paused"
            return false
        }

        return try {
            _errorMessage.value = null
            _serverState.value = ServerState.RUNNING
            logger.info("Server resumed")
            true
        } catch (e: Exception) {
            logger.error("Error resuming server", e)
            _serverState.value = ServerState.ERROR
            _errorMessage.value = "Error resuming server: ${e.message}"
            false
        }
    }

    /**
     * Restarts the server with the current or new configuration.
     *
     * @param config Optional new configuration, uses current if not provided
     * @return true if server restarted successfully, false otherwise
     */
    fun restartServer(config: ServerConfig? = null): Boolean {
        val restartConfig = config ?: currentConfig ?: ServerConfig()

        logger.info("Restarting server...")
        stopServer()
        Thread.sleep(2000) // Wait for complete shutdown
        return startServer(restartConfig)
    }

    /**
     * Gets the current server configuration.
     */
    fun getCurrentConfig(): ServerConfig? = currentConfig

    /**
     * Clears any error messages.
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /** Rebases `DOCUMENT_STORAGE_PATH`, `BACKUP_PATH` and `DATEV_EXPORT_PATH` after `--migrate-data-dir`. */
    private suspend fun rebaseDataDirectoryPaths() {
        val configRepo by inject<ConfigurationRepository>(ConfigurationRepository::class.java)
        val shopId = 1L
        val changed =
            DataDirectoryRebaseStep(
                readPath = { setting ->
                    val json = dbQuery { configRepo.getSetting(shopId, RestoredPathPolicy.configKeyOf(setting).key) } as? JsonObject
                    (json?.get(RestoredPathPolicy.fieldOf(setting)) as? JsonPrimitive)?.contentOrNull
                },
                writePath = { setting, value ->
                    val key = RestoredPathPolicy.configKeyOf(setting)
                    dbQuery {
                        val existing = configRepo.getSetting(shopId, key.key) as? JsonObject ?: JsonObject(emptyMap())
                        configRepo.setSetting(shopId, key, JsonObject(existing + (RestoredPathPolicy.fieldOf(setting) to JsonPrimitive(value))))
                    }
                },
            ).runIfMarked()
        if (changed > 0) ShopConfigurationManager.refreshConfiguration()
    }

    /**
     * Updates kasseSwVersion in KASSEN_MELDUNG_CONFIG for all shops on server startup — see
     * [KassenSwVersionSync], which a backup restore into the running server also calls.
     */
    private suspend fun updateKassenSwVersion() {
        val configRepo by inject<ConfigurationRepository>(ConfigurationRepository::class.java)
        KassenSwVersionSync(configRepo).syncToRunningBinary()
    }
}

/**
 * Main application module configuration.
 *
 * Sets up database connections, configures routing, and initializes all
 * application components. Called by Ktor during server startup.
 * Uses PostgreSQL database for all environments.
 *
 * @receiver Application instance for configuration
 */
fun Application.module(enabled: Boolean) {
    logger.info("Starting application with PostgreSQL database")

    // Initialize dependency injection for Ktor server
    // Note: Only configure Koin plugin if running standalone (not embedded in desktop app)
    // When embedded, the global Koin instance is already available to Ktor routes
    val existingKoin = GlobalContext.getOrNull()
    if (existingKoin == null) {
        logger.info("Standalone server mode: configuring Koin plugin")
        configureKoin()
    } else {
        logger.info("Embedded server mode: using existing global Koin context")
    }

    // Forwarded headers go first: they fix call.request.origin (client IP/scheme, taken from
    // X-Forwarded-* only when the TCP peer is loopback, i.e. Caddy) before anything reads it,
    // e.g. PairAttemptLimiter keying on remoteHost.
    configureForwardedHeaders()

    // CallId next, before CallLogging and StatusPages, so CallLogging.callIdMdc and
    // StatusPages see the populated traceId in MDC.
    configureCallId()

    if (enabled) {
        // Configure HTTP call logging
        configureCallLogging()
    }

    // Configure content negotiation
    configureContentNegotiation()
    install(AdminPanelSecurityHeaders)
    // Answers If-None-Match with 304. Only responses carrying an ETag are affected (the browser apps'
    // static files, see StaticResourceValidators). Outside configureHTTP() so tests exercise it too.
    install(ConditionalHeaders)

    // HTTP compression — skip in test mode (enabled=false) to avoid Content-Length mismatch in Ktor test engine
    if (enabled) {
        configureHTTP()
    }

    // Configure security and authentication
    configureSecurity()
    if (enabled) {
        assertSecureAuthConfig()
        MaintenancePolicy.logStartupState()
    }

    // Configure Status Pages
    configureStatusPages()

    // Configure access control (must be after security)
    configureAccessControl()

    // Initialize database connection (idempotent - safe to call multiple times)
    initDB()

    // Configure routing
    configureRouting()

    logger.info("Application module initialized successfully")
}
