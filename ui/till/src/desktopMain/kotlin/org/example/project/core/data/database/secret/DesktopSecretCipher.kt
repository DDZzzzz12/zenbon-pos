package org.example.project.core.data.database.secret

import co.touchlab.kermit.Logger
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [SecretCipher] for the desktop till: software AES-256-GCM with keys from [keySource] — in production
 * [OsDesktopKeySource], i.e. the OS keychain and an owner-only key file. A copied database carries nothing that opens the
 * stored secrets. A backup of the whole home directory is different when the key file is in use: it includes that file,
 * and with it the key.
 *
 * **Several candidate keys.** A key may sit in the keychain, in the file, or in both, so every readable key is a
 * candidate ([LoadedKeys]), keychain first:
 * - [seal] uses the first candidate. When there is none it creates one ([DesktopKeySource.loadOrCreate]), serialized by a
 *   lock so concurrent first seals create exactly one key. A keychain whose read threw or did not answer is never written,
 *   so a locked, unusable or hung keychain on a fresh install gets a file key instead of blocking pairing forever.
 * - [open] tries every candidate, then every other key this instance has held (below). A key created in the file while
 *   the keychain was locked keeps opening its values after the keychain comes back, and a keychain key keeps opening its
 *   values while the keychain is locked and a file key exists. A file key stays the sealing key only while no keychain key
 *   is readable. Nothing is migrated between slots, and nothing needs to be.
 *
 * **Keys held before.** Every key a result carried — loaded, created, or standing in — is remembered for the life of the
 * instance, and [open] tries the ones the current result lacks after its candidates. The current result can lack a key
 * this instance sealed with: a keychain session that answers one caller late can return a complete load read just before
 * another caller, refused the keychain, created a file key, and that complete load is then cached for good. Keys are never
 * deleted from their slots, so a key this instance held still opens exactly what it sealed.
 *
 * **Never openable (null from [open]).** A malformed value, or one that no key opens ([AEADBadTagException]) — neither a
 * candidate nor a key held before — when the load that produced the candidates read every slot and found a key:
 * - If a slot could not be read, the key that opens the value may be in there, so [open] throws and the store keeps the
 *   value.
 * - With no candidate at all [open] throws too, unless a key held before opens the value; it never creates a key. That
 *   includes a complete load that found every slot empty: a stored value proves a key once existed, and a misread at
 *   autostart is as plausible as a wipe. When every slot is empty, values read as absent and are kept until the first new
 *   key exists; after that, a value no key opens is discarded.
 * - A fresh complete load decides at once. A cached one is checked again first: desktop has no single-instance guard, so
 *   another instance of the app may have created a file key, and sealed under it, after this instance cached its load.
 *   [open] loads the keys once more, holds and tries any new ones, and returns null only when that reload is complete,
 *   holds a key, and still nothing opens the value. A reload that throws or is incomplete makes [open] throw. At most one
 *   such reload runs per [incompleteReuse], measured on [clock]; within that window the latest reload's verdict stands.
 *
 * **Loading keys.** Secrets are read on token load and refresh, login, pairing and settings, and a load can cost a whole
 * keychain session — up to [OsDesktopKeySource]'s timeout, plus a WARN for an unreadable slot. So:
 * - A complete result with at least one key is cached for the life of the instance.
 * - An incomplete result (a locked keychain, a keychain backend that cannot be opened, an unreadable file, the key file
 *   alone while the keychain does not answer) is reused for [incompleteReuse], measured on [clock] from its load, and
 *   loaded again after that, so the cipher picks up the keychain once it unlocks.
 * - A complete result with no key is reused the same way, not cached, so the first [seal] still creates a key. Until then
 *   a till whose slots are all empty does not pay a keychain session (on GNOME and KWallet a probe write, read and delete)
 *   and an INFO line for every read.
 * - A keychain that does not answer costs nothing more: [OsDesktopKeySource] then serves the key file alone, as an
 *   incomplete result, so a till whose keychain never answers still seals under a file key and opens what that key sealed.
 * - When a load throws (for example, because the keychain does not answer and the key file cannot serve the keys alone
 *   either), the most recent result that held a key stands in, however old, and counts as incomplete: [open] tries its
 *   candidates and the keys held before and throws when none opens the value, and [seal] uses its first key. That is safe
 *   because keys are never deleted from their slots, so each candidate still opens what it sealed. The stand-in is then
 *   reused for [incompleteReuse] like any incomplete result. With no such result, the load's exception propagates.
 *
 * **Logging.** The slot the sealing key came from ([LoadedKeys.sealingKeySlot], e.g. "OS keychain (GNOME_KEYRING)" or the
 * key file) is logged at INFO the first time a key is loaded, and again only if that slot changes. Standing in with the
 * last known keys is warned about once, when it starts, and noted at INFO once a load succeeds again. Never key material.
 *
 * **Errors.** An `Error` from the key source (JNA's `UnsatisfiedLinkError`, `NoClassDefFoundError`) is reported as an
 * [IllegalStateException]; only a [VirtualMachineError] passes through.
 *
 * **Thread safety.** Each call creates its own [Cipher]; loaded results are held in `@Volatile` fields, and the keys held
 * before in a copy-on-write list. No key array is ever wiped: a result may be in use on another thread, and the last known
 * keys must outlive a failed load.
 *
 * **Residual risks.**
 * - When a key's slot is wiped, the values sealed under it read as absent and are kept until the first new key exists;
 *   after that, a value no key opens is discarded. The till re-pairs.
 * - A second instance running at the same time that creates a file key and seals under it within [incompleteReuse] of this
 *   instance's latest reload has that value discarded by this instance.
 * - The file fallback is only as strong as the home directory's permissions, and travels with any backup of it.
 * - Key material stays in memory for the life of the instance.
 */
class DesktopSecretCipher(
    private val keySource: DesktopKeySource,
    private val clock: () -> Long = ::monotonicMillis,
    private val incompleteReuse: Duration = DEFAULT_INCOMPLETE_REUSE,
) : SecretCipher {
    /** A complete result with at least one key, kept for the life of the instance. */
    @Volatile
    private var cached: LoadedKeys? = null

    /** The latest result that is not cacheable — incomplete, or complete with no key — and the [clock] reading it was loaded at. */
    @Volatile
    private var recentUncacheable: Pair<LoadedKeys, Long>? = null

    /**
     * The [clock] reading when a cached load last reloaded before discarding a value, and whether that reload may discard
     * one: it was complete and held a key. False while the reload runs and when it threw.
     */
    @Volatile
    private var recentReload: Pair<Boolean, Long>? = null

    /** The latest result that held a key, however old. Handed out only as incomplete, when a load throws. */
    @Volatile
    private var lastKnown: LoadedKeys? = null

    /** Every key a result carried, each once by content, in the order first seen. Added to under [knownKeysLock]. */
    private val knownKeys = CopyOnWriteArrayList<ByteArray>()

    private val knownKeysLock = Any()
    private val lock = Any()
    private val random = SecureRandom()
    private val log = Logger.withTag("DesktopSecretCipher")
    private val reportedKeySlot = AtomicReference<String?>(null)
    private val standingIn = AtomicBoolean(false)

    override fun seal(
        key: String,
        plaintext: String,
    ): String {
        require(plaintext.isNotEmpty()) { "An empty value is never sealed" }
        val loaded = currentKeys().takeIf { it.keys.isNotEmpty() } ?: keysCreatingOneIfNone()
        return encrypt(loaded.keys.first(), key, plaintext)
    }

    override fun open(
        key: String,
        sealed: String,
    ): String? {
        val bytes = SealedSecret.decode(sealed) ?: return null
        val fromCache = cached
        val loaded = fromCache ?: reusableUncacheable() ?: loadOrStandIn { keySource.load() }
        openWith(loaded, key, bytes)?.let { return it }
        check(loaded.keys.isNotEmpty()) { "Local-secrets key not available" }
        check(loaded.complete) { NOT_OPENED_RIGHT_NOW }
        return if (fromCache == null) null else openAfterReload(key, bytes)
    }

    /**
     * Before a cached load discards [bytes]: loads the keys again, unless a reload ran within [incompleteReuse], in which
     * case that reload's verdict stands. The plaintext if a key opens [bytes]; null when the reload was complete and held a
     * key; otherwise throws.
     */
    private fun openAfterReload(
        key: String,
        bytes: ByteArray,
    ): String? {
        val now = clock()
        recentReload?.let { (mayDiscard, reloadedAt) ->
            if (now - reloadedAt < incompleteReuse.inWholeMilliseconds) {
                check(mayDiscard) { NOT_OPENED_RIGHT_NOW }
                return null
            }
        }
        recentReload = false to now
        val fresh = errorsAsExceptions { keySource.load() }
        remember(fresh)
        recentReload = fresh.isCacheable() to now
        openWith(fresh, key, bytes)?.let { return it }
        check(fresh.isCacheable()) { NOT_OPENED_RIGHT_NOW }
        return null
    }

    /** The cached keys, a recent uncacheable result, or a fresh load — the last known keys when it throws. Never creates a key. */
    private fun currentKeys(): LoadedKeys = cached ?: reusableUncacheable() ?: loadOrStandIn { keySource.load() }

    /** Under the lock, so concurrent first seals create one key: a key loaded by now if there is one, else a new one. */
    private fun keysCreatingOneIfNone(): LoadedKeys =
        synchronized(lock) {
            cached ?: reusableUncacheable()?.takeIf { it.keys.isNotEmpty() } ?: loadOrStandIn { keySource.loadOrCreate() }
        }

    /** The plaintext of [bytes] under the first of [loaded]'s candidates or the keys held before that opens it, or null. */
    private fun openWith(
        loaded: LoadedKeys,
        preferenceKey: String,
        bytes: ByteArray,
    ): String? = keysToTry(loaded).firstNotNullOfOrNull { candidate -> decrypt(candidate, preferenceKey, bytes) }

    /** [loaded]'s candidates, then every key held before that is not among them. */
    private fun keysToTry(loaded: LoadedKeys): Sequence<ByteArray> =
        loaded.keys.asSequence() + knownKeys.asSequence().filterNot { known -> loaded.keys.any { it.contentEquals(known) } }

    /** The latest uncacheable result, while it is younger than [incompleteReuse]. */
    private fun reusableUncacheable(): LoadedKeys? {
        val (loaded, loadedAt) = recentUncacheable ?: return null
        return loaded.takeIf { clock() - loadedAt < incompleteReuse.inWholeMilliseconds }
    }

    /** Runs [load] and remembers its result. When it throws, the last known keys stand in; with none, the failure propagates. */
    private fun loadOrStandIn(load: () -> LoadedKeys): LoadedKeys {
        val loaded =
            try {
                errorsAsExceptions { load() }
            } catch (e: Exception) {
                return lastKnownStandingIn(e)
            }
        remember(loaded)
        return loaded
    }

    /** The last known keys as an incomplete result, reused like one. Rethrows [failure] when no load ever held a key. */
    private fun lastKnownStandingIn(failure: Exception): LoadedKeys {
        val standIn = lastKnown?.copy(complete = false) ?: throw failure
        holdKeysOf(standIn)
        recentUncacheable = standIn to clock()
        if (standingIn.compareAndSet(false, true)) {
            log.w(failure) { "Cannot load the local-secrets keys right now — using the last keys loaded until a load succeeds" }
        }
        return standIn
    }

    /**
     * Keeps a fresh [loaded]: its keys as held, before anything can be sealed under them; the result cached when
     * [isCacheable], otherwise reusable for a while; last known when it holds a key.
     */
    private fun remember(loaded: LoadedKeys) {
        holdKeysOf(loaded)
        if (loaded.keys.isNotEmpty()) lastKnown = loaded
        if (loaded.isCacheable()) cached = loaded
        recentUncacheable = if (loaded.isCacheable()) null else loaded to clock()
        if (standingIn.compareAndSet(true, false)) log.i { "The local-secrets keys load again" }
        reportSealingKeySlot(loaded)
    }

    /** Adds each key of [loaded] not held yet to [knownKeys]. */
    private fun holdKeysOf(loaded: LoadedKeys) {
        for (candidate in loaded.keys) {
            if (knownKeys.any { it.contentEquals(candidate) }) continue
            synchronized(knownKeysLock) {
                if (knownKeys.none { it.contentEquals(candidate) }) knownKeys += candidate
            }
        }
    }

    private fun reportSealingKeySlot(loaded: LoadedKeys) {
        val slot = loaded.sealingKeySlot ?: return
        if (reportedKeySlot.getAndSet(slot) != slot) log.i { "Local-secrets key: $slot" }
    }

    private fun LoadedKeys.isCacheable(): Boolean = complete && keys.isNotEmpty()

    private fun encrypt(
        keyBytes: ByteArray,
        preferenceKey: String,
        plaintext: String,
    ): String {
        val iv = ByteArray(SealedSecret.IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, KEY_ALGORITHM), GCMParameterSpec(SealedSecret.TAG_BITS, iv))
        cipher.updateAAD(preferenceKey.encodeToByteArray())
        return SealedSecret.encode(iv + cipher.doFinal(plaintext.encodeToByteArray()))
    }

    /** The plaintext, or null when [keyBytes] does not authenticate [bytes] for [preferenceKey]. */
    private fun decrypt(
        keyBytes: ByteArray,
        preferenceKey: String,
        bytes: ByteArray,
    ): String? {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, KEY_ALGORITHM), GCMParameterSpec(SealedSecret.TAG_BITS, bytes, 0, SealedSecret.IV_LENGTH))
        cipher.updateAAD(preferenceKey.encodeToByteArray())
        return try {
            cipher.doFinal(bytes, SealedSecret.IV_LENGTH, bytes.size - SealedSecret.IV_LENGTH).decodeToString()
        } catch (_: AEADBadTagException) {
            null
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val NOT_OPENED_RIGHT_NOW = "Sealed secret did not open with the keys readable right now"
        private val DEFAULT_INCOMPLETE_REUSE: Duration = 30.seconds

        /** The production cipher: keys in the OS keychain and the owner-only key file. */
        fun fromOs(): DesktopSecretCipher = DesktopSecretCipher(OsDesktopKeySource())
    }
}
