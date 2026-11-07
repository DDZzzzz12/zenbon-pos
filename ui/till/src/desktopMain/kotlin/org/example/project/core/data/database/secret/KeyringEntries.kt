package org.example.project.core.data.database.secret

import com.github.javakeyring.KeyringStorageType
import com.github.javakeyring.PasswordAccessException

/**
 * The entries of one open OS keychain, as [OsKeyringKeySlot] uses them. Exists so the slot's rules and
 * [OsDesktopKeySource]'s timeouts can be tested: java-keyring's `Keyring` has a private constructor and cannot be faked.
 * Production: [JavaKeyringEntries].
 *
 * [close] releases the keychain connection; [OsDesktopKeySource] calls it once, after the last use.
 */
interface KeyringEntries : AutoCloseable {
    /** The backend in use; decides which "not found" messages [OsKeyringKeySlot.isMissingEntry] can trust. */
    val storageType: KeyringStorageType

    /** The stored value. Throws [PasswordAccessException] for a missing entry and for a failure alike, as java-keyring does. */
    fun get(
        service: String,
        account: String,
    ): String

    /** Stores [value], replacing any previous one. Throws [PasswordAccessException] when the keychain refuses it. */
    fun set(
        service: String,
        account: String,
        value: String,
    )

    /** Removes the entry. Throws [PasswordAccessException] when the keychain refuses it. */
    fun delete(
        service: String,
        account: String,
    )
}
