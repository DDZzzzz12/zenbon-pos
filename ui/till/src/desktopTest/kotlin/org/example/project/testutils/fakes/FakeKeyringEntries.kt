package org.example.project.testutils.fakes

import com.github.javakeyring.KeyringStorageType
import com.github.javakeyring.PasswordAccessException
import org.example.project.core.data.database.secret.KeyringEntries

/**
 * In-memory [KeyringEntries] that fails the way java-keyring 1.0.4 does: an absent entry throws a
 * [PasswordAccessException] carrying the backend's "not found" text — [missingMessage], or per account [missingMessageFor].
 *
 * [getError] runs before every read and can throw anything in its place (a transient D-Bus failure, "Too many stored
 * credentials match", a native error); [readOverride] replaces the value a read returns (a backend that does not
 * round-trip). [readAccounts] and [writtenAccounts] record every account read and written, in order.
 */
class FakeKeyringEntries(
    override val storageType: KeyringStorageType = KeyringStorageType.GNOME_KEYRING,
    var missingMessage: String = "No stored credentials match POS account: local-secrets-v1",
) : KeyringEntries {
    val stored = mutableMapOf<Pair<String, String>, String>()
    val readAccounts = mutableListOf<String>()
    val writtenAccounts = mutableListOf<String>()
    var getError: (service: String, account: String) -> Exception? = { _, _ -> null }
    var readOverride: (account: String) -> String? = { null }
    var missingMessageFor: (account: String) -> String = { missingMessage }
    var failWrites = false
    var failDeletes = false
    var sets = 0
    var deletes = 0
    var closes = 0

    override fun close() {
        closes++
    }

    override fun get(
        service: String,
        account: String,
    ): String {
        readAccounts += account
        getError(service, account)?.let { throw it }
        readOverride(account)?.let { return it }
        return stored[service to account] ?: throw PasswordAccessException(missingMessageFor(account))
    }

    override fun set(
        service: String,
        account: String,
        value: String,
    ) {
        sets++
        writtenAccounts += account
        if (failWrites) throw PasswordAccessException("Failed to store credential")
        stored[service to account] = value
    }

    override fun delete(
        service: String,
        account: String,
    ) {
        deletes++
        if (failDeletes) throw PasswordAccessException("Failed to delete credential")
        stored.remove(service to account)
    }
}
