/**
 * Enum defining the database connection mode for the POS server.
 *
 * Determines whether the application uses a bundled embedded PostgreSQL instance
 * or connects to an externally managed PostgreSQL server.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.database

/**
 * Database connection mode.
 *
 * - [EMBEDDED]: Starts a bundled PostgreSQL instance with persistent data directory.
 *   No external database installation required. Ideal for single-machine desktop deployments.
 * - [EXTERNAL]: Connects to an externally managed PostgreSQL server via JDBC URL.
 *   Use for multi-machine setups or when a DBA manages the database separately.
 */
enum class DatabaseMode {
    /**
     * Bundled PostgreSQL — the application manages its own database process.
     * Data is persisted in a configurable directory (default: ~/POS Server/data/postgresql/).
     */
    EMBEDDED,

    /**
     * External PostgreSQL — connects to a separately installed/managed database.
     * Requires the `database.postgresql` YAML configuration section.
     */
    EXTERNAL,
}
