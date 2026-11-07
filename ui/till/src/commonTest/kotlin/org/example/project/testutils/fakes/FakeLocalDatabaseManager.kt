package org.example.project.testutils.fakes

import org.example.project.core.data.database.LocalDatabaseManager

/**
 * Creates a LocalDatabaseManager backed by an in-memory FakeKeyValueStore.
 *
 * Since LocalDatabaseManager is a concrete class (not an interface),
 * this helper provides a real instance with controllable in-memory storage
 * suitable for unit tests.
 */
object FakeLocalDatabaseManager {
    /**
     * Creates a new LocalDatabaseManager with an empty in-memory store.
     */
    fun create(): LocalDatabaseManager = LocalDatabaseManager(FakeKeyValueStore())

    /**
     * Creates a new LocalDatabaseManager with pre-populated preferences.
     *
     * @param preferences Initial key-value pairs to populate
     */
    fun create(preferences: Map<String, String>): LocalDatabaseManager {
        val store = FakeKeyValueStore()
        preferences.forEach { (key, value) -> store.setString(key, value) }
        return LocalDatabaseManager(store)
    }
}
