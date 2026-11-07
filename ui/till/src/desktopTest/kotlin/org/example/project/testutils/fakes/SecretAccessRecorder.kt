package org.example.project.testutils.fakes

import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.secret.SecretPreferenceKeys

/**
 * A [KeyValueStore] that records every read and write of a secret key, and whether it ran on [io].
 *
 * Wrap the sealing store with it: opening or sealing a secret can block for seconds, so none may run on the UI
 * thread. Records `get:<key>` and `set:<key>`; removals never reach the cipher and are not recorded.
 */
class SecretAccessRecorder(
    private val delegate: KeyValueStore,
    private val io: IoMarkingDispatcher,
) : KeyValueStore {
    private val accesses = mutableListOf<Pair<String, Boolean>>()

    /** Every secret read and write since the last [reset]. */
    val secretAccesses: List<String>
        get() = synchronized(accesses) { accesses.map { it.first } }

    /** The secret reads and writes since the last [reset] that did not run on [io]. */
    val offIo: List<String>
        get() = synchronized(accesses) { accesses.filterNot { it.second }.map { it.first } }

    fun reset() {
        synchronized(accesses) { accesses.clear() }
    }

    override fun getString(key: String): String? {
        record("get", key)
        return delegate.getString(key)
    }

    override fun setString(
        key: String,
        value: String,
    ) {
        record("set", key)
        delegate.setString(key, value)
    }

    override fun remove(key: String) = delegate.remove(key)

    override fun getAll(): Map<String, String> = delegate.getAll()

    override fun clear() = delegate.clear()

    private fun record(
        operation: String,
        key: String,
    ) {
        if (key !in SecretPreferenceKeys.ALL) return
        synchronized(accesses) { accesses += "$operation:$key" to io.isCurrent }
    }
}
