/**
 * Java Preferences API-backed implementation of [KeyValueStore].
 *
 * Uses the OS-native preferences storage via [java.util.prefs.Preferences]
 * for bundled desktop mode, eliminating the need for SQLite when the
 * server is embedded and PostgreSQL is directly accessible.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.database

import java.util.prefs.Preferences

/**
 * Key-value store backed by Java Preferences API.
 *
 * Stores preferences in OS-native storage (registry on Windows,
 * plist on macOS, XML files on Linux under ~/.java/.userPrefs/).
 */
class JavaPreferencesKeyValueStore : KeyValueStore {
    private val prefs: Preferences = Preferences.userRoot().node("org.example.project.pos")

    override fun getString(key: String): String? = prefs.get(key, null)

    override fun setString(
        key: String,
        value: String,
    ) {
        prefs.put(key, value)
        prefs.flush()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }

    override fun getAll(): Map<String, String> = prefs.keys().associateWith { key -> (prefs.get(key, "") ?: "") }

    override fun clear() {
        prefs.clear()
        prefs.flush()
    }
}
