package org.example.project.core.data.database.secret

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.example.project.core.utils.platformIoDispatcher
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Seals, once per process, any secret an older build left in plaintext, and wipes old plaintext from the database file
 * once per database.
 *
 * Sealing or deleting a row does not remove its old bytes: SQLite keeps them in freed pages of the file, and in the WAL,
 * until something reuses them. A copy of the file taken after an upgrade still held the device token and the remember-me
 * password. What this pass seals is not the only plaintext left behind, either:
 * - any read of a secret key re-seals legacy plaintext by itself ([SecretProtectingKeyValueStore.getString]), and the
 *   launch gate or the device-token loader can read before this runs;
 * - the start-up session wipe deletes legacy tokens rather than sealing them;
 * - freed pages still hold earlier remember-me passwords and rotated refresh tokens.
 *
 * So the wipe does not depend on what this pass sealed. It is owed until the non-secret marker [WIPE_MARKER_KEY] says it
 * is done. Each start:
 * 1. if a secret is stored in plaintext, removes the marker *before* sealing, so a crash or a failed `VACUUM` after the
 *    seal leaves the wipe owed;
 * 2. seals the plaintext;
 * 3. when the marker is absent or this pass sealed something, wipes:
 *    - `PRAGMA secure_delete=ON`;
 *    - `VACUUM`, which rebuilds the file and overwrites every page, freed ones included;
 *    - then sets the marker, but only if the `VACUUM` succeeded and step 2 left no plaintext behind. A row still holding
 *      plaintext is out of the `VACUUM`'s reach, and a later read that re-seals it frees that plaintext afterwards;
 *    - in WAL mode, `wal_checkpoint(TRUNCATE)`, which moves the rebuilt pages into the file and empties the WAL. It is
 *      best-effort and does not hold back the marker.
 *
 * With the marker set, a start that seals nothing leaves the file alone. A start that cannot read the marker leaves the
 * wipe to the next start.
 *
 * **The `VACUUM` is the wipe.** `secure_delete` is a per-connection setting and only belt-and-braces: it covers the
 * `VACUUM` only where the same connection runs both. Android's framework connection pool may route the pragma to a
 * different connection. The desktop JDBC driver opens a fresh connection for each statement outside a transaction. All of
 * it runs in one uninterrupted block on one thread. `VACUUM` cannot run inside a transaction, so none is opened. The
 * row-returning pragmas go through `executeQuery`, because Android refuses them through `execute`.
 *
 * Best-effort: a failing step is logged and the next one still runs. Nothing but cancellation escapes, so start-up never
 * fails here.
 */
@OptIn(ExperimentalAtomicApi::class)
class LocalSecretsMigration(
    private val store: SecretProtectingKeyValueStore,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val log = Logger.withTag("LocalSecretsMigration")
    private val started = AtomicBoolean(false)

    /** Seals and wipes on [ioDispatcher]. Only the first call on an instance does anything. */
    suspend fun run() {
        if (!started.compareAndSet(false, true)) return
        withContext(ioDispatcher) {
            val found = attempt("look for legacy secrets") { store.hasLegacySecrets() } ?: false
            if (found) attempt("clear the wipe marker") { store.remove(WIPE_MARKER_KEY) }
            val sealed = attempt("seal legacy secrets") { store.sealLegacySecrets() } ?: 0
            // Checked before the VACUUM, not after it: once no row holds plaintext, no read can free any later.
            val noneLeft = attempt("check for plaintext left unsealed") { !store.hasLegacySecrets() } ?: false
            val wipeDone =
                attempt("read the wipe marker, so the wipe waits for the next start") {
                    store.getString(WIPE_MARKER_KEY) == WIPE_DONE
                } ?: return@withContext
            if (wipeDone && sealed == 0) return@withContext
            log.i { "Sealed $sealed legacy secret(s), wipe done before: $wipeDone — wiping old plaintext from the database file" }
            wipeFreedPlaintext(recordDone = noneLeft)
        }
    }

    private fun wipeFreedPlaintext(recordDone: Boolean) {
        attempt("turn on secure_delete") { driver.firstValue("PRAGMA secure_delete=ON") }
        val vacuumed = attempt("VACUUM the database") { driver.execute(identifier = null, sql = "VACUUM", parameters = 0) } != null
        // Set before the checkpoint, so the marker row leaves the WAL together with the rebuilt pages.
        if (vacuumed && recordDone) attempt("set the wipe marker") { store.setString(WIPE_MARKER_KEY, WIPE_DONE) }
        if (vacuumed && !recordDone) log.i { "A legacy secret is still stored in plaintext — the wipe stays owed" }
        attempt("truncate the WAL") {
            if (driver.firstValue("PRAGMA journal_mode").equals("wal", ignoreCase = true)) {
                driver.firstValue("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
    }

    /** Runs [step] and returns its result, or null when it failed; any failure but cancellation is logged and swallowed. */
    private inline fun <T> attempt(
        name: String,
        step: () -> T,
    ): T? =
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Could not $name — continuing" }
            null
        }

    companion object {
        /**
         * The `user_preferences` row set once this database has been vacuumed with no secret left in plaintext. Not a
         * secret, so the store passes it through unsealed. Persisted: never rename it, or every database is wiped again.
         * Bump the version suffix only to force a new wipe on every database.
         */
        const val WIPE_MARKER_KEY = "local_secrets_wipe_v1"

        private const val WIPE_DONE = "done"
    }
}

/** The first column of the first row [sql] returns, as text. Steps the cursor, which is what runs the statement. */
private fun SqlDriver.firstValue(sql: String): String? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value
