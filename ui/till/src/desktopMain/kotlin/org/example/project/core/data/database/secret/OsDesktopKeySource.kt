package org.example.project.core.data.database.secret

import co.touchlab.kermit.Logger
import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * The production [DesktopKeySource]: the OS keychain ([OsKeyringKeySlot]) and a [FileKeySlot] at `fallbackFile`. Which
 * keys count and where a new one goes live in [DesktopMasterKey].
 *
 * Every keychain session opens the keychain through [keyringFactory], runs [DesktopMasterKey] over both slots, and closes
 * it again, so nothing native is held between calls. [DesktopSecretCipher] caches a complete result and reuses an
 * incomplete one for a while, so this happens only a few times per run. The constructor performs no IO.
 *
 * **Bounded wait.** A keychain call can block for minutes. A locked Secret Service collection shows an unlock dialog and
 * waits up to 120 s in `Keyring.create()` and again in every read and write, and KWallet waits on its wallet dialog. On an
 * auto-login kiosk the collection stays locked on every boot, and the till reads its secrets while it boots, unattended.
 * So each whole session runs on one daemon thread, `local-secrets-keychain`, and the caller waits at most
 * [KeychainCallPolicy.timeout] of [policy]:
 * - A call that arrives while another is merely in progress, not yet timed out, waits its turn. Sessions never overlap.
 * - On timeout the session is left running, not interrupted, because a native call may not honour an interrupt.
 * - A queued session whose caller timed out, or was interrupted, before the session started is skipped when its turn comes
 *   and never opens the keychain. Otherwise N reads queued at boot behind a locked keyring would show N unlock dialogs, one
 *   after another.
 * - A caller whose session answers after its timeout fired, but before that timeout is recorded, gets the answer.
 * - While an abandoned session is still running, no new session starts, however long ago the timeout was.
 * - Nor does one start during the back-off after a timeout, measured on [clock] from the moment it happened, even once the
 *   abandoned session has finished. A keychain that has just hung is likely to hang again, each attempt would cost its
 *   caller another timeout, and on a locked kiosk each new session pops the unlock dialog again. After the n-th timeout
 *   in a row the back-off is [KeychainCallPolicy.backoff] × 2^(n−1), at most [KeychainCallPolicy.maxBackoff]. Callers
 *   that time out queued behind the same hang count as one timeout. A session that answers its caller in time, with a
 *   result or an exception, resets the count; an abandoned session that finishes late does not.
 *
 * **The key file alone.** Whenever no session can run — it timed out, an abandoned one is still running, or the back-off
 * lasts — the call reads the key file on the calling thread instead and never touches the keychain
 * ([DesktopMasterKey.loadFallbackOnly], [DesktopMasterKey.loadOrCreateFallbackOnly]):
 * - The result carries the file key, if there is one, and is never complete. [DesktopSecretCipher] seals with that key and
 *   keeps any value it does not open, and [loadOrCreate] creates a key in the file when there is none. That is how a till
 *   whose keychain never answers can pair at all.
 * - The key is created only if absent ([FallbackKeySlot.writeIfAbsent]), so an abandoned session that later reaches its
 *   own file write keeps that key instead of replacing it.
 * - Once the keychain answers again, the candidates are the keychain key and the file key, and each opens what it sealed.
 * - The switch to the file alone, and back, is logged through [problems] when it happens, not on every call.
 * - When the key file cannot serve the call either — it cannot be read, or cannot take a new key — the call throws an
 *   [IllegalStateException] ("OS keychain did not answer within …"). [DesktopSecretCipher] then uses its last known keys,
 *   or throws, and the store keeps its values. A readable key file that holds no key is an answer, not a failure: the
 *   empty result is returned (see [fromFileAlone]).
 *
 * **No keychain, or not right now?**
 * - `BackendNotSupportedException` from `Keyring.create()` covers an unsupported OS, but also a D-Bus session that is not
 *   up yet at autostart, an unreachable KWallet, and antivirus blocking JNA's library (java-keyring reports a native
 *   `UnsatisfiedLinkError` inside a backend that way). It therefore becomes an [UnavailableKeySlot]: the load is
 *   incomplete, never cached, and no key is written to the keychain. Any other [LinkageError] is treated the same way.
 * - Only a [NoClassDefFoundError] for java-keyring's own classes means this build has no keychain binding. That is
 *   deterministic, so there is no keychain slot and the load can be complete.
 * - Both cases are warned about once per instance. Any other exception opening the keychain propagates.
 *
 * **Slot problems** — a slot that becomes unreadable or readable again, a keychain probe that starts or stops failing, a
 * keychain that stops or starts answering — are logged through [problems], which lives as long as this source. The slots
 * themselves are built for every session, so they cannot remember that a problem was already reported.
 *
 * An `Error` escaping from the keychain binding is reported as an [IllegalStateException] ([errorsAsExceptions]).
 */
class OsDesktopKeySource(
    fallbackFile: File = FileKeySlot.defaultLocation(),
    private val keyringFactory: () -> KeyringEntries = { JavaKeyringEntries(Keyring.create()) },
    private val policy: KeychainCallPolicy = KeychainCallPolicy(),
    private val clock: () -> Long = ::monotonicMillis,
    private val problems: KeySlotProblemReporter = KeySlotProblemReporter(),
) : DesktopKeySource {
    private val log = Logger.withTag("DesktopKeySource")
    private val fallback = FileKeySlot(fallbackFile)
    private val keychainProblemReported = AtomicBoolean(false)
    private val keychainThread: ExecutorService =
        ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { task ->
            Thread(task, THREAD_NAME).apply { isDaemon = true }
        }
    private val submitLock = Any()

    /** The call submitted last. Guarded by [submitLock]. */
    private var newestCall: Future<*>? = null

    /** The newest call when a caller last timed out; until it finishes, no call is submitted. Guarded by [submitLock]. */
    private var unansweredCall: Future<*>? = null

    /** The [clock] reading when a caller last timed out. Guarded by [submitLock]. */
    private var timedOutAt: Long? = null

    /** Timeouts since a session last answered its caller in time; they set the back-off. Guarded by [submitLock]. */
    private var consecutiveTimeouts = 0

    override fun load(): LoadedKeys =
        withKeychainOrFileAlone(
            session = { keychain -> DesktopMasterKey.load(keychain, fallback, problems) },
            fileAlone = { DesktopMasterKey.loadFallbackOnly(fallback, problems) },
        )

    override fun loadOrCreate(): LoadedKeys =
        withKeychainOrFileAlone(
            session = { keychain -> DesktopMasterKey.loadOrCreate(keychain, fallback, problems) },
            fileAlone = { DesktopMasterKey.loadOrCreateFallbackOnly(fallback, problems) },
        )

    /** [session] in a keychain session, or [fileAlone] on the calling thread when no session can run. */
    private fun withKeychainOrFileAlone(
        session: (ReplaceableKeySlot?) -> LoadedKeys,
        fileAlone: () -> LoadedKeys,
    ): LoadedKeys =
        errorsAsExceptions {
            onKeychainThread(
                call = { withOpenedKeychain(session) },
                whenNotAnswering = { reason -> fromFileAlone(reason, fileAlone) },
            )
        }

    /** Opens the keychain, runs [action] over its slot, and closes the keychain again. Runs on the keychain thread. */
    private fun <T> withOpenedKeychain(action: (ReplaceableKeySlot?) -> T): T {
        val entries =
            try {
                keyringFactory()
            } catch (e: BackendNotSupportedException) {
                return action(unavailableKeychain(e))
            } catch (e: LinkageError) {
                return action(if (isMissingJavaKeyring(e)) noKeychainBinding(e) else unavailableKeychain(e))
            }
        try {
            return action(OsKeyringKeySlot(entries, problems))
        } finally {
            closeQuietly(entries)
        }
    }

    /**
     * [fileAlone] in place of a keychain session that cannot run for [reason]; the switch is logged when it starts.
     *
     * Throws when the key file could not be read: such a result read no slot at all, and throwing lets [DesktopSecretCipher]
     * stand in with its last known keys. A readable file that holds no key is returned as the empty, incomplete result it
     * is. The cipher's last known keys do not stand in for it, but [DesktopSecretCipher.open] still tries every key the
     * cipher has held, and [loadOrCreate] creates a key in the file.
     */
    private fun fromFileAlone(
        reason: Throwable,
        fileAlone: () -> LoadedKeys,
    ): LoadedKeys {
        problems.failed(SESSION_SUBJECT, reason) { "${reason.message} — using ${fallback.description} alone until it answers" }
        val loaded =
            try {
                fileAlone()
            } catch (e: Exception) {
                throw fileCannotServe(reason).apply { addSuppressed(e) }
            }
        if (!loaded.fallbackReadable) throw fileCannotServe(reason)
        return loaded
    }

    private fun fileCannotServe(reason: Throwable): IllegalStateException =
        IllegalStateException("${reason.message}, and ${fallback.description} cannot serve the local-secrets key alone", reason)

    /**
     * Runs [call] on the keychain thread and waits at most [KeychainCallPolicy.timeout] for it; [whenNotAnswering] with the
     * reason when no session can run. A call whose caller gave up is flagged, so its session skips the keychain if it has
     * not started by then.
     */
    private fun onKeychainThread(
        call: () -> LoadedKeys,
        whenNotAnswering: (reason: Throwable) -> LoadedKeys,
    ): LoadedKeys {
        val abandoned = AtomicBoolean(false)
        val future = submitUnlessUnanswered { if (abandoned.get()) null else call() }.getOrElse { reason -> return whenNotAnswering(reason) }
        val timedOut = awaitWithinTimeout(future, abandoned) ?: return answerOf(future)
        if (abandonUnlessDone(future, abandoned)) return whenNotAnswering(IllegalStateException(notAnsweringMessage(), timedOut))
        return answerOf(future)
    }

    /**
     * Null once [future] is done within the timeout, with an answer or an exception; otherwise the [TimeoutException].
     *
     * A caller interrupted while it waits gives up on its call as a timed-out one does: the call is flagged [abandoned]
     * under [submitLock], so its session skips the keychain if it has not started. An interrupt says nothing about the
     * keychain, so no timeout is recorded and no back-off starts. The interrupt status is restored and the wait fails.
     */
    private fun awaitWithinTimeout(
        future: Future<*>,
        abandoned: AtomicBoolean,
    ): TimeoutException? =
        try {
            future.get(policy.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            null
        } catch (e: TimeoutException) {
            e
        } catch (_: ExecutionException) {
            null
        } catch (e: InterruptedException) {
            synchronized(submitLock) { abandoned.set(true) }
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for the OS keychain", e)
        }

    /**
     * The answer of [future]: a session that is done and whose caller never abandoned it, so it answered that caller and
     * the back-off count starts over. Rethrows the session's exception.
     */
    private fun answerOf(future: Future<LoadedKeys?>): LoadedKeys {
        sessionAnswered()
        val answer =
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        return checkNotNull(answer) { "A skipped keychain session was handed to its caller" }
    }

    /** Queues [call] behind any call in progress, unless a call that timed out is still running or the back-off lasts: then the reason. */
    private fun submitUnlessUnanswered(call: () -> LoadedKeys?): Result<Future<LoadedKeys?>> =
        synchronized(submitLock) {
            val refusal = refusalReason()
            if (refusal != null) {
                Result.failure(IllegalStateException(refusal))
            } else {
                Result.success(keychainThread.submit(Callable { call() }).also { newestCall = it })
            }
        }

    /** Why no session may start right now, or null. Called under [submitLock]. */
    private fun refusalReason(): String? =
        when {
            unansweredCall?.isDone == false -> "${notAnsweringMessage()}, and that call is still running"
            isBackingOff() -> "${notAnsweringMessage()}; not asking it again until ${currentBackoff()} after that"
            else -> null
        }

    /**
     * Records that the caller of [future] timed out, and returns true — unless [future] is done by now: then its session
     * answered after all, nothing is recorded, and this returns false. Checking and flagging under [submitLock] means a
     * caller is never handed the answer of a session that skipped the keychain.
     *
     * Recording flags the call [abandoned], so its session skips the keychain if it has not started; it notes the moment,
     * which starts the back-off, and the call to wait for. One thread, first in first out: the newest call finishes last,
     * so while it runs, so does every call ahead of it. A caller that times out while the call it waited behind is already
     * unanswered is part of the same hang, so it does not lengthen the back-off.
     */
    private fun abandonUnlessDone(
        future: Future<*>,
        abandoned: AtomicBoolean,
    ): Boolean {
        synchronized(submitLock) {
            if (future.isDone) return false
            abandoned.set(true)
            if (unansweredCall?.isDone != false) consecutiveTimeouts++
            unansweredCall = newestCall
            timedOutAt = clock()
            return true
        }
    }

    /** A session answered its caller in time, so the back-off count starts over. */
    private fun sessionAnswered() {
        synchronized(submitLock) { consecutiveTimeouts = 0 }
        problems.recovered(SESSION_SUBJECT) { "The OS keychain answers again — keys load from it and ${fallback.description}" }
    }

    /** True within [currentBackoff] of the last timeout. Called under [submitLock]. */
    private fun isBackingOff(): Boolean {
        val since = timedOutAt ?: return false
        return clock() - since < currentBackoff().inWholeMilliseconds
    }

    /** The back-off × 2^(n−1) after the n-th timeout in a row, at most the longest one; zero after none. Called under [submitLock]. */
    private fun currentBackoff(): Duration {
        if (consecutiveTimeouts == 0) return Duration.ZERO
        var window = policy.backoff
        repeat(consecutiveTimeouts - 1) {
            if (window >= policy.maxBackoff) return policy.maxBackoff
            window *= 2
        }
        return minOf(window, policy.maxBackoff)
    }

    private fun notAnsweringMessage(): String = "OS keychain did not answer within ${policy.timeout}"

    private fun unavailableKeychain(cause: Throwable): ReplaceableKeySlot {
        reportKeychainProblem(cause) { "The OS keychain cannot be opened right now — using ${fallback.description} until it can" }
        return UnavailableKeySlot(UNAVAILABLE_DESCRIPTION, cause)
    }

    private fun noKeychainBinding(cause: LinkageError): ReplaceableKeySlot? {
        reportKeychainProblem(cause) { "This build has no OS keychain binding — the local-secrets key is kept in ${fallback.description}" }
        return null
    }

    private fun reportKeychainProblem(
        cause: Throwable,
        message: () -> String,
    ) {
        if (keychainProblemReported.compareAndSet(false, true)) log.w(cause) { message() }
    }

    private fun closeQuietly(entries: KeyringEntries) {
        try {
            entries.close()
        } catch (e: Exception) {
            log.d(e) { "Closing the OS keychain failed" }
        }
    }

    private companion object {
        const val IDLE_SECONDS = 30L
        const val THREAD_NAME = "local-secrets-keychain"
        const val UNAVAILABLE_DESCRIPTION = "OS keychain (unavailable)"
        const val SESSION_SUBJECT = "OS keychain session"
        const val JAVA_KEYRING_CLASSES = "com/github/javakeyring/"

        /** A class of java-keyring itself cannot be found: this build has no keychain binding, and no retry changes that. */
        fun isMissingJavaKeyring(e: LinkageError): Boolean {
            if (e !is NoClassDefFoundError) return false
            return e.message
                .orEmpty()
                .replace('.', '/')
                .startsWith(JAVA_KEYRING_CLASSES)
        }
    }
}
