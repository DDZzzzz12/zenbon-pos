package org.example.project.core.data.database.secret

/**
 * One place the local-secrets key can be kept, as Base64 text: the OS keychain ([OsKeyringKeySlot]) or a key file
 * ([FileKeySlot]). Read-only here: the keychain can also be written over ([ReplaceableKeySlot]), while the key file can
 * only be created ([FallbackKeySlot.writeIfAbsent]) and never replaced.
 */
interface KeySlot {
    /** Where the key is kept, for logs. Never contains key material. */
    val description: String

    /**
     * The stored value, or null ONLY when the slot provably holds no key.
     *
     * Throws when the slot cannot be read right now — a locked or unreachable keychain, an unreadable file. Absence is
     * never guessed: a null makes the caller create a new key, which must not happen while the real one is out of reach.
     */
    fun read(): String?
}
