package org.example.project.admin.core.data

import kotlinx.browser.localStorage
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.StorageNamespace

/**
 * Browser-backed [KeyValueStore]. Values survive page reloads and are scoped to the origin,
 * so the admin session persists across refreshes but not across browsers or profiles.
 *
 * Every key goes through [namespace]: the browser till is served from the same origin and so
 * shares this `localStorage`, and both apps write the same `TokenStore` keys. Unprefixed, opening
 * the till in a second tab signed the back office out (F52). [clear] and [getAll] are scoped to the
 * namespace too — a `localStorage.clear()` here would wipe the till's session and both device ids.
 *
 * The device ids (`admin_device_id`, `pos_preview_device_id`) do not go through this store at all;
 * `DeviceInfoProvider` reads them from `localStorage` directly under their own distinct names.
 */
class LocalStorageKeyValueStore(
    private val namespace: StorageNamespace = StorageNamespace.ADMIN,
) : KeyValueStore {
    override fun getString(key: String): String? = localStorage.getItem(namespace.physicalKey(key))?.takeIf { it.isNotEmpty() }

    override fun setString(
        key: String,
        value: String,
    ) = localStorage.setItem(namespace.physicalKey(key), value)

    override fun remove(key: String) = localStorage.removeItem(namespace.physicalKey(key))

    override fun getAll(): Map<String, String> = namespace.viewOf(rawLocalStorage())

    override fun clear() {
        rawLocalStorage().keys.filter(namespace::owns).forEach(localStorage::removeItem)
    }
}

/** A snapshot of the whole origin's `localStorage`, every namespace included. */
internal fun rawLocalStorage(): Map<String, String> =
    buildMap {
        for (index in 0 until localStorage.length) {
            val key = localStorage.key(index) ?: continue
            localStorage.getItem(key)?.let { put(key, it) }
        }
    }
