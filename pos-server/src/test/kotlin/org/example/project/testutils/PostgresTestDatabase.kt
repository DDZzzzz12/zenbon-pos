package org.example.project.testutils

import com.zaxxer.hikari.HikariConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.example.project.database.DatabaseFactory
import java.io.Closeable
import javax.sql.DataSource

/**
 * Reusable embedded-PostgreSQL harness for `@Tag("postgres")` tests.
 *
 * Starts a zonky [EmbeddedPostgres] instance — the same library the production
 * [org.example.project.database.EmbeddedDatabaseManager] uses — but tuned for tests:
 *
 * - **Ephemeral port.** [EmbeddedPostgres.builder] is started *without* `setPort(...)`, so zonky
 *   binds a random free port. This is deliberate: the app's embedded PG uses a fixed 5435 and a
 *   dev server may be listening there, so a hard-coded port would collide. Read the actual port
 *   from [port] after start if you need it.
 * - **One instance per test CLASS, not per method.** Startup is a few seconds (zonky extracts and
 *   `initdb`s a real PostgreSQL), so callers should start it in `@BeforeAll` / a companion and
 *   [close] it in `@AfterAll`. JUnit `@TestInstance(PER_CLASS)` makes non-static `@BeforeAll`
 *   convenient.
 *
 * Binary/platform note: the linux-amd64 zonky binary covers CI; the darwin-amd64 binary runs under
 * Rosetta on Apple Silicon (there is no arm64 alias). Both are declared as `testRuntimeOnly` deps
 * so zonky can pick the right one for the host.
 */
class PostgresTestDatabase private constructor(
    private val pg: EmbeddedPostgres,
) : Closeable {
    /**
     * A [DataSource] for the default `postgres` database (superuser `postgres`). Handed straight to
     * Flyway and to raw-JDBC seeding in the migration tests. It is a real connection pool managed by
     * zonky — do not close individual connections' underlying pool; [close] tears everything down.
     */
    val dataSource: DataSource get() = pg.postgresDatabase

    /** The ephemeral TCP port zonky bound. */
    val port: Int get() = pg.port

    /** JDBC URL for the default `postgres` database. */
    val jdbcUrl: String get() = pg.getJdbcUrl("postgres", "postgres")

    /**
     * A fresh [HikariConfig] pointing at this embedded instance, for callers that want to drive it
     * through the app's [org.example.project.database.DatabaseFactory] rather than the raw
     * [dataSource]. Small pool — these are single-class tests.
     */
    fun hikariConfig(): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = this@PostgresTestDatabase.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = "postgres"
            password = "postgres"
            maximumPoolSize = 4
            isAutoCommit = false
        }

    /**
     * Tears down the global [DatabaseFactory] before the server it points at, then the server.
     *
     * The global is closed here rather than left to each test class because forgetting it is
     * silent and lands in a *different* class. `initDB` begins with
     * `if (!DatabaseFactory.isInitialized())` and does nothing when that is false, and
     * `isInitialized()` reads `!dataSource.isClosed` — a Hikari flag about the *pool*, not about
     * the server behind it. So a class that closed only its embedded PostgreSQL left a pool that
     * still called itself open, pointing at a server that no longer existed. The next class's
     * `initDB` then skipped silently, its fresh database never got a schema, and it failed
     * somewhere far from the cause: the symptom that surfaced was a syntax error from
     * `INSERT INTO "shop_info" ()`, because `information_schema` had no columns to name.
     *
     * Order matters: the pool is drained while its server is still up, so `close()` does not have
     * to time out on dead sockets.
     *
     * Safe for the classes that never touch [DatabaseFactory] — closing an uninitialised one is a
     * no-op, and a class that did initialise it is finished by the time its `@AfterAll` runs.
     *
     * [TestDatabase] reached the same conclusion for the H2 suite and closes the global *before*
     * its `initDB`; its comment describes the identical failure. This harness was the outlier, and
     * closing on the way out rather than on the way in means a class cannot leak it by returning
     * early or throwing.
     */
    override fun close() {
        DatabaseFactory.close()
        pg.close()
    }

    companion object {
        /** Starts a new embedded PostgreSQL on an ephemeral port. */
        fun start(): PostgresTestDatabase = PostgresTestDatabase(EmbeddedPostgres.builder().start())
    }
}
