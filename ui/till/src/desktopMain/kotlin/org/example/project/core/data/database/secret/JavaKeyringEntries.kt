package org.example.project.core.data.database.secret

import com.github.javakeyring.Keyring
import com.github.javakeyring.KeyringStorageType

/** [KeyringEntries] over a java-keyring [Keyring], which it owns: [close] closes it. */
class JavaKeyringEntries(
    private val keyring: Keyring,
) : KeyringEntries {
    override val storageType: KeyringStorageType
        get() = keyring.keyringStorageType

    /** java-keyring never returns null here; a null would read as blank, i.e. absent, as it did before this adapter. */
    override fun get(
        service: String,
        account: String,
    ): String = keyring.getPassword(service, account).orEmpty()

    override fun set(
        service: String,
        account: String,
        value: String,
    ) = keyring.setPassword(service, account, value)

    override fun delete(
        service: String,
        account: String,
    ) = keyring.deletePassword(service, account)

    override fun close() = keyring.close()
}
