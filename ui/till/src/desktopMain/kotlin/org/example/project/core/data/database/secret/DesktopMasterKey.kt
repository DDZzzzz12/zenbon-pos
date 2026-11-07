package org.example.project.core.data.database.secret

import co.touchlab.kermit.Logger
import java.security.SecureRandom
import java.util.Base64

/**
 * Reads and creates the 256-bit keys behind [DesktopSecretCipher], kept Base64-encoded in a [KeySlot]: the OS keychain
 * (primary, a [ReplaceableKeySlot]) and an owner-only key file (fallback, a [FallbackKeySlot]).
 *
 * **Reading.** [load] reads both slots, never writes, and returns every key it found as a candidate ([LoadedKeys]). A slot
 * that throws — an exception, or a native `Error` such as JNA's `UnsatisfiedLinkError` ([errorsAsExceptions]) — is
 * recorded, not fatal: its key is simply not a candidate and the result is incomplete. Only a [VirtualMachineError]
 * propagates. A stored value that is not Base64 or not [KEY_BYTES] long can never have been a key; it is ignored and does
 * not make the result incomplete. Both kinds of problem are logged through the caller's [KeySlotProblemReporter], which
 * must outlive the call, when they start and when they end rather than on every load: a slot that becomes unreadable or
 * readable again, and a malformed stored key that appears or goes away. The till loads on every token refresh, and a
 * kiosk with a garbled key file would otherwise log a WARN each time.
 *
 * **Creating.** [loadOrCreate] creates a key only when [load] found none at all, and never writes a slot whose read threw:
 * that slot may hold a key it could not show.
 * - The keychain takes the key whenever its read succeeded, whatever state the key file is in. The keychain key is the
 *   first candidate, so a key file nobody can read (a root-owned config folder left by a `sudo` run) does not block
 *   pairing.
 * - The file takes the key only when the keychain is unusable or refuses it, and only when the file's own read
 *   succeeded. Otherwise [loadOrCreate] throws.
 * - The file is written only through [FallbackKeySlot.writeIfAbsent]. Since the load read the file, another caller may have
 *   created a key there: the calling thread's file-only fallback while a keychain session hangs, the abandoned session
 *   finishing later, or a second app instance. That key wins and is returned. A key file that holds something other than a
 *   key is never replaced; creating throws instead.
 *
 * **Without the keychain.** [loadFallbackOnly] and [loadOrCreateFallbackOnly] apply the same rules to the key file alone,
 * for when no keychain session can run right now ([OsDesktopKeySource]). The keychain is not asked at all, so the result is
 * never complete and a new key can only go to the file.
 */
object DesktopMasterKey {
    const val KEY_BYTES = 32

    private const val PRIMARY_SUBJECT = "primary key slot"
    private const val FALLBACK_SUBJECT = "fallback key slot"
    private const val MALFORMED_SUFFIX = " holding a malformed key"

    private val log = Logger.withTag("DesktopMasterKey")
    private val random = SecureRandom()

    /** Every key readable in [primary] (null when this build has no keychain binding) and [fallback], keychain first. Never writes. */
    fun load(
        primary: ReplaceableKeySlot?,
        fallback: FallbackKeySlot,
        problems: KeySlotProblemReporter,
    ): LoadedKeys {
        val fromPrimary = primary?.let { readKey(it, PRIMARY_SUBJECT, problems) }
        val fromFallback = readKey(fallback, FALLBACK_SUBJECT, problems)
        val primaryKey = fromPrimary?.getOrNull()
        val fallbackKey = fromFallback.getOrNull()
        return LoadedKeys(
            keys = listOfNotNull(primaryKey, fallbackKey),
            primaryUsable = fromPrimary?.isSuccess == true,
            fallbackReadable = fromFallback.isSuccess,
            complete = (fromPrimary?.isSuccess ?: true) && fromFallback.isSuccess,
            sealingKeySlot = primary?.description.takeIf { primaryKey != null } ?: fallback.description.takeIf { fallbackKey != null },
        )
    }

    /** [load] while the keychain cannot be asked: only [fallback] is read, and the result is never complete. Never writes. */
    fun loadFallbackOnly(
        fallback: FallbackKeySlot,
        problems: KeySlotProblemReporter,
    ): LoadedKeys = load(null, fallback, problems).copy(complete = false)

    /**
     * [load]; when it found no key, generates one and stores it — in [primary] when its read succeeded, otherwise (or when
     * that write fails) in [fallback], provided the file's read succeeded — and returns the stored key with the load's
     * flags and the slot that took it. A key another caller created in [fallback] meanwhile is returned in place of the new
     * one.
     *
     * Throws when no slot takes the key, including a file that could not be read or already holds something other than a key.
     */
    fun loadOrCreate(
        primary: ReplaceableKeySlot?,
        fallback: FallbackKeySlot,
        problems: KeySlotProblemReporter,
    ): LoadedKeys = createIfNone(primary, fallback, load(primary, fallback, problems))

    /** [loadOrCreate] while the keychain cannot be asked: a new key can only go to [fallback], and the result is never complete. */
    fun loadOrCreateFallbackOnly(
        fallback: FallbackKeySlot,
        problems: KeySlotProblemReporter,
    ): LoadedKeys = createIfNone(null, fallback, loadFallbackOnly(fallback, problems))

    /** [loaded] when it holds a key; otherwise [loaded] carrying a newly stored key and the slot that took it. */
    private fun createIfNone(
        primary: ReplaceableKeySlot?,
        fallback: FallbackKeySlot,
        loaded: LoadedKeys,
    ): LoadedKeys {
        if (loaded.keys.isNotEmpty()) return loaded
        val (key, slot) = createAndStore(primary, fallback, loaded)
        return loaded.copy(keys = listOf(key), sealingKeySlot = slot.description)
    }

    /** The stored key and the slot that took it. [loaded] must be the empty result of a load over the same slots. */
    private fun createAndStore(
        primary: ReplaceableKeySlot?,
        fallback: FallbackKeySlot,
        loaded: LoadedKeys,
    ): Pair<ByteArray, KeySlot> {
        require(loaded.keys.isEmpty()) { "A local-secrets key is created only when none was loaded" }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val encoded = Base64.getEncoder().encodeToString(key)
        var keychainFailure: Exception? = null
        if (primary != null && loaded.primaryUsable) {
            keychainFailure = writeOrFailure(primary, encoded)
            if (keychainFailure == null) {
                log.i { "Created the local-secrets key in ${primary.description}" }
                return key to primary
            }
            log.w(keychainFailure) { "Could not store the local-secrets key in ${primary.description}" }
        }
        key.fill(0)
        return try {
            storeInFallback(fallback, encoded, loaded.fallbackReadable) to fallback
        } catch (e: Exception) {
            throw IllegalStateException("The local-secrets key could not be stored in any slot", e)
                .apply { keychainFailure?.let(::addSuppressed) }
        }
    }

    /**
     * The key [fallback] holds once [encoded] was offered to it: the new key, or one another caller stored first. Throws
     * when the file could not be read ([readable] is false), refuses the key, or already holds something other than a key.
     */
    private fun storeInFallback(
        fallback: FallbackKeySlot,
        encoded: String,
        readable: Boolean,
    ): ByteArray {
        check(readable) { "${fallback.description} could not be read — not creating a local-secrets key there" }
        val stored = errorsAsExceptions { fallback.writeIfAbsent(encoded) }
        val key = checkNotNull(decodeKey(stored)) { "${fallback.description} already holds something other than a local-secrets key — not replacing it" }
        if (stored == encoded) {
            log.w { "No usable OS keychain — local-secrets key stored in ${fallback.description}. See manual chapter 16." }
        } else {
            log.i { "Another caller created the local-secrets key in ${fallback.description} first — using that key" }
        }
        return key
    }

    /**
     * Success with the decoded key (null when the slot holds none or a malformed one), or failure when the read threw. A
     * read that starts or stops throwing, and a malformed key that appears or goes away, are reported to [problems] under
     * [subject], the slot's role: a keychain slot's description differs between an unavailable and a working backend, so it
     * cannot serve as the key.
     */
    private fun readKey(
        slot: KeySlot,
        subject: String,
        problems: KeySlotProblemReporter,
    ): Result<ByteArray?> {
        val stored =
            try {
                errorsAsExceptions { slot.read() }
            } catch (e: Exception) {
                problems.failed(subject, e) { "Cannot read the local-secrets key from ${slot.description} right now" }
                return Result.failure(e)
            }
        problems.recovered(subject) { "The local-secrets key can be read from ${slot.description} again" }
        val key = stored?.takeUnless { it.isBlank() }?.let(::decodeKey)
        val malformedSubject = subject + MALFORMED_SUFFIX
        if (key == null && !stored.isNullOrBlank()) {
            problems.failed(malformedSubject, cause = null) { "Ignoring a malformed local-secrets key in ${slot.description}" }
        } else {
            problems.recovered(malformedSubject) { "${slot.description} no longer holds a malformed local-secrets key" }
        }
        return Result.success(key)
    }

    /** The key [stored] encodes, or null when it is not Base64 or not [KEY_BYTES] long. */
    private fun decodeKey(stored: String): ByteArray? {
        val bytes =
            try {
                Base64.getDecoder().decode(stored.trim())
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (bytes.size == KEY_BYTES) return bytes
        bytes.fill(0)
        return null
    }

    /** Null when [slot] took [encoded]; otherwise the failure, a native `Error` included ([errorsAsExceptions]). */
    private fun writeOrFailure(
        slot: ReplaceableKeySlot,
        encoded: String,
    ): Exception? =
        try {
            errorsAsExceptions { slot.write(encoded) }
            null
        } catch (e: Exception) {
            e
        }
}
