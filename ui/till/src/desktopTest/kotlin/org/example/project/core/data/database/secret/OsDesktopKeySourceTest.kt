package org.example.project.core.data.database.secret

import co.touchlab.kermit.Severity
import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.KeyringStorageType
import org.example.project.testutils.fakes.CapturingLogWriter
import org.example.project.testutils.fakes.FakeKeyringEntries
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * No test here opens the real OS keychain or the real config folder: every source gets a fake factory and a temp key file.
 * Every source also runs on the test's [now] clock, so no back-off or reuse window is waited out for real.
 *
 * A keychain session built by [hangingFactory] hangs until the test hands out a [hung] permit, and releases a
 * [sessionsClosed] permit once it has closed its keychain.
 */
class OsDesktopKeySourceTest {
    private val dir = createTempDirectory("os-desktop-key-source-test").toFile()
    private val keyFile = File(dir, "pos/local-secrets.key")
    private val latches = ConcurrentLinkedQueue<CountDownLatch>()
    private val hung = Semaphore(0)
    private val sessionsClosed = Semaphore(0)
    private val now = AtomicLong()
    private val log = CapturingLogWriter()

    @AfterTest
    fun releaseBlockedKeychainCalls() {
        latches.forEach { it.countDown() }
        hung.release(RELEASE_ALL)
        dir.deleteRecursively()
    }

    @Test
    fun a_working_keychain_takes_the_new_key_and_is_closed_after_every_call() {
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val source = source({ entries })

        val created = source.loadOrCreate()
        val loaded = source.load()

        assertTrue(created.complete)
        assertTrue(created.primaryUsable)
        assertEquals(listOf(OsKeyringKeySlot.ACCOUNT), entries.writtenAccounts)
        assertFalse(keyFile.exists())
        assertContentEquals(created.keys.single(), loaded.keys.single())
        assertEquals(2, entries.closes)
    }

    @Test
    fun an_unavailable_keychain_backend_is_an_incomplete_load_not_a_missing_keychain() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val source = source({ throw BackendNotSupportedException("Error connecting to dbus") })

        val loaded = source.load()

        assertEquals(1, loaded.keys.size)
        assertFalse(loaded.complete)
        assertFalse(loaded.primaryUsable)
        assertTrue(loaded.fallbackReadable)
    }

    @Test
    fun a_load_over_an_unavailable_keychain_backend_is_reused_only_within_the_window() {
        val factoryCalls = AtomicInteger()
        val source =
            source({
                factoryCalls.incrementAndGet()
                throw BackendNotSupportedException("Error connecting to dbus")
            })
        val cipher = DesktopSecretCipher(source, clock = now::get, incompleteReuse = REUSE)

        val sealed = cipher.seal(DEVICE_TOKEN, "device-token-123")
        assertEquals("device-token-123", cipher.open(DEVICE_TOKEN, sealed))
        assertEquals(2, factoryCalls.get())
        now.addAndGet(REUSE.inWholeMilliseconds)
        assertEquals("device-token-123", cipher.open(DEVICE_TOKEN, sealed))

        assertTrue(keyFile.exists())
        assertEquals(3, factoryCalls.get())
    }

    @Test
    fun missing_java_keyring_classes_mean_no_keychain_slot_and_a_complete_load() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val source = source({ throw NoClassDefFoundError("com/github/javakeyring/Keyring") })

        val loaded = source.load()

        assertEquals(1, loaded.keys.size)
        assertTrue(loaded.complete)
        assertFalse(loaded.primaryUsable)
    }

    @Test
    fun any_other_linkage_error_opening_the_keychain_is_an_incomplete_load() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val errors =
            listOf(
                UnsatisfiedLinkError("jnidispatch blocked"),
                NoClassDefFoundError("com/sun/jna/Native"),
                NoClassDefFoundError("Could not initialize class com.github.javakeyring.Keyring"),
            )
        for (error in errors) {
            val loaded = source({ throw error }).load()

            assertEquals(1, loaded.keys.size, "$error")
            assertFalse(loaded.complete, "$error")
        }
    }

    @Test
    fun a_keychain_that_does_not_answer_and_a_key_file_that_cannot_be_read_fail_the_call_after_the_timeout() {
        assertTrue(keyFile.mkdirs(), "a folder where the key file belongs makes every read of it throw")
        val source = source(blockingFactory(latch()), timeout = HANG_TIMEOUT)

        val started = System.nanoTime()
        val thrown = assertFailsWith<IllegalStateException> { source.load() }
        val elapsed = (System.nanoTime() - started).nanoseconds
        val duringTheHang = assertFailsWith<IllegalStateException> { source.loadOrCreate() }

        assertTrue(elapsed >= HANG_TIMEOUT, "returned after $elapsed")
        assertTrue(elapsed < MARGIN, "returned after $elapsed")
        assertTrue(thrown.message.orEmpty().startsWith(NOT_ANSWERING), thrown.message)
        assertTrue(duringTheHang.message.orEmpty().startsWith(NOT_ANSWERING), duringTheHang.message)
        assertTrue(keyFile.isDirectory)
    }

    @Test
    fun when_the_keychain_hangs_load_serves_the_key_file_and_never_touches_the_keychain_again_in_that_call() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val factoryCalls = AtomicInteger()
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val source = source(hangingFactory(entries, factoryCalls), timeout = HANG_TIMEOUT)

        val started = System.nanoTime()
        val loaded = source.load()
        val elapsed = (System.nanoTime() - started).nanoseconds

        assertTrue(elapsed >= HANG_TIMEOUT, "returned after $elapsed")
        assertContentEquals(KEY, loaded.keys.single())
        assertFalse(loaded.primaryUsable)
        assertFalse(loaded.complete)
        assertTrue(loaded.fallbackReadable)
        assertEquals(FileKeySlot(keyFile).description, loaded.sealingKeySlot)
        assertEquals(1, factoryCalls.get())
        assertTrue(entries.readAccounts.isEmpty())
        assertEquals(1, log.messages(Severity.Warn).count { it.startsWith(NOT_ANSWERING) }, "${log.messages(Severity.Warn)}")
    }

    @Test
    fun when_the_keychain_hangs_seal_can_create_a_file_key() {
        val factoryCalls = AtomicInteger()
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val source = source(hangingFactory(entries, factoryCalls), timeout = HANG_TIMEOUT)

        val created = source.loadOrCreate()
        hung.release()
        awaitSessionClosed()

        assertContentEquals(Base64.getDecoder().decode(FileKeySlot(keyFile).read()), created.keys.single())
        assertFalse(created.complete)
        assertFalse(created.primaryUsable)
        assertEquals(FileKeySlot(keyFile).description, created.sealingKeySlot)
        assertEquals(1, factoryCalls.get())
        assertEquals(0, entries.sets)
    }

    @Test
    fun a_call_while_an_unanswered_call_is_still_running_is_served_from_the_file_even_past_the_backoff_and_a_later_call_works() {
        val release = latch()
        val factoryCalls = AtomicInteger()
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val source =
            source({
                if (factoryCalls.incrementAndGet() == 1) release.await()
                entries
            })
        source.load()
        now.set(BACKOFF.inWholeMilliseconds)

        val started = System.nanoTime()
        val servedFromFile = source.loadOrCreate()
        val elapsed = (System.nanoTime() - started).nanoseconds
        val refusal = log.messages(Severity.Debug).last { NOT_ANSWERING in it }
        release.countDown()
        val later = loadOnceTheKeychainAnswers(source)

        assertTrue(elapsed < MARGIN, "served after $elapsed")
        assertTrue(STILL_RUNNING in refusal, refusal)
        assertFalse(servedFromFile.complete)
        assertTrue(later.complete)
        assertContentEquals(servedFromFile.keys.single(), later.keys.single())
        assertEquals(2, factoryCalls.get())
        assertTrue(entries.writtenAccounts.isEmpty())
    }

    @Test
    fun during_backoff_calls_are_served_from_the_file_without_starting_a_keychain_session() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val release = latch()
        val factoryCalls = AtomicInteger()
        val source = source(clockMovingOnFirstCall(release, factoryCalls), timeout = HANG_TIMEOUT)
        source.load()
        release.countDown()
        now.set(CLOCK_AT_TIMEOUT + BACKOFF.inWholeMilliseconds - 1)

        val refusal = refusalOnceTheAbandonedCallFinished(source)
        val served = listOf(source.load(), source.loadOrCreate(), source.load())

        assertTrue(BACKING_OFF in refusal, refusal)
        for (loaded in served) {
            assertContentEquals(KEY, loaded.keys.single())
            assertFalse(loaded.complete)
            assertFalse(loaded.primaryUsable)
            assertEquals(FileKeySlot(keyFile).description, loaded.sealingKeySlot)
        }
        assertEquals(1, factoryCalls.get())
    }

    @Test
    fun a_call_after_the_backoff_window_reaches_the_keychain_again() {
        val release = latch()
        val factoryCalls = AtomicInteger()
        val source = source(clockMovingOnFirstCall(release, factoryCalls))
        source.load()
        release.countDown()
        refusalOnceTheAbandonedCallFinished(source)
        now.set(CLOCK_AT_TIMEOUT + BACKOFF.inWholeMilliseconds)

        val loaded = source.load()

        assertTrue(loaded.complete)
        assertEquals(2, factoryCalls.get())
    }

    @Test
    fun backoff_doubles_after_consecutive_timeouts_and_is_capped() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val factoryCalls = AtomicInteger()
        val source =
            source(
                hangingFactory(FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN), factoryCalls),
                timeout = HANG_TIMEOUT,
                maxBackoff = CAPPED_BACKOFF,
            )

        var timedOutAt = 0L
        for (window in listOf(BACKOFF, BACKOFF * 2, CAPPED_BACKOFF, CAPPED_BACKOFF)) {
            timeOutOnce(source, factoryCalls)
            timedOutAt += window.inWholeMilliseconds
            assertServedFromFileUntil(source, factoryCalls, timedOutAt)
            now.set(timedOutAt)
        }
        timeOutOnce(source, factoryCalls)

        assertEquals(5, factoryCalls.get())
    }

    @Test
    fun a_completed_session_resets_the_backoff() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val factoryCalls = AtomicInteger()
        val source = source(hangingFactory(FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN), factoryCalls))
        timeOutOnce(source, factoryCalls)
        now.set(BACKOFF.inWholeMilliseconds)
        hung.release()
        assertTrue(source.load().complete)
        awaitSessionClosed()

        timeOutOnce(source, factoryCalls)
        assertServedFromFileUntil(source, factoryCalls, 2 * BACKOFF.inWholeMilliseconds)
        now.set(2 * BACKOFF.inWholeMilliseconds)
        hung.release()

        assertTrue(source.load().complete)
        assertEquals(4, factoryCalls.get())
    }

    @Test
    fun an_abandoned_session_finishing_later_does_not_replace_a_file_key_created_meanwhile() {
        val refusalRelease = latch()
        val keyFileAbsentWhenTheSessionTriedTheKeychain = AtomicBoolean(false)
        val keychain = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN).apply { failWrites = true }
        val slowToRefuseWrites =
            object : KeyringEntries by keychain {
                override fun set(
                    service: String,
                    account: String,
                    value: String,
                ) {
                    keyFileAbsentWhenTheSessionTriedTheKeychain.set(!keyFile.exists())
                    refusalRelease.await()
                    keychain.set(service, account, value)
                }
            }
        val source = source({ signallingClose(slowToRefuseWrites) }, timeout = HANG_TIMEOUT)

        val created = source.loadOrCreate()
        val keyCreatedWhileHung = assertNotNull(FileKeySlot(keyFile).read())
        refusalRelease.countDown()
        awaitSessionClosed()

        assertTrue(keyFileAbsentWhenTheSessionTriedTheKeychain.get())
        assertEquals(1, keychain.sets)
        assertContentEquals(Base64.getDecoder().decode(keyCreatedWhileHung), created.keys.single())
        assertEquals(keyCreatedWhileHung, FileKeySlot(keyFile).read())
    }

    @Test
    fun a_call_made_while_another_is_still_within_its_timeout_waits_its_turn() {
        val release = latch()
        val entered = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val source =
            source(
                {
                    if (factoryCalls.incrementAndGet() == 1) {
                        entered.countDown()
                        release.await()
                    }
                    entries
                },
                timeout = 10.seconds,
            )
        val results = ConcurrentLinkedQueue<Result<LoadedKeys>>()

        val first = thread { results += runCatching { source.load() } }
        assertTrue(entered.await(MARGIN.inWholeMilliseconds, TimeUnit.MILLISECONDS))
        val second = thread { results += runCatching { source.load() } }
        awaitParked(second)
        release.countDown()
        first.join(MARGIN.inWholeMilliseconds)
        second.join(MARGIN.inWholeMilliseconds)

        assertEquals(2, results.size)
        assertTrue(results.all { it.isSuccess }, "$results")
        assertEquals(2, factoryCalls.get())
    }

    @Test
    fun callers_queued_behind_one_hang_lengthen_the_backoff_only_once() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val entered = CountDownLatch(1)
        val release = latch()
        val source = source(hangingOnFirstCall(AtomicInteger(), entered, release))

        val served = timeOutTogether(source, entered, queuedCallers = 2)
        release.countDown()
        refusalOnceTheAbandonedCallFinished(source)
        now.set(BACKOFF.inWholeMilliseconds)
        val afterOneBackoff = source.load()

        assertTrue(served.none { it.complete }, "$served")
        assertTrue(afterOneBackoff.complete, "no keychain session started once a single back-off had passed")
    }

    /**
     * The window between a caller's timeout and recording it: another call holds the lock (it is reading the clock) while
     * the caller's session answers. The caller must get that answer, and the back-off must not be re-armed.
     */
    @Test
    fun a_session_that_answers_while_its_timed_out_caller_waits_for_the_lock_still_answers_that_caller() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val factoryCalls = AtomicInteger()
        val hanging = hangingFactory(FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN), factoryCalls)
        val sessionThread = AtomicReference<Thread>()
        val lockHolder = AtomicReference<Thread>()
        val clockEntered = CountDownLatch(1)
        val clockRelease = latch()
        val source =
            OsDesktopKeySource(
                fallbackFile = keyFile,
                keyringFactory = {
                    sessionThread.set(Thread.currentThread())
                    hanging()
                },
                policy = KeychainCallPolicy(timeout = ANSWER_TIMEOUT, backoff = BACKOFF, maxBackoff = MAX_BACKOFF),
                clock = {
                    if (Thread.currentThread() === lockHolder.get()) {
                        clockEntered.countDown()
                        clockRelease.await()
                    }
                    now.get()
                },
                problems = KeySlotProblemReporter(log.logger),
            )
        timeOutOnce(source, factoryCalls)
        now.set(BACKOFF.inWholeMilliseconds)

        val answers = ConcurrentLinkedQueue<Result<LoadedKeys>>()
        val caller = thread { answers += runCatching { source.load() } }
        awaitParked(caller)
        val holding = thread(start = false) { runCatching { source.load() } }
        lockHolder.set(holding)
        holding.start()
        assertTrue(clockEntered.await(MARGIN.inWholeMilliseconds, TimeUnit.MILLISECONDS), "The second call never read the clock under the lock")
        awaitState(caller, Thread.State.BLOCKED, ANSWER_TIMEOUT + MARGIN)
        hung.release(3)
        awaitState(assertNotNull(sessionThread.get()), Thread.State.TIMED_WAITING, MARGIN)
        clockRelease.countDown()
        caller.join(MARGIN.inWholeMilliseconds)
        holding.join(MARGIN.inWholeMilliseconds)

        assertTrue(answers.single().getOrThrow().complete, "The caller was served from the file although its session had answered")
        assertTrue(source.load().complete, "A session that answered re-armed the back-off")
    }

    @Test
    fun a_queued_session_whose_caller_timed_out_never_opens_the_keychain() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val entered = CountDownLatch(1)
        val release = latch()
        val factoryCalls = AtomicInteger()
        val source = source(hangingOnFirstCall(factoryCalls, entered, release))

        val served = timeOutTogether(source, entered, queuedCallers = 3)
        release.countDown()
        refusalOnceTheAbandonedCallFinished(source)

        assertTrue(served.none { it.complete }, "$served")
        assertEquals(1, factoryCalls.get(), "a session whose caller had given up still opened the keychain")
    }

    @Test
    fun a_queued_session_whose_caller_was_interrupted_never_opens_the_keychain() {
        FileKeySlot(keyFile).writeIfAbsent(encodedKey())
        val entered = CountDownLatch(1)
        val release = latch()
        val factoryCalls = AtomicInteger()
        val source = source(hangingOnFirstCall(factoryCalls, entered, release), timeout = 10.seconds)
        val interruptedResult = AtomicReference<Result<LoadedKeys>>()
        val interruptStatusKept = AtomicBoolean(false)

        val first = thread { runCatching { source.load() } }
        assertTrue(entered.await(MARGIN.inWholeMilliseconds, TimeUnit.MILLISECONDS), "The keychain session never started")
        val queued =
            thread {
                interruptedResult.set(runCatching { source.load() })
                interruptStatusKept.set(Thread.currentThread().isInterrupted)
            }
        awaitParked(queued)
        queued.interrupt()
        queued.join(MARGIN.inWholeMilliseconds)
        release.countDown()
        first.join(MARGIN.inWholeMilliseconds)
        val later = source.load()

        assertIs<IllegalStateException>(assertNotNull(interruptedResult.get()).exceptionOrNull())
        assertTrue(interruptStatusKept.get(), "the interrupted caller lost its interrupt status")
        assertTrue(later.complete)
        assertEquals(2, factoryCalls.get(), "the session of a caller that was interrupted still opened the keychain")
    }

    @Test
    fun an_error_on_the_keychain_thread_is_reported_as_an_exception() {
        val error = NotImplementedError("native keychain call")
        val source = source({ throw error })

        val thrown = assertFailsWith<IllegalStateException> { source.load() }

        assertSame(error, thrown.cause)
    }

    @Test
    fun a_slot_failure_is_warned_once_until_it_recovers() {
        val log = CapturingLogWriter()
        val keychainUp = AtomicBoolean(false)
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val source =
            source(
                { if (keychainUp.get()) entries else throw BackendNotSupportedException("Error connecting to dbus") },
                problems = KeySlotProblemReporter(log.logger),
            )

        repeat(3) { source.load() }
        assertEquals(1, log.messages(Severity.Warn).size)
        assertEquals(1, log.entriesWithThrowable())
        keychainUp.set(true)
        repeat(2) { source.load() }
        assertEquals(1, log.messages(Severity.Info).size)
        keychainUp.set(false)
        source.load()

        assertEquals(2, log.messages(Severity.Warn).size)
        assertEquals(2, log.entriesWithThrowable())
    }

    @Test
    fun a_failing_probe_is_warned_once_across_loads() {
        val log = CapturingLogWriter()
        val entries = FakeKeyringEntries(KeyringStorageType.KWALLET, missingMessage = "Password is not in wallet").apply { failWrites = true }
        val source = source({ entries }, problems = KeySlotProblemReporter(log.logger))

        source.load()
        val warningsAfterFirstLoad = log.messages(Severity.Warn)
        source.load()

        assertEquals(1, warningsAfterFirstLoad.count { "probe" in it }, "$warningsAfterFirstLoad")
        assertEquals(warningsAfterFirstLoad, log.messages(Severity.Warn))
    }

    /**
     * A source over [keyFile] on the [now] clock. [timeout] defaults to [ANSWER_TIMEOUT], for tests in which a session must
     * answer; a test in which every session is meant to hang passes [HANG_TIMEOUT].
     */
    private fun source(
        factory: () -> KeyringEntries,
        timeout: Duration = ANSWER_TIMEOUT,
        maxBackoff: Duration = MAX_BACKOFF,
        problems: KeySlotProblemReporter = KeySlotProblemReporter(log.logger),
    ): OsDesktopKeySource =
        OsDesktopKeySource(
            fallbackFile = keyFile,
            keyringFactory = factory,
            policy = KeychainCallPolicy(timeout = timeout, backoff = BACKOFF, maxBackoff = maxBackoff),
            clock = now::get,
            problems = problems,
        )

    /** A working keychain whose first session hangs in `Keyring.create()` until [release]; [entered] opens once it does. */
    private fun hangingOnFirstCall(
        factoryCalls: AtomicInteger,
        entered: CountDownLatch,
        release: CountDownLatch,
    ): () -> KeyringEntries {
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        return {
            if (factoryCalls.incrementAndGet() == 1) {
                entered.countDown()
                release.await()
            }
            entries
        }
    }

    /**
     * One caller whose session hangs (it must be the source's first, [hangEntered] opening once it hangs) and
     * [queuedCallers] more whose sessions queue behind it before it times out. Returns what each caller was served once all
     * of them timed out. The hang is left in place.
     */
    private fun timeOutTogether(
        source: OsDesktopKeySource,
        hangEntered: CountDownLatch,
        queuedCallers: Int,
    ): List<LoadedKeys> {
        val results = ConcurrentLinkedQueue<Result<LoadedKeys>>()
        val hungCaller = thread { results += runCatching { source.load() } }
        assertTrue(hangEntered.await(MARGIN.inWholeMilliseconds, TimeUnit.MILLISECONDS), "The keychain session never started")
        val queued = List(queuedCallers) { thread { results += runCatching { source.load() } }.also(::awaitParked) }
        (queued + hungCaller).forEach { it.join((ANSWER_TIMEOUT + MARGIN).inWholeMilliseconds) }
        assertEquals(queuedCallers + 1, results.size, "Not every caller returned")
        return results.map { it.getOrThrow() }
    }

    private fun blockingFactory(release: CountDownLatch): () -> KeyringEntries =
        {
            release.await()
            throw BackendNotSupportedException("answered too late")
        }

    /** A keychain whose every session hangs in `Keyring.create()` until a [hung] permit is handed out, then opens [entries]. */
    private fun hangingFactory(
        entries: KeyringEntries,
        factoryCalls: AtomicInteger,
    ): () -> KeyringEntries =
        {
            factoryCalls.incrementAndGet()
            hung.acquire()
            signallingClose(entries)
        }

    /** [entries], releasing a [sessionsClosed] permit when the source closes them at the end of a session. */
    private fun signallingClose(entries: KeyringEntries): KeyringEntries =
        object : KeyringEntries by entries {
            override fun close() {
                entries.close()
                sessionsClosed.release()
            }
        }

    /**
     * A working keychain whose first call moves the clock to [CLOCK_AT_TIMEOUT] and then hangs until [release]: the caller's
     * timeout is read after the clock moved, so a back-off measured from the call's start would end too early.
     */
    private fun clockMovingOnFirstCall(
        release: CountDownLatch,
        factoryCalls: AtomicInteger,
    ): () -> KeyringEntries {
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        return {
            if (factoryCalls.incrementAndGet() == 1) {
                now.set(CLOCK_AT_TIMEOUT)
                release.await()
            }
            entries
        }
    }

    private fun latch(): CountDownLatch = CountDownLatch(1).also { latches += it }

    /**
     * One call over a [hangingFactory] keychain that times out and is served from the file. Its session is then released,
     * and this returns once the source no longer counts it as running.
     */
    private fun timeOutOnce(
        source: OsDesktopKeySource,
        factoryCalls: AtomicInteger,
    ) {
        val calls = factoryCalls.get()
        val loaded = source.load()
        assertFalse(loaded.complete)
        assertEquals(calls + 1, factoryCalls.get(), "no keychain session started at ${now.get()} ms")
        hung.release()
        awaitSessionClosed()
        refusalOnceTheAbandonedCallFinished(source)
    }

    /** Calls [source] at the last clock reading before [end] and checks it was served without a keychain session. */
    private fun assertServedFromFileUntil(
        source: OsDesktopKeySource,
        factoryCalls: AtomicInteger,
        end: Long,
    ) {
        now.set(end - 1)
        val calls = factoryCalls.get()
        assertFalse(source.load().complete)
        assertEquals(calls, factoryCalls.get(), "a keychain session started before $end ms")
    }

    private fun awaitSessionClosed() {
        assertTrue(sessionsClosed.tryAcquire(MARGIN.inWholeMilliseconds, TimeUnit.MILLISECONDS), "The keychain session never closed")
    }

    /** The abandoned call finishes moments after it is released; until then the source serves the key file alone. */
    private fun loadOnceTheKeychainAnswers(source: OsDesktopKeySource): LoadedKeys {
        val deadline = System.nanoTime() + MARGIN.inWholeNanoseconds
        while (true) {
            val loaded = source.load()
            if (loaded.complete) return loaded
            assertTrue(System.nanoTime() < deadline, "The keychain never answered again")
            Thread.onSpinWait()
        }
    }

    /**
     * Calls [source] until the logged reason it was served from the file no longer says the abandoned call is
     * [STILL_RUNNING], and returns that reason. A call that reaches the keychain fails the test.
     */
    private fun refusalOnceTheAbandonedCallFinished(source: OsDesktopKeySource): String {
        val deadline = System.nanoTime() + MARGIN.inWholeNanoseconds
        while (true) {
            val logged = log.messages(Severity.Debug).size
            source.load()
            val refusal =
                assertNotNull(
                    log.messages(Severity.Debug).drop(logged).lastOrNull { NOT_ANSWERING in it },
                    "A call reached the keychain during the back-off",
                )
            if (STILL_RUNNING !in refusal) return refusal
            assertTrue(System.nanoTime() < deadline, "The abandoned keychain call never finished")
            Thread.onSpinWait()
        }
    }

    /** Waits, bounded, until [caller] is parked in its wait for the keychain thread, and fails if it never gets there. */
    private fun awaitParked(caller: Thread) {
        val deadline = System.nanoTime() + MARGIN.inWholeNanoseconds
        while (caller.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(caller.state in setOf(Thread.State.TIMED_WAITING, Thread.State.WAITING), "caller is ${caller.state}")
    }

    /**
     * Waits, bounded by [within], until [thread] is in [state], and fails if it never gets there. For the keychain thread,
     * TIMED_WAITING means idle: its session is done and it waits for the next one.
     */
    private fun awaitState(
        thread: Thread,
        state: Thread.State,
        within: Duration,
    ) {
        val deadline = System.nanoTime() + within.inWholeNanoseconds
        while (thread.state != state && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertEquals(state, thread.state, "${thread.name} never reached $state")
    }

    private fun encodedKey(): String = Base64.getEncoder().encodeToString(KEY)

    private companion object {
        const val DEVICE_TOKEN = "device_token"
        const val NOT_ANSWERING = "OS keychain did not answer within"
        const val STILL_RUNNING = "still running"
        const val BACKING_OFF = "not asking it again"
        const val CLOCK_AT_TIMEOUT = 1_000L
        const val RELEASE_ALL = 1_000
        val KEY = ByteArray(DesktopMasterKey.KEY_BYTES) { 7 }

        /** For a keychain session that is meant to hang: short, since every hang costs a caller this long. */
        val HANG_TIMEOUT: Duration = 200.milliseconds

        /** For a keychain session that is meant to answer, or callers that must queue before a hang times out. */
        val ANSWER_TIMEOUT: Duration = 2.seconds
        val BACKOFF: Duration = 30.seconds
        val CAPPED_BACKOFF: Duration = 100.seconds
        val MAX_BACKOFF: Duration = 30.minutes
        val REUSE: Duration = 30.seconds
        val MARGIN: Duration = 2.seconds
    }
}
