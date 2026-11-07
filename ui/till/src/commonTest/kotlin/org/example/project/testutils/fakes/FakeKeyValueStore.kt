package org.example.project.testutils.fakes

import org.example.project.core.data.database.KeyValueStore

/**
 * Map-backed [KeyValueStore].
 *
 * By default an empty value reads back as null. Pass [emptyReadsAsNull] = false to return values exactly
 * as written, including `""` — that matches `SqlDelightKeyValueStore`, so tests of the "empty means
 * cleared" path see what the real till reads back.
 */
class FakeKeyValueStore(
    private val emptyReadsAsNull: Boolean = true,
) : KeyValueStore {
    private val store = mutableMapOf<String, String>()

    override fun getString(key: String): String? = store[key]?.takeUnless { emptyReadsAsNull && it.isEmpty() }

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
