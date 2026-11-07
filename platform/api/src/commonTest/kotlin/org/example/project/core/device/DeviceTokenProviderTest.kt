package org.example.project.core.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The device token is read on demand, not once at construction: a key store that cannot open the sealed token at
 * boot must not leave the till without `X-Device-Token` until a restart, and one that does not answer must not be
 * asked on every request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTokenProviderTest {
    private val clock = TestTimeSource()

    @BeforeTest
    fun setUp() {
        DeviceTokenProvider.reset()
        DeviceTokenProvider.timeSource = clock
    }

    @AfterTest
    fun tearDown() {
        DeviceTokenProvider.reset()
    }

    @Test
    fun a_held_token_is_returned_without_asking_the_loader() =
        runTest {
            var loads = 0
            DeviceTokenProvider.token = "held"
            DeviceTokenProvider.loader = {
                loads++
                "loaded"
            }

            assertEquals("held", DeviceTokenProvider.currentToken())
            assertEquals(0, loads)
        }

    @Test
    fun without_a_loader_a_missing_token_stays_missing() =
        runTest {
            assertNull(DeviceTokenProvider.currentToken())
        }

    @Test
    fun a_seed_holds_the_token_when_nothing_wrote_since_the_mark() {
        val mark = DeviceTokenProvider.writeMark()

        assertTrue(DeviceTokenProvider.seedIfUnchangedSince(mark, "tok"))
        assertEquals("tok", DeviceTokenProvider.token)
    }

    @Test
    fun a_seed_after_a_clear_leaves_the_token_cleared() {
        DeviceTokenProvider.token = "tok-old"
        val mark = DeviceTokenProvider.writeMark()
        // Unpairing lands while the caller is still reading the persisted token.
        DeviceTokenProvider.token = null

        assertFalse(DeviceTokenProvider.seedIfUnchangedSince(mark, "tok-old"))
        assertNull(DeviceTokenProvider.token, "a stale read must not undo the clear")
    }

    @Test
    fun a_missing_token_is_loaded_on_demand_and_cached() =
        runTest {
            var loads = 0
            DeviceTokenProvider.loader = {
                loads++
                "tok-1"
            }

            assertEquals("tok-1", DeviceTokenProvider.currentToken())
            assertEquals("tok-1", DeviceTokenProvider.token, "a loaded token is cached")
            assertEquals("tok-1", DeviceTokenProvider.currentToken())
            assertEquals(1, loads)
        }

    @Test
    fun an_empty_load_is_retried_at_most_once_every_five_seconds() =
        runTest {
            var loads = 0
            DeviceTokenProvider.loader = {
                loads++
                null
            }

            assertNull(DeviceTokenProvider.currentToken())
            clock += 4.seconds
            assertNull(DeviceTokenProvider.currentToken())
            assertEquals(1, loads, "a second read inside five seconds must not ask the key store again")

            clock += 1.seconds
            assertNull(DeviceTokenProvider.currentToken())
            assertEquals(2, loads, "after five seconds the loader is asked again")
        }

    @Test
    fun a_token_that_becomes_readable_is_picked_up_after_the_retry_interval() =
        runTest {
            var readable = false
            DeviceTokenProvider.loader = { if (readable) "tok-1" else null }

            assertNull(DeviceTokenProvider.currentToken())
            readable = true
            assertNull(DeviceTokenProvider.currentToken(), "still inside the retry interval")

            clock += 5.seconds
            assertEquals("tok-1", DeviceTokenProvider.currentToken())
        }

    @Test
    fun a_throwing_loader_reads_as_missing_and_is_throttled_too() =
        runTest {
            var loads = 0
            DeviceTokenProvider.loader = {
                loads++
                throw IllegalStateException("key store unavailable")
            }

            assertNull(DeviceTokenProvider.currentToken())
            assertNull(DeviceTokenProvider.currentToken())
            assertEquals(1, loads)
        }

    @Test
    fun a_token_cleared_while_a_load_is_in_flight_is_not_resurrected() =
        runTest {
            val release = CompletableDeferred<Unit>()
            DeviceTokenProvider.loader = {
                release.await()
                "tok-stale"
            }

            val read = async { DeviceTokenProvider.currentToken() }
            runCurrent()
            // What clearDeviceToken() does, landing while the load is still reading the old value.
            DeviceTokenProvider.token = null
            release.complete(Unit)

            assertNull(read.await(), "a load that started before the clear must not hand out the cleared token")
            assertNull(DeviceTokenProvider.token, "nor cache it")
        }

    @Test
    fun concurrent_reads_share_one_load() =
        runTest {
            var loads = 0
            val release = CompletableDeferred<Unit>()
            DeviceTokenProvider.loader = {
                loads++
                release.await()
                "tok-1"
            }

            val first = async { DeviceTokenProvider.currentToken() }
            val second = async { DeviceTokenProvider.currentToken() }
            runCurrent()
            release.complete(Unit)

            assertEquals("tok-1", first.await())
            assertEquals("tok-1", second.await(), "a read waiting on the in-flight load gets its answer, not a missing header")
            assertEquals(1, loads)
        }
}
