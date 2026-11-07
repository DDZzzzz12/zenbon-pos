/**
 * SQLite-backed implementation of [KeyValueStore].
 *
 * Wraps SQLDelight's user_preferences queries to provide key-value storage
 * in the local SQLite database. Used on Android and non-bundled desktop.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.database

import org.example.project.database.PosDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Key-value store backed by SQLDelight local_preferences table.
 *
 * @property database The SQLDelight PosDatabase instance
 */
@OptIn(ExperimentalTime::class)
class SqlDelightKeyValueStore(
    private val database: PosDatabase,
) : KeyValueStore {
    override fun getString(key: String): String? = database.localCacheQueries.getPreference(key).executeAsOneOrNull()

    override fun setString(
        key: String,
        value: String,
    ) {
        database.localCacheQueries.setPreference(key, value, Clock.System.now().toEpochMilliseconds())
    }

    override fun remove(key: String) {
        database.localCacheQueries.deletePreference(key)
    }

    override fun getAll(): Map<String, String> =
        database.localCacheQueries
            .getAllPreferences()
            .executeAsList()
            .associate { it.key to it.value_ }

    override fun clear() {
        database.localCacheQueries.clearAllPreferences()
    }
}
