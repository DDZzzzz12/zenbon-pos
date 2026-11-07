package org.example.project.core.data.database.secret

import org.example.project.core.data.database.KeyValueStore
import org.example.project.testutils.fakes.FakeSecretCipher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A secret written on one thread survives a read on another thread that is re-sealing or discarding the same key.
 *
 * Both rewrites are check-then-act: the read compares the row with what it found, then writes or removes it. The
 * compare alone left a window between the check and the act, and a [SecretProtectingKeyValueStore.setString] landing in
 * it was overwritten by the re-seal or removed by the discard. The compare and its act now run under a lock that every
 * write of a secret key also takes, so the write waits until the rewrite is done.
 *
 * The rig holds the reading thread inside that window — in the delegate, just after the compare read has returned —
 * starts the write, waits until the writer has either finished or parked on the lock, then lets the read go on. A latch
 * in the cipher's `open` would not show the race: it holds the read *before* the compare, which already refuses to act
 * on a row that changed. JVM-only because the window lies between two threads.
 */
class SecretProtectingKeyValueStoreConcurrencyTest {
    private val cipher = FakeSecretCipher()
    private val disk = WindowedStore()
    private val store = SecretProtectingKeyValueStore(disk, cipher)

    @Test
    fun a_discard_racing_a_write_leaves_the_written_value() {
        // Sealed for another key: it can never open under this one, so the read discards it.
        disk.setString(KEY, cipher.seal(key = "password", plaintext = "tok-old"))

        val read = readWhileWriting("tok-new")

        assertNull(read, "a value that can never be opened reads as absent")
        assertEquals("tok-new", store.getString(KEY), "the discard removed the value written while it was deciding")
    }

    @Test
    fun a_reseal_racing_a_write_leaves_the_written_value() {
        disk.setString(KEY, "tok-legacy")

        val read = readWhileWriting("tok-new")

        assertEquals("tok-legacy", read, "legacy plaintext is still returned to its reader")
        assertEquals("tok-new", store.getString(KEY), "the re-seal overwrote the value written while it was deciding")
    }

    /**
     * Reads [KEY] on one thread and, while that read sits between its compare and its rewrite, writes [value] on another.
     * Returns what the read returned.
     */
    private fun readWhileWriting(value: String): String? {
        // Read 1 fetches the stored value; read 2 is the compare that decides whether to rewrite it.
        disk.holdRead(number = 2)
        val read = AtomicReference<Result<String?>>()
        val reader = thread(name = "secret-reader") { read.set(runCatching { store.getString(KEY) }) }
        assertTrue(disk.inWindow.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the read never reached its compare")

        val written = AtomicReference<Result<Unit>>()
        val writer = thread(name = "secret-writer") { written.set(runCatching { store.setString(KEY, value) }) }
        awaitFinishedOrParked(writer)
        disk.leaveWindow.countDown()

        joinOrFail(reader)
        joinOrFail(writer)
        written.get()!!.getOrThrow()
        return read.get()!!.getOrThrow()
    }

    /**
     * Returns once [writer] has finished, or has been parked for a moment — waiting for the lock the read holds. Letting
     * the read go before either would let it finish first and hide the race.
     */
    private fun awaitFinishedOrParked(writer: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        var parkedSince: Long? = null
        while (System.nanoTime() < deadline) {
            if (!writer.isAlive) return
            val now = System.nanoTime()
            if (writer.state == Thread.State.BLOCKED || writer.state == Thread.State.WAITING) {
                val since = parkedSince ?: now.also { parkedSince = it }
                if (now - since >= TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS)) return
            } else {
                parkedSince = null
            }
            Thread.sleep(POLL_MILLIS)
        }
        fail("the writer neither finished nor waited for the lock")
    }

    private fun joinOrFail(thread: Thread) {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertFalse(thread.isAlive, "${thread.name} did not finish — the store deadlocked")
    }

    /** A thread-safe in-memory store that can hold one read of [KEY] until the test lets it go on. */
    private class WindowedStore : KeyValueStore {
        private val rows = ConcurrentHashMap<String, String>()
        private val readsOfKey = AtomicInteger()

        @Volatile
        private var heldRead = 0

        val inWindow = CountDownLatch(1)
        val leaveWindow = CountDownLatch(1)

        /** Holds the [number]th read of [KEY] from now on, after it has read the row. */
        fun holdRead(number: Int) {
            readsOfKey.set(0)
            heldRead = number
        }

        override fun getString(key: String): String? {
            val value = rows[key]
            if (key == KEY && readsOfKey.incrementAndGet() == heldRead) {
                inWindow.countDown()
                leaveWindow.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            return value
        }

        override fun setString(
            key: String,
            value: String,
        ) {
            rows[key] = value
        }

        override fun remove(key: String) {
            rows.remove(key)
        }

        override fun getAll(): Map<String, String> = rows.toMap()

        override fun clear() {
            rows.clear()
        }
    }

    private companion object {
        const val KEY = "device_token"
        const val TIMEOUT_SECONDS = 5L
        const val SETTLE_MILLIS = 100L
        const val POLL_MILLIS = 5L
    }
}
