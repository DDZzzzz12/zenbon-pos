package org.example.project.core.data.database

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.example.project.core.data.database.secret.SecretProtectingKeyValueStore
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeSecretCipher
import org.example.project.testutils.fakes.IoMarkingDispatcher
import org.example.project.testutils.fakes.SecretAccessRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The device token is read when a request needs it, on the IO dispatcher — not once, by whichever thread happens to
 * construct [LocalDatabaseManager].
 *
 * A constructor read decrypted on the UI thread (Koin first resolves the manager there) and, if the key store was not
 * ready at that moment, left the till without `X-Device-Token` until it restarted: the store keeps a value it cannot
 * open *right now* but reads it as absent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTokenLoaderTest {
    private val clock = TestTimeSource()
    private val disk = FakeKeyValueStore()
    private val cipher = FakeSecretCipher()
    private val store = SecretProtectingKeyValueStore(disk, cipher)

    @BeforeEach
    fun setUp() {
        startBoot()
    }

    @AfterEach
    fun tearDown() {
        DeviceTokenProvider.reset()
    }

    /** A process start: nothing in memory, the retry interval measured on [clock]. */
    private fun startBoot() {
        DeviceTokenProvider.reset()
        DeviceTokenProvider.timeSource = clock
    }

    /** An earlier run paired the till and sealed [token]; this boot starts with nothing in memory. */
    private fun pairedInAnEarlierRun(token: String) {
        LocalDatabaseManager(store).setDeviceToken(token)
        startBoot()
    }

    @Test
    fun `constructing the manager opens no secret`() {
        pairedInAnEarlierRun("tok-1")
        val recorder = SecretAccessRecorder(store, IoMarkingDispatcher(StandardTestDispatcher()))

        LocalDatabaseManager(recorder)

        assertEquals(emptyList(), recorder.secretAccesses, "the constructor must not decrypt on the caller's thread")
        assertNull(DeviceTokenProvider.token)
    }

    @Test
    fun `a device token the key store could not open at boot is sent once it can`() =
        runTest {
            pairedInAnEarlierRun("tok-1")
            val sealed = disk.getString("device_token")
            cipher.openError = IllegalStateException("Keystore not ready")
            installDeviceTokenLoader(LocalDatabaseManager(store), StandardTestDispatcher(testScheduler))

            assertNull(DeviceTokenProvider.currentToken(), "the first read fails, as the boot read did")

            cipher.openError = null
            clock += 5.seconds

            assertEquals("tok-1", DeviceTokenProvider.currentToken(), "a later request-time read gets the token")
            assertEquals(sealed, disk.getString("device_token"), "the sealed value was never removed")
        }

    @Test
    fun `the loader opens the device token on the IO dispatcher`() =
        runTest {
            pairedInAnEarlierRun("tok-1")
            val io = IoMarkingDispatcher(StandardTestDispatcher(testScheduler))
            val recorder = SecretAccessRecorder(store, io)
            installDeviceTokenLoader(LocalDatabaseManager(recorder), io)

            assertEquals("tok-1", DeviceTokenProvider.currentToken())
            assertEquals(listOf("get:device_token"), recorder.secretAccesses)
            assertEquals(emptyList(), recorder.offIo, "the device token was opened on the caller's thread")
        }

    @Test
    fun `a cleared device token is not brought back by the loader`() =
        runTest {
            val localDb = LocalDatabaseManager(store)
            installDeviceTokenLoader(localDb, StandardTestDispatcher(testScheduler))
            localDb.setDeviceToken("tok-1")
            assertEquals("tok-1", DeviceTokenProvider.currentToken(), "precondition")

            localDb.clearDeviceToken()
            clock += 5.seconds

            assertNull(DeviceTokenProvider.currentToken(), "unpairing must stay unpaired")
        }
}
