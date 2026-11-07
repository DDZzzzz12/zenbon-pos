/**
 * Abstract key-value store interface for user preferences and settings.
 *
 * Provides a platform-agnostic contract for storing simple key-value pairs.
 * Implementations include SQLite-backed (for Android/non-bundled desktop)
 * and Java Preferences-backed (for bundled desktop).
 *
 * @since 1.0.0
 */
package org.example.project.core.data.database

/**
 * Key-value store interface for user preferences.
 *
 * All operations are synchronous since preference access is lightweight.
 */
interface KeyValueStore {
    /**
     * Retrieves a string value for the given key.
     *
     * @param key The preference key
     * @return The stored value, or null if not found
     */
    fun getString(key: String): String?

    /**
     * Stores a string value for the given key.
     *
     * @param key The preference key
     * @param value The value to store
     */
    fun setString(
        key: String,
        value: String,
    )

    /**
     * Removes a specific key from the store.
     *
     * @param key The preference key to remove
     */
    fun remove(key: String)

    /**
     * Retrieves all stored key-value pairs.
     *
     * @return Map of all stored preferences
     */
    fun getAll(): Map<String, String>

    /**
     * Clears all stored preferences.
     */
    fun clear()
}
