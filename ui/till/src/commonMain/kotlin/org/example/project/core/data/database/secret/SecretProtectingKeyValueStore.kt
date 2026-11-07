package org.example.project.core.data.database.secret

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.example.project.core.data.database.KeyValueStore

/**
 * A [KeyValueStore] that seals the values of [secretKeys] before they reach [delegate].
 *
 * Every caller keeps reading and writing plaintext; only the stored bytes change. Each value is sealed
 * bound to its own key, so a sealed value copied into another row does not open. On read:
 * - plaintext found under a secret key was written by a build before encryption — it is returned and
 *   re-sealed on a best-effort basis: if sealing or the write fails, the read still succeeds and the
 *   next read tries again;
 * - a sealed value that can never be opened (the cipher returns null: value damaged, sealed for another
 *   key, or a cipher key the platform can prove is gone, such as a Keystore alias Android API 31+ reports as not found) is
 *   removed and reads as absent, so the till re-pairs instead of crashing;
 * - a sealed value that cannot be opened right now (the cipher throws: Keystore unavailable at boot, a
 *   desktop keychain that is locked or cannot prove its key was deleted, a native keychain `Error`) is
 *   kept on disk and reads as absent this time, so a transient failure never destroys a pairing;
 * - a value sealed by a newer format version is kept on disk and reads as absent — never handed out as
 *   plaintext, discarded or re-sealed, so running an older build cannot corrupt it;
 * - an empty value means "cleared" (LocalDatabaseManager writes "" to clear) and stays empty.
 *
 * A failing [setString] seal propagates and writes nothing.
 *
 * **Threads.** Ktor request coroutines read the store while login, pairing and the start-up migration write it from
 * others. The re-seal and the discard are check-then-act: each compares the row with what it read, then writes or
 * removes it. That compare and its act run as one step under a lock that every write, remove and clear of a secret key
 * also takes, so nothing can land between them: within one process the race is closed. The cipher calls run outside the
 * lock. They can take seconds — a keychain that does not answer, Keystore IPC — and a logout clearing the tokens on the
 * UI thread must not wait behind another thread's open. Operations on other keys never take the lock.
 */
class SecretProtectingKeyValueStore(
    private val delegate: KeyValueStore,
    private val cipher: SecretCipher,
    private val secretKeys: Set<String> = SecretPreferenceKeys.ALL,
) : KeyValueStore {
    private val log = Logger.withTag("SecretStore")

    /** Held only across storage calls on a secret key, never across a cipher call. Nothing takes it twice. */
    private val secretLock = SynchronizedObject()

    override fun getString(key: String): String? {
        val stored = delegate.getString(key) ?: return null
        if (key !in secretKeys || stored.isEmpty()) return stored
        if (!SealedSecret.isFromThisFamily(stored)) return stored.also { resealLegacy(key, it) }
        if (!SealedSecret.isSealed(stored)) return ignoreNewerFormat(key)
        val opened =
            try {
                cipher.open(key = key, sealed = stored)
            } catch (e: Throwable) {
                // Throwable, not Exception: a desktop keychain binding can throw a native linkage Error, and
                // the device token is now read lazily through DeviceTokenProvider's loader on the IO dispatcher,
                // whose callers catch only Exception. Not suspend, so nothing to rethrow.
                log.w(e) { "Stored secret '$key' cannot be opened right now — keeping it" }
                return null
            }
        return opened ?: discardUnreadable(key, stored)
    }

    override fun setString(
        key: String,
        value: String,
    ) {
        if (key !in secretKeys) return delegate.setString(key, value)
        val stored = if (value.isEmpty()) value else cipher.seal(key = key, plaintext = value)
        synchronized(secretLock) { delegate.setString(key, stored) }
    }

    override fun remove(key: String) {
        if (key !in secretKeys) return delegate.remove(key)
        synchronized(secretLock) { delegate.remove(key) }
    }

    /**
     * Opens secrets for the caller; never rewrites or discards anything. Legacy plaintext is listed as is;
     * a secret that cannot be opened — now or ever — or that a newer format sealed is listed as `""`.
     */
    override fun getAll(): Map<String, String> =
        synchronized(secretLock) { delegate.getAll() }.mapValues { (key, value) ->
            if (key in secretKeys) listSecret(key, value) else value
        }

    override fun clear() {
        synchronized(secretLock) { delegate.clear() }
    }

    /**
     * Seals every secret an older build left in plaintext and returns how many rows it sealed. Unlike a read it opens
     * nothing, and it leaves sealed, cleared, absent and newer-format values as they are.
     *
     * A seal that fails does not throw: it is logged by key, counts as nothing, and the plaintext stays for a later read
     * or run to seal. A failure to read the underlying store does propagate.
     */
    fun sealLegacySecrets(): Int = secretKeys.count(::sealIfLegacy)

    /**
     * Whether any secret an older build left in plaintext is stored — what [sealLegacySecrets] would seal. Opens, seals
     * and writes nothing. A failure to read the underlying store propagates.
     */
    fun hasLegacySecrets(): Boolean = secretKeys.any { key -> delegate.getString(key)?.let(::isLegacy) == true }

    private fun sealIfLegacy(key: String): Boolean {
        val stored = delegate.getString(key)
        if (stored == null || !isLegacy(stored)) return false
        return resealLegacy(key, stored)
    }

    /** Plaintext left by a build before encryption: not cleared, and not in any format of ours, current or newer. */
    private fun isLegacy(stored: String): Boolean = stored.isNotEmpty() && !SealedSecret.isFromThisFamily(stored)

    /** Writes the sealed form only if the row still holds [stored], and returns whether it did. */
    private fun resealLegacy(
        key: String,
        stored: String,
    ): Boolean =
        runCatching {
            val sealed = cipher.seal(key = key, plaintext = stored)
            ifStillHolds(key, stored) { delegate.setString(key, sealed) }
        }.onSuccess { resealed ->
            if (resealed) {
                log.i { "Sealed legacy plaintext secret '$key'" }
            } else {
                log.i { "Legacy secret '$key' changed while sealing — leaving the newer value" }
            }
        }.onFailure { e -> log.w(e) { "Could not seal legacy secret '$key' yet — retrying later" } }
            .getOrDefault(false)

    /** Removes the row only if it still holds [stored], the value that failed to open. */
    private fun discardUnreadable(
        key: String,
        stored: String,
    ): String? {
        log.w { "Stored secret '$key' cannot be opened (key lost or value damaged) — discarding it" }
        runCatching {
            ifStillHolds(key, stored) { delegate.remove(key) }
        }.onFailure { e -> log.w(e) { "Could not discard unreadable secret '$key' — retrying on next read" } }
        return null
    }

    /**
     * Runs [act] only if the row for [key] still holds [expected], and returns whether it ran. The compare and [act] run
     * under [secretLock], so no write or remove on another thread can land between them.
     */
    private inline fun ifStillHolds(
        key: String,
        expected: String,
        act: () -> Unit,
    ): Boolean =
        synchronized(secretLock) {
            val unchanged = delegate.getString(key) == expected
            if (unchanged) act()
            unchanged
        }

    private fun ignoreNewerFormat(key: String): String? {
        log.w { "Stored secret '$key' was sealed by a newer build — ignoring it" }
        return null
    }

    private fun listSecret(
        key: String,
        stored: String,
    ): String =
        when {
            !SealedSecret.isFromThisFamily(stored) -> stored
            SealedSecret.isSealed(stored) -> openForListing(key, stored)
            else -> ""
        }

    private fun openForListing(
        key: String,
        sealed: String,
    ): String =
        try {
            cipher.open(key = key, sealed = sealed).orEmpty()
        } catch (e: Throwable) {
            // Throwable, not Exception: a native keychain Error must not crash a settings listing.
            log.w(e) { "Stored secret '$key' cannot be opened right now — listing it as empty" }
            ""
        }
}
