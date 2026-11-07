package org.example.project.core.data.database

/**
 * A key prefix that gives one client a private partition of a storage area it shares with another client.
 *
 * The back office (`/admin/`) and the browser till (`/pos/`) are served from the same
 * pos-server origin, and a browser keeps exactly one `localStorage` per origin. Both apps persist
 * their session through the shared [org.example.project.core.auth.TokenStore] under the same
 * logical keys (`jwt_access_token`, …), so without a prefix the till overwrote — or, on logout,
 * deleted — the back office's tokens and vice versa (F52). Each wasm `KeyValueStore` therefore
 * maps every logical key through its namespace before touching `localStorage`.
 *
 * The separator is `:`, not `.`, on purpose: adminApp already stored keys spelled `admin.language`
 * and `admin.sidebar.*` before namespacing existed. With an `admin.` prefix such a legacy key would
 * read back as the namespaced logical key `language` — a legacy value silently surfacing as a
 * current one. `admin:` cannot be confused with any key written before this class existed.
 */
class StorageNamespace(
    val prefix: String,
) {
    init {
        require(prefix.isNotEmpty()) { "A storage namespace needs a non-empty prefix" }
    }

    /** The key actually written to the shared storage area for the logical [key]. */
    fun physicalKey(key: String): String = prefix + key

    /** The logical key for a [physicalKey] in this namespace, or null when it belongs to someone else. */
    fun logicalKeyOrNull(physicalKey: String): String? = if (physicalKey.startsWith(prefix)) physicalKey.removePrefix(prefix) else null

    /** Whether [physicalKey] lives in this namespace. */
    fun owns(physicalKey: String): Boolean = physicalKey.startsWith(prefix)

    /**
     * This namespace's view of a whole storage area: only its own entries, under their logical keys.
     * What `KeyValueStore.getAll()` returns, so one app can never enumerate the other's tokens.
     */
    fun viewOf(storage: Map<String, String>): Map<String, String> =
        buildMap {
            storage.forEach { (physicalKey, value) ->
                logicalKeyOrNull(physicalKey)?.let { put(it, value) }
            }
        }

    companion object {
        /** The back office, served at `/admin/`. */
        val ADMIN = StorageNamespace("admin:")

        /** The browser till (composeApp wasmJs), served at `/pos/`. */
        val POS = StorageNamespace("pos:")
    }
}
