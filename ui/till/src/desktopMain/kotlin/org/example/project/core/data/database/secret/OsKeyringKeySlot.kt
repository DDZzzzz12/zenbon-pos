package org.example.project.core.data.database.secret

import co.touchlab.kermit.Logger
import com.github.javakeyring.KeyringStorageType
import com.github.javakeyring.PasswordAccessException
import org.example.project.BuildKonfig
import java.util.UUID

/**
 * [ReplaceableKeySlot] in the OS keychain: Windows Credential Manager, the Linux Secret Service or KWallet, or the macOS Keychain.
 *
 * **Absent or unusable?** java-keyring's `getPassword` throws [PasswordAccessException] both for a missing entry and for
 * a keychain that cannot be used, and only some backends word the two differently. A stored value is returned (a blank
 * one reads as absent). A [PasswordAccessException] is judged by these rules, in order:
 * 1. **Every backend.** A message starting with `"Too many stored credentials match"`, on the first read or on a re-read,
 *    throws. It proves entries exist (Secret Service creates a duplicate item on a second write), so absence is never
 *    concluded from it.
 * 2. **[KeyringStorageType.OSX_KEYCHAIN].** The verified not-found ([isMissingEntry]) reads as absent. Any other failure
 *    throws at once, with no probe: a working keychain says nothing about access to one item, such as an access-list
 *    denial after an app update changed the code signature.
 * 3. **[KeyringStorageType.WINDOWS_CREDENTIAL_STORE] and [KeyringStorageType.LEGACY_OSX_KEYCHAIN].** The verified
 *    not-found (Windows `"Error code 1168"`) reads as absent. Otherwise the probe runs, then a never-written account
 *    `<account>-absent-<uuid>` is read and its failure message captured, then the real account is read once more: a value
 *    is returned, a failure with exactly the captured message reads as absent, and any other failure throws. The
 *    comparison catches a failure whose error code survives, and one clobbered differently from call to call. It does not
 *    help in the case the rule exists for: when `GetLastError` is clobbered *consistently*, a real failure and a missing
 *    entry produce the same message, so a fresh install still gets its key, but the comparison protects no better than
 *    rule 4. **Residual risk:** as under rule 4, a transient failure in the milliseconds after a successful probe can read
 *    as absent. On Windows that is worse, because `CredWriteA` replaces an existing entry: when the key file holds no key
 *    either, [DesktopMasterKey.loadOrCreate] then overwrites the keychain key.
 * 4. **[KeyringStorageType.GNOME_KEYRING] (Secret Service) and [KeyringStorageType.KWALLET].** Both report absence and
 *    failure identically. The probe runs; if it succeeds, a failing re-read of the real account reads as absent.
 *    **Residual risk:** a D-Bus failure landing exactly between a successful probe and that re-read reads as absent.
 *
 * **The probe** writes a random nonce under `<account>-probe`, reads it back, and deletes it. If the nonce cannot be
 * written or read, or reads back as something else, the keychain is unusable right now and [read] throws
 * [IllegalStateException] with the probe failure suppressed; the caller keeps its sealed values and never creates a key
 * over one it cannot see. A probe that starts failing is warned about once, and noted when it passes again, through
 * [problems], which outlives this slot (a new slot is built for every keychain session). On Secret Service a stale
 * duplicated probe item makes every probe fail, so the till runs on the key file alone — safe, but it should be visible.
 *
 * Every exception other than a [PasswordAccessException] propagates. The real account is never written by [read].
 */
class OsKeyringKeySlot(
    private val entries: KeyringEntries,
    private val problems: KeySlotProblemReporter,
    private val service: String = BuildKonfig.APP_NAME,
    private val account: String = ACCOUNT,
) : ReplaceableKeySlot {
    private val log = Logger.withTag("OsKeyringKeySlot")
    private val probeAccount = "$account$PROBE_SUFFIX"

    override val description: String
        get() = "OS keychain (${entries.storageType})"

    override fun read(): String? {
        val failure =
            try {
                return readEntry()
            } catch (e: PasswordAccessException) {
                e
            }
        requireNoDuplicates(failure)
        val storageType = entries.storageType
        if (isMissingEntry(storageType, failure)) return null
        return when (storageType) {
            KeyringStorageType.OSX_KEYCHAIN -> throw IllegalStateException("$description refused to read the local-secrets key", failure)
            KeyringStorageType.WINDOWS_CREDENTIAL_STORE, KeyringStorageType.LEGACY_OSX_KEYCHAIN -> reReadComparedWithAbsentAccount(failure)
            KeyringStorageType.GNOME_KEYRING, KeyringStorageType.KWALLET -> reReadAfterProbe(failure)
        }
    }

    override fun write(value: String) = entries.set(service, account, value)

    private fun readEntry(): String? = entries.get(service, account).takeIf { it.isNotBlank() }

    /** Rule 3: after a working probe, a failing re-read is absent only when it fails exactly as a never-written account does. */
    private fun reReadComparedWithAbsentAccount(readFailure: PasswordAccessException): String? {
        requireWorkingKeychain(readFailure)
        val absentMessage = absentAccountFailureMessage()
        return try {
            readEntry()
        } catch (e: PasswordAccessException) {
            requireNoDuplicates(e)
            if (e.message != absentMessage) {
                throw IllegalStateException("$description failed to read the local-secrets key differently than a missing entry", e)
            }
            reportAbsent()
            null
        }
    }

    /** Rule 4: the probe just proved the backend works, so a failing re-read means the entry is absent. */
    private fun reReadAfterProbe(readFailure: PasswordAccessException): String? {
        requireWorkingKeychain(readFailure)
        return try {
            readEntry()
        } catch (e: PasswordAccessException) {
            requireNoDuplicates(e)
            reportAbsent()
            null
        }
    }

    /** How the backend reports an account that was never written, right now. Throws unless it reports one as missing. */
    private fun absentAccountFailureMessage(): String {
        val absentAccount = "$account$ABSENT_SUFFIX${UUID.randomUUID()}"
        try {
            entries.get(service, absentAccount)
        } catch (e: PasswordAccessException) {
            return e.message ?: throw IllegalStateException("$description reported a never-written entry without a message", e)
        } catch (e: Exception) {
            throw IllegalStateException("$description failed to read a never-written entry", e)
        }
        throw IllegalStateException("$description returned a value for an entry that was never written")
    }

    /** Rule 1: more than one matching entry proves entries exist. */
    private fun requireNoDuplicates(failure: PasswordAccessException) {
        if (failure.message?.startsWith(TOO_MANY_PREFIX) != true) return
        throw IllegalStateException("$description holds more than one local-secrets entry", failure)
    }

    /** Returns when a probe round-trips; otherwise throws, carrying [readFailure] as the cause. */
    private fun requireWorkingKeychain(readFailure: PasswordAccessException) {
        val probeFailure = probeFailure()
        if (probeFailure == null) {
            problems.recovered(PROBE_SUBJECT) { "$description passes its probe again" }
            return
        }
        problems.failed(PROBE_SUBJECT, probeFailure) {
            "$description failed its probe, so the local-secrets key cannot be kept there right now. If this persists, " +
                "remove stale '$probeAccount' entries with the keyring manager."
        }
        throw IllegalStateException("$description cannot be used right now", readFailure).apply { addSuppressed(probeFailure) }
    }

    /** Null when a nonce written under [probeAccount] reads back unchanged; otherwise what went wrong. */
    private fun probeFailure(): Exception? {
        val nonce = UUID.randomUUID().toString()
        val readBack =
            try {
                entries.set(service, probeAccount, nonce)
                entries.get(service, probeAccount)
            } catch (e: Exception) {
                return e
            }
        if (readBack != nonce) return IllegalStateException("The OS keychain probe read back a different value")
        removeProbe()
        return null
    }

    private fun removeProbe() {
        try {
            entries.delete(service, probeAccount)
        } catch (e: Exception) {
            log.w(e) { "Could not remove the OS keychain probe entry from ${entries.storageType}" }
        }
    }

    private fun reportAbsent() = log.i { "$description works but holds no local-secrets key" }

    companion object {
        const val ACCOUNT = "local-secrets-v1"

        private const val PROBE_SUBJECT = "OS keychain probe"
        private const val PROBE_SUFFIX = "-probe"
        private const val ABSENT_SUFFIX = "-absent-"
        private const val TOO_MANY_PREFIX = "Too many stored credentials match"
        private const val OSX_NOT_FOUND_PREFIX = "No stored credentials match "
        private const val WINDOWS_ERROR_NOT_FOUND = "Error code 1168"

        /**
         * True only when [e] proves the entry does not exist, so [read] needs no further IO. Verified against the
         * java-keyring 1.0.4 bytecode (and, for macOS, the jkeychain 1.1.0 native library it calls):
         *
         * | Backend | "Missing entry" message | Distinguishable from a failure? |
         * |---|---|---|
         * | [KeyringStorageType.OSX_KEYCHAIN] (`ModernOsxKeychainBackend`) | `"No stored credentials match <service> account: <account>"` | Yes. The native code returns no value only for `errSecItemNotFound` (-25300). |
         * | [KeyringStorageType.WINDOWS_CREDENTIAL_STORE] (`WinCredentialStoreBackend`) | `"Error code 1168"` | Yes in principle. `GetLastError` is read in a separate JNA call, though, so it may be clobbered. |
         * | [KeyringStorageType.GNOME_KEYRING] (Freedesktop / Secret Service) | `"No stored credentials match …"` | **No.** secret-service returns null on D-Bus errors (locked, `NoReply`, `ServiceUnknown`, …). |
         * | [KeyringStorageType.KWALLET] (`KWalletBackend`) | `"Password is not in wallet"` | **No.** The wallet-open result is never checked. |
         * | [KeyringStorageType.LEGACY_OSX_KEYCHAIN] (`OsxKeychainBackend`, JNA) | OS-localized text | **No.** |
         *
         * Only the two "Yes" rows return true. Everything else — including Windows' `"Password not Found"` (an entry
         * without a credential blob) and a clobbered Windows error code — is judged by [read]'s other rules.
         */
        fun isMissingEntry(
            storageType: KeyringStorageType,
            e: PasswordAccessException,
        ): Boolean {
            val message = e.message ?: return false
            return when (storageType) {
                KeyringStorageType.OSX_KEYCHAIN -> message.startsWith(OSX_NOT_FOUND_PREFIX)
                KeyringStorageType.WINDOWS_CREDENTIAL_STORE -> message == WINDOWS_ERROR_NOT_FOUND
                KeyringStorageType.GNOME_KEYRING, KeyringStorageType.KWALLET, KeyringStorageType.LEGACY_OSX_KEYCHAIN -> false
            }
        }
    }
}
