package org.example.project.core.data.database

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * [KeyValueStore] over the browser's `localStorage`.
 *
 * The wasm build constructs no `PosDatabase`, so `SqlDelightKeyValueStore` has nothing to sit on.
 * localStorage is the right shape for this and only this: a synchronous string-to-string map holding
 * a JWT and a handful of settings. It is **not** a database, and nothing cache-like belongs here —
 * the data providers go to the server instead.
 *
 * Values survive a reload, which is deliberate: a preview that logs you out on every rebuild is a
 * preview nobody uses. `SessionState` still applies `StartupPolicy.WIPE` on top, exactly as on the
 * other platforms.
 *
 * Every key is namespaced through [StorageNamespace.POS]: the back office is served from the same
 * origin and shares this `localStorage`, with the same `TokenStore` keys, so an unprefixed till
 * signed the back office out and vice versa (F52). [clear] and [getAll] are scoped to the namespace
 * as well. Unprefixed keys written by an older build are deliberately **not** adopted — they could
 * be the back office's session, so the till starts clean.
 */
class LocalStorageKeyValueStore : KeyValueStore {
    private val namespace = StorageNamespace.POS

    override fun getString(key: String): String? = localStorage[namespace.physicalKey(key)]

    override fun setString(
        key: String,
        value: String,
    ) {
        localStorage[namespace.physicalKey(key)] = value
    }

    override fun remove(key: String) {
        localStorage.removeItem(namespace.physicalKey(key))
    }

    override fun getAll(): Map<String, String> = namespace.viewOf(rawStorage())

    override fun clear() {
        rawStorage().keys.filter(namespace::owns).forEach(localStorage::removeItem)
    }

    private fun rawStorage(): Map<String, String> =
        buildMap {
            for (index in 0 until localStorage.length) {
                val key = localStorage.key(index) ?: continue
                val value = localStorage[key] ?: continue
                put(key, value)
            }
        }
}
