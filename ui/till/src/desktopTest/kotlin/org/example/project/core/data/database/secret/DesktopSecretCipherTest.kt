package org.example.project.core.data.database.secret

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.KeyringStorageType
import org.example.project.BuildKonfig
import org.example.project.testutils.fakes.FakeDesktopKeySource
import org.example.project.testutils.fakes.FakeKeySlot
import org.example.project.testutils.fakes.FakeKeyringEntries
import org.example.project.testutils.fakes.ScriptedDesktopKeySource
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopSecretCipherTest {
    private val source = FakeDesktopKeySource(primary = FakeKeySlot(encodedRandomKey()))
    private val cipher = DesktopSecretCipher(source)
    private var now = 0L
    private val dir = createTempDirectory("desktop-secret-cipher-test").toFile()
    private val keyFile = File(dir, "pos/local-secrets.key")
    private val keychainHang = CountDownLatch(1)

    @AfterTest
    fun releaseTheKeychainAndDeleteTheKeyFile() {
        keychainHang.countDown()
        dir.deleteRecursively()
    }

    @Test
    fun a_sealed_value_opens_to_the_original() {
        val sealed = cipher.seal(DEVICE_TOKEN, "device-token-123")

        assertTrue(SealedSecret.isSealed(sealed))
        assertEquals("device-token-123", cipher.open(DEVICE_TOKEN, sealed))
    }

    @Test
    fun the_same_plaintext_seals_differently_each_time() {
        assertNotEquals(cipher.seal(DEVICE_TOKEN, "same"), cipher.seal(DEVICE_TOKEN, "same"))
    }

    @Test
    fun a_value_sealed_for_one_preference_key_does_not_open_for_another() {
        val sealed = cipher.seal(DEVICE_TOKEN, "device-token-123")

        assertNull(cipher.open(PASSWORD, sealed))
    }

    @Test
    fun a_tampered_value_does_not_open() {
        val bytes = assertNotNull(SealedSecret.decode(cipher.seal(DEVICE_TOKEN, "device-token-123")))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        assertNull(cipher.open(DEVICE_TOKEN, SealedSecret.encode(bytes)))
    }

    @Test
    fun a_malformed_value_opens_as_null_without_loading_a_key() {
        assertNull(cipher.open(DEVICE_TOKEN, "enc:v1:%%%not-base64"))
        assertNull(cipher.open(DEVICE_TOKEN, SealedSecret.encode(ByteArray(8))))
        assertEquals(0, source.loads + source.loadOrCreates)
    }

    @Test
    fun when_no_key_opens_and_every_slot_was_readable_the_value_is_unreadable() {
        val other = DesktopSecretCipher(FakeDesktopKeySource(primary = FakeKeySlot(encodedRandomKey()), fallback = FakeKeySlot(encodedRandomKey())))

        assertNull(other.open(DEVICE_TOKEN, sealedByAnotherCipher()))
    }

    @Test
    fun when_no_key_opens_but_a_slot_could_not_be_read_open_throws() {
        val keychain = FakeKeySlot(encodedRandomKey()).apply { readError = IllegalStateException("locked") }
        val locked = DesktopSecretCipher(FakeDesktopKeySource(primary = keychain, fallback = FakeKeySlot(encodedRandomKey())))

        assertFailsWith<IllegalStateException> { locked.open(DEVICE_TOKEN, sealedByAnotherCipher()) }
    }

    @Test
    fun open_with_no_readable_key_throws_and_creates_nothing() {
        val empty = FakeDesktopKeySource()
        val lockedKeychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val locked = FakeDesktopKeySource(primary = lockedKeychain)
        val sealed = sealedByAnotherCipher()

        for (keySource in listOf(empty, locked)) {
            assertFailsWith<IllegalStateException> { DesktopSecretCipher(keySource).open(DEVICE_TOKEN, sealed) }
            assertEquals(0, keySource.loadOrCreates)
            assertEquals(0, (keySource.primary?.writes ?: 0) + keySource.fallback.writes)
            assertNull(keySource.fallback.value)
        }
    }

    @Test
    fun an_error_from_the_key_source_is_reported_as_an_exception() {
        val sealed = sealedByAnotherCipher()
        val errors =
            listOf(
                NotImplementedError("no keychain binding"),
                UnsatisfiedLinkError("jnidispatch missing"),
                NoClassDefFoundError("com/sun/jna/Native"),
            )
        for (error in errors) {
            val failingSource = FakeDesktopKeySource().apply { failure = error }
            val failing = DesktopSecretCipher(failingSource)

            val fromOpen = assertFails { failing.open(DEVICE_TOKEN, sealed) }
            val fromSeal = assertFails { failing.seal(DEVICE_TOKEN, "device-token-123") }

            assertIs<IllegalStateException>(fromOpen)
            assertSame(error, fromOpen.cause)
            assertIs<IllegalStateException>(fromSeal)
            assertSame(error, fromSeal.cause)
            assertEquals(0, assertNotNull(failingSource.primary).writeAttempts + failingSource.fallback.writeAttempts)
        }
    }

    @Test
    fun a_virtual_machine_error_from_the_key_source_is_not_wrapped() {
        val failing = DesktopSecretCipher(FakeDesktopKeySource().apply { failure = OutOfMemoryError("simulated") })

        assertFailsWith<OutOfMemoryError> { failing.open(DEVICE_TOKEN, sealedByAnotherCipher()) }
        assertFailsWith<OutOfMemoryError> { failing.seal(DEVICE_TOKEN, "device-token-123") }
    }

    @Test
    fun an_unavailable_keychain_backend_seals_under_a_new_file_key_and_never_writes_the_keychain() {
        val keychain = unavailableKeychain()
        val unavailable = FakeDesktopKeySource(primary = keychain)

        DesktopSecretCipher(unavailable).seal(DEVICE_TOKEN, "device-token-123")

        assertEquals(0, keychain.writeAttempts)
        assertEquals(1, unavailable.fallback.writes)
    }

    @Test
    fun a_value_sealed_under_the_file_key_opens_while_the_keychain_backend_is_unavailable() {
        val unavailable = FakeDesktopKeySource(primary = unavailableKeychain())
        val sealed = DesktopSecretCipher(unavailable).seal(DEVICE_TOKEN, "device-token-123")

        assertEquals("device-token-123", DesktopSecretCipher(unavailable).open(DEVICE_TOKEN, sealed))
    }

    @Test
    fun a_value_no_candidate_opens_while_the_keychain_backend_is_unavailable_makes_open_throw() {
        val unavailable = FakeDesktopKeySource(primary = unavailableKeychain(), fallback = FakeKeySlot(encodedRandomKey()))

        assertFailsWith<IllegalStateException> { DesktopSecretCipher(unavailable).open(DEVICE_TOKEN, sealedByAnotherCipher()) }

        assertEquals(0, unavailable.loadOrCreates)
    }

    @Test
    fun an_unreadable_key_file_and_no_other_key_makes_open_throw() {
        val file = FakeKeySlot(encodedRandomKey()).apply { readError = IOException("permission denied") }
        val unreadable = FakeDesktopKeySource(primary = FakeKeySlot(), fallback = file)

        assertFailsWith<IllegalStateException> { DesktopSecretCipher(unreadable).open(DEVICE_TOKEN, sealedByAnotherCipher()) }

        assertEquals(0, unreadable.loadOrCreates)
        assertEquals(0, assertNotNull(unreadable.primary).writeAttempts + file.writeAttempts)
    }

    @Test
    fun seal_creates_the_key_lazily_exactly_once() {
        val empty = FakeDesktopKeySource()
        val lazyCipher = DesktopSecretCipher(empty)
        assertEquals(0, empty.loads + empty.loadOrCreates)

        val first = lazyCipher.seal(DEVICE_TOKEN, "first")
        val second = lazyCipher.seal(PASSWORD, "second")

        assertEquals(1, assertNotNull(empty.primary).writes)
        assertEquals(0, empty.fallback.writes)
        assertEquals("first", lazyCipher.open(DEVICE_TOKEN, first))
        assertEquals("second", lazyCipher.open(PASSWORD, second))
    }

    @Test
    fun seal_uses_an_existing_key_instead_of_creating_one() {
        val sealed = cipher.seal(DEVICE_TOKEN, "device-token-123")

        assertEquals(0, source.loadOrCreates)
        assertEquals(0, assertNotNull(source.primary).writes + source.fallback.writes)
        val sameKey = FakeDesktopKeySource(primary = FakeKeySlot(assertNotNull(source.primary).value))
        assertEquals("device-token-123", DesktopSecretCipher(sameKey).open(DEVICE_TOKEN, sealed))
    }

    @Test
    fun a_complete_load_is_cached() {
        val sealed = cipher.seal(DEVICE_TOKEN, "device-token-123")
        cipher.open(DEVICE_TOKEN, sealed)
        cipher.open(DEVICE_TOKEN, sealed)

        assertEquals(1, source.loads)
    }

    @Test
    fun an_incomplete_load_is_reused_within_the_window_and_reloaded_after_it() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val lockedSource = FakeDesktopKeySource(primary = keychain, fallback = FakeKeySlot(encodedRandomKey()))
        val lockedCipher = cipherOnTestClock(lockedSource)

        val sealed = lockedCipher.seal(DEVICE_TOKEN, "device-token-123")
        now += REUSE.inWholeMilliseconds - 1
        assertEquals("device-token-123", lockedCipher.open(DEVICE_TOKEN, sealed))
        lockedCipher.seal(PASSWORD, "password-456")
        assertEquals(1, lockedSource.loads)
        now += 1
        assertEquals("device-token-123", lockedCipher.open(DEVICE_TOKEN, sealed))

        assertEquals(2, lockedSource.loads)
        assertEquals(0, lockedSource.loadOrCreates)
    }

    @Test
    fun when_a_reload_fails_the_last_known_keys_still_open_and_seal() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val keySource = FakeDesktopKeySource(primary = keychain, fallback = FakeKeySlot(encodedRandomKey()))
        val lockedCipher = cipherOnTestClock(keySource)
        val sealedBefore = lockedCipher.seal(DEVICE_TOKEN, "device-token-123")

        keySource.failure = IllegalStateException("OS keychain did not answer within 5s")
        now += 10 * REUSE.inWholeMilliseconds
        assertEquals("device-token-123", lockedCipher.open(DEVICE_TOKEN, sealedBefore))
        val sealedDuring = lockedCipher.seal(PASSWORD, "password-456")
        assertEquals(2, keySource.loads)
        keySource.failure = null

        assertEquals("password-456", DesktopSecretCipher(keySource).open(PASSWORD, sealedDuring))
        assertEquals(0, keySource.loadOrCreates)
    }

    @Test
    fun last_known_keys_never_produce_null() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val keySource = FakeDesktopKeySource(primary = keychain, fallback = FakeKeySlot(encodedRandomKey()))
        val lockedCipher = cipherOnTestClock(keySource)
        lockedCipher.seal(DEVICE_TOKEN, "device-token-123")
        val reloadFailure = IllegalStateException("OS keychain did not answer within 5s")
        keySource.failure = reloadFailure
        now += REUSE.inWholeMilliseconds

        val thrown = assertFailsWith<IllegalStateException> { lockedCipher.open(DEVICE_TOKEN, sealedByAnotherCipher()) }

        assertNotSame(reloadFailure, thrown)
        assertEquals(2, keySource.loads)
    }

    @Test
    fun a_complete_load_with_no_keys_is_reused_within_the_window() {
        val empty = FakeDesktopKeySource()
        val emptyCipher = cipherOnTestClock(empty)
        val sealed = sealedByAnotherCipher()

        assertFailsWith<IllegalStateException> { emptyCipher.open(DEVICE_TOKEN, sealed) }
        now += REUSE.inWholeMilliseconds - 1
        assertFailsWith<IllegalStateException> { emptyCipher.open(DEVICE_TOKEN, sealed) }
        assertEquals(1, empty.loads)
        now += 1
        assertFailsWith<IllegalStateException> { emptyCipher.open(DEVICE_TOKEN, sealed) }

        assertEquals(2, empty.loads)
        assertEquals(0, empty.loadOrCreates)
    }

    /**
     * A stored value proves a key once existed while every slot says none: a misread at autostart is as plausible as a wipe,
     * so the value is kept. Once a new key exists, a value no key opens is discarded.
     */
    @Test
    fun with_every_slot_empty_open_throws_until_a_new_key_exists_then_an_old_value_reads_as_unreadable() {
        val sealedUnderA = sealedByAnotherCipher()
        val wiped = FakeDesktopKeySource()
        val wipedCipher = cipherOnTestClock(wiped)

        assertFailsWith<IllegalStateException> { wipedCipher.open(DEVICE_TOKEN, sealedUnderA) }
        val sealedUnderB = wipedCipher.seal(PASSWORD, "password-456")
        assertEquals(1, wiped.loads)
        assertEquals(1, wiped.loadOrCreates)
        assertEquals(1, assertNotNull(wiped.primary).writes)

        assertEquals("password-456", wipedCipher.open(PASSWORD, sealedUnderB))
        assertNull(wipedCipher.open(DEVICE_TOKEN, sealedUnderA))
    }

    /**
     * The race behind I-1 across processes — desktop has no single-instance guard: this instance cached a complete load,
     * then another instance created a file key and sealed under it.
     */
    @Test
    fun a_value_sealed_under_a_key_created_after_the_cached_load_opens_after_one_reload() {
        val keychainKey = randomKey()
        val fileKey = randomKey()
        val keySource = ScriptedDesktopKeySource(loads = listOf(completeWith(keychainKey), completeWith(keychainKey, fileKey)))
        val secondInstance = cipherOnTestClock(keySource)
        secondInstance.seal(PASSWORD, "password-456")
        val sealedByFirstInstance = sealedUnder(fileKey, DEVICE_TOKEN, "device-token-123")

        assertEquals("device-token-123", secondInstance.open(DEVICE_TOKEN, sealedByFirstInstance))
        assertEquals("device-token-123", secondInstance.open(DEVICE_TOKEN, sealedByFirstInstance))
        assertEquals(2, keySource.loads)
    }

    @Test
    fun a_cached_load_reloads_at_most_once_per_window_before_a_value_reads_as_unreadable() {
        val key = randomKey()
        val keySource = ScriptedDesktopKeySource(loads = List(3) { completeWith(key) })
        val cachedCipher = cipherOnTestClock(keySource)
        cachedCipher.seal(PASSWORD, "password-456")
        val foreign = sealedByAnotherCipher()

        assertNull(cachedCipher.open(DEVICE_TOKEN, foreign))
        assertEquals(2, keySource.loads)
        now += REUSE.inWholeMilliseconds - 1
        assertNull(cachedCipher.open(DEVICE_TOKEN, foreign))
        assertEquals(2, keySource.loads)
        now += 1
        assertNull(cachedCipher.open(DEVICE_TOKEN, foreign))

        assertEquals(3, keySource.loads)
    }

    @Test
    fun a_cached_load_whose_reload_throws_or_is_incomplete_keeps_the_value() {
        val keychain = FakeKeySlot(encodedRandomKey())
        val keySource = FakeDesktopKeySource(primary = keychain)
        val cachedCipher = cipherOnTestClock(keySource)
        val sealed = cachedCipher.seal(PASSWORD, "password-456")
        val foreign = sealedByAnotherCipher()

        keySource.failure = IllegalStateException("OS keychain did not answer within 5s")
        assertFailsWith<IllegalStateException> { cachedCipher.open(DEVICE_TOKEN, foreign) }
        now += REUSE.inWholeMilliseconds - 1
        assertFailsWith<IllegalStateException> { cachedCipher.open(DEVICE_TOKEN, foreign) }
        assertEquals(2, keySource.loads)
        keySource.failure = null
        keychain.readError = IllegalStateException("locked")
        now += 1
        assertFailsWith<IllegalStateException> { cachedCipher.open(DEVICE_TOKEN, foreign) }
        assertEquals(3, keySource.loads)
        keychain.readError = null
        now += REUSE.inWholeMilliseconds

        assertNull(cachedCipher.open(DEVICE_TOKEN, foreign))
        assertEquals("password-456", cachedCipher.open(PASSWORD, sealed))
        assertEquals(4, keySource.loads)
    }

    @Test
    fun a_fresh_complete_load_can_still_return_null() {
        val keychain = FakeKeySlot(encodedRandomKey()).apply { readError = IllegalStateException("locked") }
        val keySource = FakeDesktopKeySource(primary = keychain, fallback = FakeKeySlot(encodedRandomKey()))
        val recovering = cipherOnTestClock(keySource)
        val foreign = sealedByAnotherCipher()
        assertFailsWith<IllegalStateException> { recovering.open(DEVICE_TOKEN, foreign) }
        keySource.failure = IllegalStateException("OS keychain did not answer within 5s")
        now += REUSE.inWholeMilliseconds
        assertFailsWith<IllegalStateException> { recovering.open(DEVICE_TOKEN, foreign) }

        keySource.failure = null
        keychain.readError = null
        now += REUSE.inWholeMilliseconds

        assertNull(recovering.open(DEVICE_TOKEN, foreign))
    }

    /**
     * The race behind I-1: a seal refused the keychain creates a file key while a keychain session is about to answer
     * another caller with a complete load read before that file existed. That complete load is cached, and lacks the key.
     */
    @Test
    fun a_value_sealed_under_a_file_key_still_opens_when_a_complete_load_cached_meanwhile_lacks_that_key() {
        val fileKey = randomKey()
        val keySource =
            ScriptedDesktopKeySource(
                loads = listOf(incompleteWith(null), completeWith(randomKey())),
                loadOrCreates = listOf(incompleteWith(fileKey)),
            )
        val racing = cipherOnTestClock(keySource)
        val sealed = racing.seal(DEVICE_TOKEN, "device-token-123")
        now += REUSE.inWholeMilliseconds

        assertEquals("device-token-123", racing.open(DEVICE_TOKEN, sealed))
        assertEquals("device-token-123", DesktopSecretCipher(ScriptedDesktopKeySource(loads = listOf(completeWith(fileKey)))).open(DEVICE_TOKEN, sealed))
    }

    @Test
    fun a_value_no_key_the_cipher_ever_held_opens_is_still_unreadable_under_a_complete_load() {
        val keySource =
            ScriptedDesktopKeySource(
                loads = listOf(incompleteWith(null), completeWith(randomKey())),
                loadOrCreates = listOf(incompleteWith(randomKey())),
            )
        val racing = cipherOnTestClock(keySource)
        racing.seal(DEVICE_TOKEN, "device-token-123")
        now += REUSE.inWholeMilliseconds

        assertNull(racing.open(DEVICE_TOKEN, sealedByAnotherCipher()))
    }

    @Test
    fun with_no_candidate_right_now_open_tries_the_keys_it_already_held_and_throws_when_none_opens() {
        val keySource = ScriptedDesktopKeySource(loads = listOf(incompleteWith(randomKey()), incompleteWith(null)))
        val emptied = cipherOnTestClock(keySource)
        val sealed = emptied.seal(DEVICE_TOKEN, "device-token-123")
        now += REUSE.inWholeMilliseconds

        assertEquals("device-token-123", emptied.open(DEVICE_TOKEN, sealed))
        assertFailsWith<IllegalStateException> { emptied.open(DEVICE_TOKEN, sealedByAnotherCipher()) }
        assertEquals("device-token-123", emptied.open(DEVICE_TOKEN, sealed))
    }

    @Test
    fun a_locked_keychain_on_a_fresh_install_still_lets_the_till_seal() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val lockedSource = FakeDesktopKeySource(primary = keychain)
        val lockedCipher = DesktopSecretCipher(lockedSource)

        val sealed = lockedCipher.seal(DEVICE_TOKEN, "device-token-123")

        assertEquals(0, keychain.writes)
        assertEquals(1, lockedSource.fallback.writes)
        assertEquals("device-token-123", lockedCipher.open(DEVICE_TOKEN, sealed))
    }

    @Test
    fun a_value_sealed_under_the_file_key_still_opens_once_the_keychain_key_is_readable_again() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val file = FakeKeySlot()
        val keySource = FakeDesktopKeySource(primary = keychain, fallback = file)
        val sealedWhileLocked = DesktopSecretCipher(keySource).seal(DEVICE_TOKEN, "device-token-123")
        assertEquals(0, keychain.writes)
        assertEquals(1, file.writes)

        keychain.readError = null
        keychain.value = encodedRandomKey()
        val afterUnlock = DesktopSecretCipher(keySource)

        assertEquals("device-token-123", afterUnlock.open(DEVICE_TOKEN, sealedWhileLocked))
        val sealedAfterUnlock = afterUnlock.seal(PASSWORD, "password-456")
        val keychainKeyOnly = DesktopSecretCipher(FakeDesktopKeySource(primary = FakeKeySlot(keychain.value)))
        assertEquals("password-456", keychainKeyOnly.open(PASSWORD, sealedAfterUnlock))
        assertEquals(1, file.writes)
    }

    @Test
    fun concurrent_first_seals_create_one_key() {
        val empty = FakeDesktopKeySource().apply { delayMillis = 20 }
        val sharedCipher = DesktopSecretCipher(empty)
        val start = CountDownLatch(1)
        val sealed = arrayOfNulls<String>(THREADS)
        val failures = ConcurrentLinkedQueue<Throwable>()

        val workers =
            List(THREADS) { index ->
                thread {
                    try {
                        start.await()
                        sealed[index] = sharedCipher.seal(DEVICE_TOKEN, "value-$index")
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertTrue(failures.isEmpty(), "Concurrent seals failed: $failures")
        assertEquals(1, assertNotNull(empty.primary).writes + empty.fallback.writes)
        sealed.forEachIndexed { index, value ->
            assertEquals("value-$index", sharedCipher.open(DEVICE_TOKEN, assertNotNull(value)))
        }
    }

    @Test
    fun concurrent_seals_under_an_incomplete_load_create_one_file_key() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val lockedSource = FakeDesktopKeySource(primary = keychain).apply { delayMillis = 20 }
        val sharedCipher = DesktopSecretCipher(lockedSource)
        val start = CountDownLatch(1)
        val sealed = arrayOfNulls<String>(THREADS)
        val failures = ConcurrentLinkedQueue<Throwable>()

        val workers =
            List(THREADS) { index ->
                thread {
                    try {
                        start.await()
                        sealed[index] = sharedCipher.seal(DEVICE_TOKEN, "value-$index")
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertTrue(failures.isEmpty(), "Concurrent seals failed: $failures")
        assertEquals(0, keychain.writeAttempts)
        assertEquals(1, lockedSource.fallback.writes)
        sealed.forEachIndexed { index, value ->
            assertEquals("value-$index", sharedCipher.open(DEVICE_TOKEN, assertNotNull(value)))
        }
    }

    @Test
    fun a_hung_keychain_on_a_fresh_install_still_lets_the_till_seal_and_open() {
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        val factoryCalls = AtomicInteger()
        val hungCipher = cipherOnTestClock(sourceWithHungKeychain(entries, factoryCalls, HANG_TIMEOUT))

        val sealed = hungCipher.seal(DEVICE_TOKEN, "device-token-123")

        assertEquals("device-token-123", hungCipher.open(DEVICE_TOKEN, sealed))
        assertFailsWith<IllegalStateException> { hungCipher.open(DEVICE_TOKEN, sealedByAnotherCipher()) }
        assertTrue(keyFile.isFile)
        assertEquals(1, factoryCalls.get())
        assertEquals(0, entries.sets)
    }

    @Test
    fun values_sealed_under_the_file_key_while_the_keychain_hung_open_once_it_answers() {
        val keychainKey = encodedRandomKey()
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN).apply { stored[BuildKonfig.APP_NAME to OsKeyringKeySlot.ACCOUNT] = keychainKey }
        val sealedUnderKeychain = DesktopSecretCipher(FakeDesktopKeySource(primary = FakeKeySlot(keychainKey))).seal(PASSWORD, "password-456")
        val factoryCalls = AtomicInteger()
        val tillCipher = cipherOnTestClock(sourceWithHungKeychain(entries, factoryCalls, ANSWER_TIMEOUT))
        val sealedUnderFile = tillCipher.seal(DEVICE_TOKEN, "device-token-123")
        assertFailsWith<IllegalStateException> { tillCipher.open(PASSWORD, sealedUnderKeychain) }

        keychainHang.countDown()
        val openedUnderKeychain = openOnceTheKeychainAnswers(tillCipher, PASSWORD, sealedUnderKeychain)

        assertEquals("password-456", openedUnderKeychain)
        assertEquals("device-token-123", tillCipher.open(DEVICE_TOKEN, sealedUnderFile))
        assertNull(tillCipher.open(DEVICE_TOKEN, sealedByAnotherCipher()))
        assertEquals(0, entries.sets)
        assertEquals(3, factoryCalls.get(), "the hang, the answer, and the one reload a cached load takes before returning null")
    }

    /** A cipher whose incomplete-reuse window runs on [now], which each test advances by hand. */
    private fun cipherOnTestClock(keySource: DesktopKeySource): DesktopSecretCipher =
        DesktopSecretCipher(
            keySource,
            clock = { now },
            incompleteReuse = REUSE,
        )

    /**
     * The production key source over [keyFile], on the [now] clock, whose first keychain session hangs in `Keyring.create()`
     * until [keychainHang] is released. Every later session opens [entries]. A test in which a later session must answer
     * passes [ANSWER_TIMEOUT], so a slow machine cannot turn that answer into another timeout.
     */
    private fun sourceWithHungKeychain(
        entries: KeyringEntries,
        factoryCalls: AtomicInteger,
        timeout: Duration,
    ): OsDesktopKeySource =
        OsDesktopKeySource(
            fallbackFile = keyFile,
            keyringFactory = {
                if (factoryCalls.incrementAndGet() == 1) keychainHang.await()
                entries
            },
            policy = KeychainCallPolicy(timeout = timeout),
            clock = { now },
        )

    private fun incompleteWith(key: ByteArray?): LoadedKeys =
        LoadedKeys(keys = listOfNotNull(key), primaryUsable = false, fallbackReadable = true, complete = false, sealingKeySlot = key?.let { "key file" })

    private fun completeWith(vararg keys: ByteArray): LoadedKeys =
        LoadedKeys(keys = keys.toList(), primaryUsable = true, fallbackReadable = true, complete = true, sealingKeySlot = "OS keychain")

    /** [plaintext] sealed for [preferenceKey] by a cipher whose only key is [key]. */
    private fun sealedUnder(
        key: ByteArray,
        preferenceKey: String,
        plaintext: String,
    ): String = DesktopSecretCipher(ScriptedDesktopKeySource(loads = listOf(completeWith(key)))).seal(preferenceKey, plaintext)

    /**
     * Opens [sealed] once the keychain answers again. The abandoned session is done moments after its release; until then
     * each open is served from the key file and throws, so every attempt moves [now] past the reuse window and the back-off.
     */
    private fun openOnceTheKeychainAnswers(
        cipher: DesktopSecretCipher,
        key: String,
        sealed: String,
    ): String? {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (true) {
            now += REUSE.inWholeMilliseconds
            try {
                return cipher.open(key, sealed)
            } catch (e: IllegalStateException) {
                if (System.nanoTime() > deadline) throw e
                Thread.onSpinWait()
            }
        }
    }

    /** A keychain slot whose every read and write throws, as an unavailable java-keyring backend does. */
    private fun unavailableKeychain(): FakeKeySlot =
        FakeKeySlot().apply {
            readError = IllegalStateException("backend unavailable", BackendNotSupportedException("Error connecting to dbus"))
            writeError = IllegalStateException("backend unavailable")
        }

    private fun sealedByAnotherCipher(): String {
        val otherSource = FakeDesktopKeySource(primary = FakeKeySlot(encodedRandomKey()))
        return DesktopSecretCipher(otherSource).seal(DEVICE_TOKEN, "device-token-123")
    }

    private fun encodedRandomKey(): String = Base64.getEncoder().encodeToString(randomKey())

    private fun randomKey(): ByteArray = ByteArray(DesktopMasterKey.KEY_BYTES).also { SecureRandom().nextBytes(it) }

    private companion object {
        const val DEVICE_TOKEN = "device_token"
        const val PASSWORD = "password"
        const val THREADS = 8
        val REUSE: Duration = 30.seconds

        /** For a keychain session that is meant to hang: short, since every hang costs a caller this long. */
        val HANG_TIMEOUT: Duration = 200.milliseconds

        /** For a keychain session that is meant to answer: long enough that a loaded machine never misses it. */
        val ANSWER_TIMEOUT: Duration = 2.seconds
    }
}
