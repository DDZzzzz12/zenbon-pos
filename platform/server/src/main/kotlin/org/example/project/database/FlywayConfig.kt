/**
 * Flyway database migration configuration and initialization.
 *
 * Manages database schema migrations using Flyway, ensuring the database schema is always
 * up-to-date before the application starts. Migrations are applied automatically during application
 * startup.
 *
 * Migration files are located in: resources/db/migration/
 *
 * Supported migration types:
 * - Versioned: V{version}__{description}.sql (e.g., V1__add_shop_client_id.sql)
 * - Repeatable: R__{description}.sql (e.g., R__refresh_views.sql)
 * - Callbacks: afterMigrate.sql, beforeMigrate.sql, etc.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.database

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Configuration object for Flyway database migrations.
 *
 * Provides methods to configure and run database migrations before Exposed ORM schema
 * initialization. This ensures that any schema changes are applied in a controlled, versioned
 * manner.
 */
object FlywayConfig {
    private val logger = LoggerFactory.getLogger(FlywayConfig::class.java)

    /** Default migration locations */
    private const val DEFAULT_LOCATION = "classpath:db/migration"

    /** Schema history table name */
    private const val HISTORY_TABLE = "flyway_schema_history"

    /**
     * Creates a base Flyway configuration with common settings.
     *
     * @param dataSource The JDBC data source for database connection
     * @param locations Migration file locations (default: classpath:db/migration)
     * @return FluentConfiguration for further customization
     */
    private fun createBaseConfig(
        dataSource: DataSource,
        vararg locations: String = arrayOf(DEFAULT_LOCATION),
    ): FluentConfiguration =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations(*locations)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .table(HISTORY_TABLE)

    /**
     * Runs database migrations using the provided data source.
     *
     * Configures Flyway with:
     * - baselineOnMigrate: true - Allows running on existing databases
     * - baselineVersion: 0 - Sets baseline for existing tables
     * - validateOnMigrate: true - Validates applied migrations
     *
     * @param dataSource The JDBC data source for database connection
     * @param locations Optional custom migration locations
     * @return MigrateResult containing migration execution details
     */
    fun migrate(
        dataSource: DataSource,
        vararg locations: String = arrayOf(DEFAULT_LOCATION),
    ): MigrateResult {
        logger.info("Starting Flyway database migration...")

        val flyway = createBaseConfig(dataSource, *locations).load()
        val result = flyway.migrate()

        if (result.success) {
            logger.info(
                "Flyway migration completed successfully. " +
                    "Migrations applied: ${result.migrationsExecuted}, " +
                    "Current version: ${result.targetSchemaVersion}",
            )
        } else {
            logger.error("Flyway migration failed: ${result.warnings}")
        }

        return result
    }

    /**
     * Runs every migration above [baselineVersion] against a database whose schema already stands
     * at that version but has no history table — the restore's staging database. Throws if any
     * migration fails, with the migration's own message.
     */
    fun migrateFromBaseline(
        dataSource: DataSource,
        baselineVersion: String,
    ): MigrateResult =
        createBaseConfig(dataSource)
            .baselineVersion(baselineVersion)
            .load()
            .migrate()

    /**
     * Baselines Flyway at the latest available migration version.
     *
     * Used on a **fresh database** where Exposed has already created the full current schema. All
     * existing migration files are marked as "already applied" so they are never executed. Future
     * migrations (V16+, etc.) will run normally.
     *
     * @param dataSource The JDBC data source for database connection
     */
    fun baselineAtLatest(dataSource: DataSource) {
        logger.info("Baselining Flyway at the latest migration version (fresh database)...")

        // Discover the highest migration version from the classpath
        val discoveryFlyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(DEFAULT_LOCATION)
                .load()

        val allMigrations = discoveryFlyway.info().all()
        val latestVersion =
            allMigrations.mapNotNull { it.version }.maxByOrNull {
                it.version.toIntOrNull() ?: 0
            }

        if (latestVersion == null) {
            logger.info("No migration files found — skipping baseline")
            return
        }

        logger.info("Baselining at version: ${latestVersion.version}")

        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(DEFAULT_LOCATION)
                .baselineVersion(latestVersion.version)
                .table(HISTORY_TABLE)
                .load()

        flyway.baseline()
        logger.info("Flyway baseline set to version ${latestVersion.version}")
    }

    /**
     * Runs migrations with placeholder substitution.
     *
     * Allows using variables like ${schema_name} in migration files.
     *
     * @param dataSource The JDBC data source for database connection
     * @param placeholders Map of placeholder names to values
     * @return MigrateResult containing migration execution details
     */
    fun migrateWithPlaceholders(
        dataSource: DataSource,
        placeholders: Map<String, String>,
    ): MigrateResult {
        logger.info("Starting Flyway migration with placeholders: ${placeholders.keys}")

        val flyway =
            createBaseConfig(dataSource)
                .placeholderReplacement(true)
                .placeholders(placeholders)
                .load()

        return flyway.migrate()
    }

    /**
     * Runs migrations allowing out-of-order execution.
     *
     * Useful when multiple developers work on migrations simultaneously and version numbers might
     * not be strictly sequential.
     *
     * @param dataSource The JDBC data source for database connection
     * @return MigrateResult containing migration execution details
     */
    fun migrateOutOfOrder(dataSource: DataSource): MigrateResult {
        logger.info("Starting Flyway migration (out-of-order allowed)...")

        val flyway = createBaseConfig(dataSource).outOfOrder(true).load()

        return flyway.migrate()
    }

    /**
     * Gets information about pending and applied migrations.
     *
     * Useful for debugging and monitoring the migration state.
     *
     * @param dataSource The JDBC data source for database connection
     */
    fun info(dataSource: DataSource) {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()

        val info = flyway.info()

        logger.info("=== Flyway Migration Status ===")
        logger.info("Current version: ${info.current()?.version}")
        logger.info("Pending migrations: ${info.pending().size}")

        info.all().forEach { migration ->
            logger.info(
                "  ${migration.version} - ${migration.description} " +
                    "[${migration.state}] - ${migration.installedOn ?: "pending"}",
            )
        }
    }

    /**
     * Validates that all migrations have been applied correctly.
     *
     * Checks that:
     * - All applied migrations match their checksums
     * - No migrations have been modified after being applied
     *
     * @param dataSource The JDBC data source for database connection
     * @throws org.flywaydb.core.api.FlywayException if validation fails
     */
    fun validate(dataSource: DataSource) {
        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()

        flyway.validate()
        logger.info("Flyway migration validation successful")
    }

    /**
     * Repairs the Flyway schema history table.
     *
     * Use this when:
     * - A migration failed and left the schema history in a bad state
     * - You need to fix checksum mismatches after intentional changes
     *
     * @param dataSource The JDBC data source for database connection
     */
    fun repair(dataSource: DataSource) {
        logger.warn("Repairing Flyway schema history...")

        val flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()

        flyway.repair()
        logger.info("Flyway schema history repaired")
    }

    /**
     * Checks if there are pending migrations that need to be applied.
     *
     * Useful for health checks or startup validation.
     *
     * @param dataSource The JDBC data source for database connection
     * @return true if there are pending migrations, false otherwise
     */
    fun hasPendingMigrations(dataSource: DataSource): Boolean {
        val flyway = createBaseConfig(dataSource).load()
        return flyway.info().pending().isNotEmpty()
    }

    /**
     * Gets a summary of migration status.
     *
     * @param dataSource The JDBC data source for database connection
     * @return MigrationStatus containing counts and current version
     */
    fun getStatus(dataSource: DataSource): MigrationStatus {
        val flyway = createBaseConfig(dataSource).load()
        val info = flyway.info()

        return MigrationStatus(
            currentVersion = info.current()?.version?.version,
            pendingCount = info.pending().size,
            appliedCount = info.applied().size,
            totalCount = info.all().size,
        )
    }

    /**
     * Data class representing migration status summary.
     *
     * @property currentVersion The current applied migration version
     * @property pendingCount Number of migrations waiting to be applied
     * @property appliedCount Number of successfully applied migrations
     * @property totalCount Total number of migrations (applied + pending)
     */
    data class MigrationStatus(
        val currentVersion: String?,
        val pendingCount: Int,
        val appliedCount: Int,
        val totalCount: Int,
    ) {
        /** Returns true if all migrations have been applied */
        val isUpToDate: Boolean
            get() = pendingCount == 0
    }
}
