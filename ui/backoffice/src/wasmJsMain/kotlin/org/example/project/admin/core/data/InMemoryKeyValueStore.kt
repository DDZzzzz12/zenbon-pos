package org.example.project.admin.core.data

import org.example.project.core.data.database.KeyValueStore

/**
 * A [KeyValueStore] that forgets everything on reload.
 *
 * Holds the admin session's access token, role and capabilities — none of which may sit in
 * localStorage, where any script running on this origin can read them. The HttpOnly refresh
 * cookie restores them on the next page load (see StartupPolicy.RESUME_VIA_REFRESH_COOKIE).
 */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]?.takeIf { it.isNotEmpty() }

    override fun setString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun getAll(): Map<String, String> = values.toMap()

    override fun clear() = values.clear()
}
