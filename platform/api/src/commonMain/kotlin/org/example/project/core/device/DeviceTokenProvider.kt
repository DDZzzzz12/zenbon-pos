package org.example.project.core.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Process-global holder for the paired device token, which the shared HTTP client attaches as `X-Device-Token` (see
 * [org.example.project.core.network.createPosHttpClient]).
 *
 * Mirrors [DeviceInfoProvider]: a lightweight global read at request time, so the HTTP-client factory does not need a
 * `LocalDatabaseManager` dependency. adminApp never pairs and sets no [loader], so the value stays null there.
 *
 * On composeApp `LocalDatabaseManager` is the source of truth: it sets [token] on pairing, clears it on unpairing, and
 * is installed as the [loader] — it does NOT read the token when constructed. The token is sealed, so opening it can
 * block for seconds (a keychain that does not answer) and can fail for now (Keystore not ready at boot, keychain
 * locked), in which case the store keeps the value and reads it as absent. A read taken once at construction ran on
 * whichever thread built the manager, and a boot that caught the key store unready sent no device token until a
 * restart. [currentToken] instead asks the loader whenever the token is missing, at most once per [RETRY_INTERVAL].
 *
 * The token is written from the pairing flow and read from the Ktor request pipeline, potentially on different threads.
 * It sits in an [AtomicReference] to an immutable holder, and every write installs a new holder, so a load caches what
 * it read by compare-and-set against the holder it started from — never by a check followed by an assignment, which a
 * write could slip between. `kotlin.concurrent` (not `java.util.concurrent` or the JVM-only `kotlin.jvm.Volatile`)
 * because this file compiles for wasmJs too.
 */
@OptIn(ExperimentalAtomicApi::class)
object DeviceTokenProvider {
    /** How long a load that found nothing, or failed, stands before the loader is asked again. */
    val RETRY_INTERVAL = 5.seconds

    private val loadLock = Mutex()

    private val held = AtomicReference(Held(null))

    @Volatile
    private var lastLoad: ComparableTimeMark? = null

    /**
     * The token in memory, or null when none is held. A write is authoritative: a load in flight when it lands — a
     * clear included — is discarded, however closely the two interleave, so unpairing cannot be undone by a read of the
     * old value.
     */
    var token: String?
        get() = held.load().token
        set(value) {
            held.store(Held(value))
        }

    /** Marks the holder as it is now, for [seedIfUnchangedSince]. Take it before reading the persisted token. */
    fun writeMark(): WriteMark = WriteMark(held.load())

    /**
     * Holds [token] only if nothing has written the holder since [mark] was taken — no write, no clear, no load that
     * cached — in one compare-and-set. Returns whether it now holds [token].
     *
     * For a caller that read the persisted token itself: assigning [token] instead would let a clear that landed during
     * that read be undone by the value read before it. When this returns false, the holder already carries whatever the
     * later write put there.
     */
    fun seedIfUnchangedSince(
        mark: WriteMark,
        token: String,
    ): Boolean = held.compareAndSet(mark.held, Held(token))

    /**
     * Reads the persisted token, or null when there is none or it cannot be read right now.
     *
     * Must not block the calling thread — composeApp's loader switches to the IO dispatcher — and must not send a
     * request through the shared HTTP client, which would wait on the lock this loader runs under.
     */
    @Volatile
    var loader: (suspend () -> String?)? = null

    /** The clock [RETRY_INTERVAL] is measured on; tests substitute a `TestTimeSource`. Setting it restarts the interval. */
    @Volatile
    var timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic
        set(value) {
            field = value
            lastLoad = null
        }

    /**
     * The token for a request about to be sent: the one in memory, else what [loader] reads, cached once non-null.
     *
     * Concurrent callers share one load — the second waits for the first's answer rather than going out without the
     * header, which a terminal route would refuse. A load that found nothing or threw is not repeated within
     * [RETRY_INTERVAL], so a key store that does not answer costs its timeout once per interval, not once per request.
     */
    suspend fun currentToken(): String? {
        token?.let { return it }
        val load = loader ?: return null
        return loadLock.withLock { token ?: loadUnlessTriedRecently(load) }
    }

    /** Drops the token, the loader and the retry state. The holder is process-global, so tests call this after each test. */
    fun reset() {
        loader = null
        token = null
        timeSource = TimeSource.Monotonic
    }

    private suspend fun loadUnlessTriedRecently(load: suspend () -> String?): String? {
        if (lastLoad?.let { it.elapsedNow() < RETRY_INTERVAL } == true) return null
        val before = held.load()
        val loaded = loadOrNull(load)
        lastLoad = timeSource.markNow()
        return cacheUnlessWrittenSince(before, loaded)
    }

    /**
     * Caches [loaded] only if [token] has not been written since [before] was taken, in one compare-and-set. When a write
     * landed in between, the load is stale and the written value stands; a load that found nothing caches nothing.
     */
    private fun cacheUnlessWrittenSince(
        before: Held,
        loaded: String?,
    ): String? {
        if (loaded != null && held.compareAndSet(before, Held(loaded))) return loaded
        val current = held.load()
        return if (current === before) null else current.token
    }

    private suspend fun loadOrNull(load: suspend () -> String?): String? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** The holder as [writeMark] found it. Opaque: all a caller can do with it is pass it to [seedIfUnchangedSince]. */
    class WriteMark internal constructor(
        internal val held: Held,
    )

    /** One value of [token]. Compared by identity: each write allocates a new one, so equal tokens still differ. */
    internal class Held(
        val token: String?,
    )
}
