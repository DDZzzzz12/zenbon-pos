package org.example.project.testutils

import org.example.project.core.data.database.KeyValueStore

/** In-memory [KeyValueStore] for tests. Mirrors the SQLDelight impl's empty-string-is-absent rule. */
class FakeKeyValueStore : KeyValueStore {
    private val store = mutableMapOf<String, String>()

    override fun getString(key: String): String? = store[key]?.takeIf { it.isNotEmpty() }

    override fun setString(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    override fun remove(key: String) {
        store.remove(key)
    }

    override fun getAll(): Map<String, String> = store.toMap()

    override fun clear() {
        store.clear()
    }
}
