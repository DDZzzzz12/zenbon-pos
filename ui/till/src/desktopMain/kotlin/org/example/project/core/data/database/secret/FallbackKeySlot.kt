package org.example.project.core.data.database.secret

/**
 * The [KeySlot] a new local-secrets key may be created in from more than one thread or process at once: the key file
 * ([FileKeySlot]). [OsDesktopKeySource] creates a file key on the calling thread while an abandoned keychain session can
 * still finish minutes later and try to create its own, and two app instances can race on a fresh install. Creating a
 * key there must therefore never replace a stored one, so this slot offers no other write than [writeIfAbsent]; a slot
 * that can be written over is a [ReplaceableKeySlot].
 */
interface FallbackKeySlot : KeySlot {
    /**
     * Stores [value] unless the slot already holds a value, atomically, and returns the value the slot holds afterwards:
     * [value] itself, or the one another caller stored first.
     *
     * Throws when the slot cannot take [value], and when it already holds something that is not a value it could have
     * written. That content is never overwritten: it may be a key read wrong, and replacing it would lose every secret
     * sealed under it.
     */
    fun writeIfAbsent(value: String): String
}
