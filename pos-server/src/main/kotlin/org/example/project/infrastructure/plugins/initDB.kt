package org.example.project.infrastructure.plugins

import com.zaxxer.hikari.HikariConfig
import org.example.project.core.config.TimezoneConfig
import org.example.project.database.DatabaseFactory
import org.example.project.database.DatabaseMode
import org.example.project.database.EmbeddedDatabaseManager
import org.example.project.database.FlywayConfig
import org.example.project.database.SchemaManager
import org.example.project.utils.config.YamlConfigLoader
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application.initDB")

/**
 * Initializes the database connection using YAML configuration with Typesafe Config.
 *
 * Handles two scenarios:
 * - **Fresh database** (no flyway_schema_history): Creates the full schema via Exposed,
 *   then baselines Flyway at the latest version so old migrations are skipped.
 * - **Existing database**: Runs Flyway migrations incrementally, then ensures any
 *   new Exposed-defined tables/columns are also present.
 *
 * This ensures both fresh installs (embedded PG first start) and incremental upgrades
 * (external PG with prior data) work correctly.
 */
fun initDB(
    dataSource: HikariConfig = createDataSource(),
    runMigrations: Boolean = true,
) {
    try {
        if (!DatabaseFactory.isInitialized()) {
            // Step 1: Connect without running Flyway
            DatabaseFactory.init(dataSource, runMigrations = false)
            logger.info("Database connection initialized successfully")

            val ds = DatabaseFactory.getDataSource()
            val isFreshDatabase = !hasFlywayHistory(ds)

            if (isFreshDatabase) {
                // Fresh database: Exposed creates the full current schema, then
                // baseline Flyway so old ALTER TABLE migrations are never executed.
                logger.info("Fresh database detected — creating schema via Exposed and baselining Flyway")
                SchemaManager.createTablesAndBootstrap()

                if (runMigrations) {
                    FlywayConfig.baselineAtLatest(ds)
                }
            } else {
                // Existing database: run Flyway to apply incremental migrations,
                // then let Exposed add any newly defined tables/columns.
                if (runMigrations) {
                    logger.info("Running Flyway migrations on existing database...")
                    FlywayConfig.migrate(ds)
                }
                SchemaManager.createTablesAndBootstrap()
            }

            // Register TimezoneConfig as the global TimeProvider so that
            // platform/server-common's currentTimeStamp() uses the shop-configured timezone
            TimezoneConfig.registerAsTimeProvider()

            logger.info("Database schema initialization completed")
        } else {
            // Deliberate: repeated calls during startup must not rebuild the schema. Logged
            // because the skip is otherwise invisible — a caller handing in a *different*
            // datasource gets silence and an unschema'd database, and the failure surfaces
            // wherever that database is first read rather than here.
            logger.warn(
                "initDB: DatabaseFactory already initialized — skipping connection and schema setup. " +
                    "The supplied datasource is NOT used. Close the existing one first if you meant to switch.",
            )
        }
    } catch (e: Exception) {
        logger.error("Failed to initialize database", e)
        throw e
    }
}

/**
 * Checks whether the Flyway schema history table exists in the database.
 *
 * Used to distinguish a completely fresh database (no prior migrations)
 * from one that has been managed by Flyway before.
 */
private fun hasFlywayHistory(dataSource: javax.sql.DataSource): Boolean =
    try {
        dataSource.connection.use { conn ->
            val rs = conn.metaData.getTables(null, "public", "flyway_schema_history", arrayOf("TABLE"))
            rs.next()
        }
    } catch (e: Exception) {
        logger.debug("Could not check for Flyway history table: ${e.message}")
        false
    }

/**
 * Mode-aware database initialization.
 *
 * - [DatabaseMode.EMBEDDED]: Uses [embeddedManager] to create a HikariConfig pointing
 *   at the embedded PostgreSQL instance. Pool settings are loaded from YAML.
 * - [DatabaseMode.EXTERNAL]: Delegates to the existing [createDataSource] flow
 *   that reads the full JDBC URL from YAML configuration.
 *
 * @param mode The database connection mode (EMBEDDED or EXTERNAL)
 * @param embeddedManager The running embedded PG manager (required when mode is EMBEDDED)
 * @param runMigrations Whether to run Flyway migrations (default: true)
 */
fun initDB(
    mode: DatabaseMode,
    embeddedManager: EmbeddedDatabaseManager? = null,
    runMigrations: Boolean = true,
) {
    val hikariConfig =
        when (mode) {
            DatabaseMode.EMBEDDED -> {
                requireNotNull(embeddedManager) { "EmbeddedDatabaseManager is required for EMBEDDED mode" }
                logger.info("Initializing database in EMBEDDED mode (port: ${embeddedManager.getPort()})")
                val poolConfig = createPoolConfig()
                embeddedManager.createHikariConfig(poolConfig)
            }
            DatabaseMode.EXTERNAL -> {
                logger.info("Initializing database in EXTERNAL mode")
                createDataSource()
            }
        }

    initDB(hikariConfig, runMigrations)
}

/**
 * Creates a full HikariConfig for external PostgreSQL connections.
 *
 * Reads JDBC URL, credentials, and pool settings from the `database.postgresql`
 * section of application.yaml.
 */
fun createDataSource(): HikariConfig {
    // Load configuration from application.yaml file using Typesafe Config
    logger.info("Initializing database connection from YAML configuration")
    val config = YamlConfigLoader.load().getConfig("database.postgresql")

    val hikariConfig =
        HikariConfig().apply {
            jdbcUrl = config.getString("url")
            username = config.getString("username")
            password =
                if (System.getenv("DB_PASSWORD") != null) {
                    System.getenv("DB_PASSWORD")
                } else {
                    config.getString("password")
                }
            driverClassName = config.getString("driver")

            // Connection pool settings for optimal performance
            maximumPoolSize = config.getInt("maximumPoolSize")
            minimumIdle = 5
            connectionTimeout = config.getLong("connectionTimeout")
            idleTimeout = config.getLong("idleTimeout")
            maxLifetime = config.getLong("maxLifetime")

            // Additional HikariCP optimizations
            isAutoCommit = config.getBoolean("isAutoCommit")
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Connection validation and health checks
            validationTimeout = 3000
            leakDetectionThreshold = 60000

            // Pool name for monitoring and debugging
            poolName = "PosServer-HikariCP"
        }

    return hikariConfig
}

/**
 * Creates a HikariConfig with only pool settings (no JDBC URL or credentials).
 *
 * Used by [EmbeddedDatabaseManager.createHikariConfig] to apply the same pool tuning
 * (timeouts, pool size) as external mode, while the manager provides its own JDBC URL.
 *
 * @return HikariConfig populated with pool settings from `database.postgresql` YAML section
 */
fun createPoolConfig(): HikariConfig {
    val config = YamlConfigLoader.load().getConfig("database.postgresql")

    return HikariConfig().apply {
        maximumPoolSize = config.getInt("maximumPoolSize")
        connectionTimeout = config.getLong("connectionTimeout")
        idleTimeout = config.getLong("idleTimeout")
        maxLifetime = config.getLong("maxLifetime")
        isAutoCommit = config.getBoolean("isAutoCommit")
    }
}
