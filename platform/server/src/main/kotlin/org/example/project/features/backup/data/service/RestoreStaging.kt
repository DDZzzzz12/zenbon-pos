package org.example.project.features.backup.data.service

import com.zaxxer.hikari.HikariDataSource
import org.example.project.database.FlywayConfig
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Brings a pre-1.1.0 archive up to this version the way an in-place upgrade would, before any of it
 * touches the live database.
 *
 * Restoring a v1.0.10 archive straight into the current schema skips every data step of the
 * migrations after it: orders keep no Z-Bon (the next closing then claims the whole order history),
 * the Kassenbuch Z-Bon aggregates are not flagged (DSFinV-K counts the closing's cash sale a second
 * time), and the stored TSE Admin PIN, PUK and credential seed survive. An in-place upgrade does all
 * of it, so a restore must too.
 *
 * The migrations cannot build the old schema themselves — they start at V33 and assume the tables
 * Exposed created at install time — so the v1.0.10 schema is frozen in [BASE_SCHEMA]. The archive
 * is loaded into a throwaway database built from it, the real V77..Vmax chain runs there, and the
 * migrated tables are handed back as CSVs for the ordinary load into the live database.
 *
 * A separate database, not a schema: some migrations name `public` explicitly, and in a staging
 * schema they would read the live tables instead of the staged ones.
 */
object RestoreStaging {
    private val logger = LoggerFactory.getLogger(RestoreStaging::class.java)

    /** The Flyway version [BASE_SCHEMA] stands at: the last one v1.0.10 shipped. */
    const val BASE_VERSION = 76

    private const val BASE_SCHEMA = "db/restore-base/base_schema_v76.sql"
    private const val NAME_PREFIX = "pos_restore_staging_"

    /**
     * Whether an archive taken at [archiveFlywayVersion] predates the migrations this build runs on
     * upgrade. An unreadable version is not staged: it is written as `"unknown"` only when the
     * source could not read its own history, and guessing a schema for it would be worse.
     */
    fun isNeeded(archiveFlywayVersion: String?): Boolean {
        val major = archiveFlywayVersion?.substringBefore('.')?.toIntOrNull() ?: return false
        return major <= BASE_VERSION
    }

    /**
     * Returns [csvByTable] migrated from [BASE_VERSION] to the newest migration on the classpath.
     * Tables the frozen base does not know are passed through unchanged, so nothing the archive
     * carries is dropped here that the direct load would have kept.
     *
     * Throws if the archive cannot be migrated — the live database has not been touched yet.
     */
    fun migrate(
        live: HikariDataSource,
        csvByTable: Map<String, ByteArray>,
    ): Map<String, ByteArray> {
        val liveUrl = live.jdbcUrl ?: error("the live data source has no JDBC URL to derive a staging database from")
        val name =
            NAME_PREFIX +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val admin = dataSource(liveUrl, live)

        dropAbandoned(admin)
        admin.connection.use { it.createStatement().execute("CREATE DATABASE \"$name\"") }
        logger.info("migrate: staging the archive in database={}", name)
        try {
            val staged = dataSource(withDatabase(liveUrl, name), live)
            val unknown = staged.connection.use { conn -> buildAndLoad(conn, csvByTable) }
            val result = FlywayConfig.migrateFromBaseline(staged, BASE_VERSION.toString())
            logger.info(
                "migrate: archive migrated from V{} to V{} ({} migrations)",
                BASE_VERSION,
                result.targetSchemaVersion,
                result.migrationsExecuted,
            )
            val migrated = staged.connection.use { exportAll(it) }
            if (unknown.isNotEmpty()) {
                logger.warn("migrate: tables outside the v{} base passed through unmigrated={}", BASE_VERSION, unknown)
            }
            return migrated + csvByTable.filterKeys { it in unknown }
        } finally {
            dropQuietly(admin, name)
        }
    }

    /** Builds the frozen base and loads the archive into it; returns the tables the base lacks. */
    private fun buildAndLoad(
        conn: java.sql.Connection,
        csvByTable: Map<String, ByteArray>,
    ): Set<String> {
        conn.autoCommit = false
        conn.createStatement().use { it.execute(baseSchemaSql()) }
        val baseTables = ArchiveTables.discover(conn).toSet()
        val copyManager = CopyManager(conn.unwrap(BaseConnection::class.java))
        // The archive's own rows, loaded in no particular order: foreign keys are checked once, on
        // the live database, after the migrated rows arrive there.
        conn.createStatement().use { it.execute("SET session_replication_role = replica") }
        for ((table, csv) in csvByTable) {
            if (table in baseTables) ArchiveTables.load(conn, copyManager, table, csv)
        }
        conn.createStatement().use { it.execute("SET session_replication_role = origin") }
        // Migrations insert rows of their own (permission groups, cash books, release markers).
        ArchiveTables.resetSequences(conn)
        conn.commit()
        return csvByTable.keys - baseTables
    }

    private fun exportAll(conn: java.sql.Connection): Map<String, ByteArray> {
        val copyManager = CopyManager(conn.unwrap(BaseConnection::class.java))
        return ArchiveTables.discover(conn).associateWith { table ->
            ByteArrayOutputStream()
                .also { out ->
                    copyManager.copyOut("COPY \"$table\" TO STDOUT WITH (FORMAT csv, HEADER true)", out)
                }.toByteArray()
        }
    }

    private fun baseSchemaSql(): String =
        RestoreStaging::class.java.classLoader
            .getResourceAsStream(BASE_SCHEMA)
            ?.use { it.readBytes().decodeToString() }
            ?: error("$BASE_SCHEMA is missing from the classpath")

    /** A staging database left behind by a crashed restore. One still in use is refused and kept. */
    private fun dropAbandoned(admin: PGSimpleDataSource) {
        val leftovers =
            admin.connection.use { conn ->
                conn.prepareStatement("SELECT datname FROM pg_database WHERE datname LIKE ?").use { ps ->
                    ps.setString(1, "$NAME_PREFIX%")
                    ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            }
        for (name in leftovers) {
            runCatching { admin.connection.use { it.createStatement().execute("DROP DATABASE \"$name\"") } }
                .onSuccess { logger.info("dropAbandoned: removed staging database={}", name) }
        }
    }

    private fun dropQuietly(
        admin: PGSimpleDataSource,
        name: String,
    ) {
        runCatching { admin.connection.use { it.createStatement().execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)") } }
            .onFailure { logger.warn("dropQuietly: could not drop staging database={}", name, it) }
    }

    private fun dataSource(
        url: String,
        live: HikariDataSource,
    ): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setUrl(url)
            live.username?.let { user = it }
            live.password?.let { password = it }
        }

    /** [url] pointed at database [name], keeping host, port and parameters. */
    fun withDatabase(
        url: String,
        name: String,
    ): String {
        val match =
            Regex("^(jdbc:postgresql://[^/?]*/)([^?]*)(.*)$").matchEntire(url)
                ?: error("not a PostgreSQL JDBC URL with a database: $url")
        return match.groupValues[1] + name + match.groupValues[3]
    }
}
