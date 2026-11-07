package org.example.project.ui.components.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * `PdfViewerState.dispose()` calls `close()` on the UI thread. `close()` must therefore never wait for the
 * document lock: while a render or an open holds it, the release is handed to that work, which frees the document
 * when it lets go of the lock.
 *
 * Ordering is deterministic without sleeps: the work holding the lock is parked in a test hook on a latch, and the
 * caller of `close()` is a plain thread whose termination is the assertion. The join timeout only turns a
 * regression (close blocking) into a failure instead of a hung build; it never orders anything.
 */
class PdfBoxPageRendererNonBlockingCloseTest {
    private val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("pdf/two-page.pdf")).use { it.readBytes() }

    @Test
    fun closeWithTheLockFreeReleasesTheDocumentInline() =
        runBlocking<Unit>(Dispatchers.Default) {
            val releases = AtomicInteger(0)
            val renderer = PdfBoxPageRenderer(onDocumentReleased = { releases.incrementAndGet() })
            renderer.open(PdfSource.Bytes(bytes))
            renderer.close()
            assertEquals(1, releases.get())
            assertFalse(renderer.hasOpenDocument())
            renderer.close()
            assertEquals(1, releases.get(), "close is idempotent: the document is released once")
        }

    @Test
    fun closeReturnsWhileARenderHoldsTheLockAndTheRenderReleasesTheDocument() =
        runBlocking<Unit>(Dispatchers.Default) {
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val releases = AtomicInteger(0)
            val renderer =
                PdfBoxPageRenderer(
                    beforeRasterise = {
                        inside.countDown()
                        release.await()
                    },
                    onDocumentReleased = { releases.incrementAndGet() },
                )
            try {
                renderer.open(PdfSource.Bytes(bytes))
                val render = async(Dispatchers.IO) { renderer.render(0, 50) }
                inside.await()

                assertCloseReturnsPromptly(renderer)
                assertEquals(0, releases.get(), "the document is in use: it must not be freed under the render")

                release.countDown()
                render.await()
                assertEquals(1, releases.get(), "the render frees the document when it lets go of the lock")
                assertFalse(renderer.hasOpenDocument())
                assertFailsWith<IllegalStateException> { renderer.render(0, 50) }
                renderer.close()
                assertEquals(1, releases.get(), "a second close releases nothing")
            } finally {
                release.countDown()
            }
        }

    @Test
    fun renderQueuedBehindACloseIsRefusedWithoutRasterising() =
        runBlocking<Unit>(Dispatchers.Default) {
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val rasterised = AtomicInteger(0)
            val releases = AtomicInteger(0)
            val renderer =
                PdfBoxPageRenderer(
                    beforeRasterise = {
                        if (rasterised.incrementAndGet() == 1) {
                            inside.countDown()
                            release.await()
                        }
                    },
                    onDocumentReleased = { releases.incrementAndGet() },
                )
            try {
                renderer.open(PdfSource.Bytes(bytes))
                val first = async(Dispatchers.IO) { renderer.render(0, 50) }
                inside.await()
                val queued = async(SupervisorJob() + Dispatchers.IO) { renderer.render(1, 50) }
                awaitQueued(renderer)

                assertCloseReturnsPromptly(renderer)
                release.countDown()
                first.await()
                assertFailsWith<IllegalStateException> { queued.await() }
                assertEquals(1, rasterised.get(), "a render that got the lock after close must not touch the document")
                assertEquals(1, releases.get())
                assertFalse(renderer.hasOpenDocument())
            } finally {
                release.countDown()
            }
        }

    @Test
    fun closeDuringOpenReturnsAndTheOpenReleasesWhatItLoaded() =
        runBlocking<Unit>(Dispatchers.Default) {
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val releases = AtomicInteger(0)
            val renderer =
                PdfBoxPageRenderer(
                    beforeLoad = {
                        inside.countDown()
                        release.await()
                    },
                    onDocumentReleased = { releases.incrementAndGet() },
                )
            try {
                val open = async(SupervisorJob() + Dispatchers.IO) { renderer.open(PdfSource.Bytes(bytes)) }
                inside.await()

                assertCloseReturnsPromptly(renderer)
                release.countDown()
                assertFailsWith<IllegalStateException>("an open that finishes after close reports the renderer closed") { open.await() }
                assertEquals(1, releases.get(), "the document loaded after close must be freed by the open itself")
                assertFalse(renderer.hasOpenDocument())
                assertFailsWith<IllegalStateException> { renderer.render(0, 50) }
            } finally {
                release.countDown()
            }
        }

    /** Calls `close()` on its own thread (standing in for the UI thread) and fails if it does not return. */
    private fun assertCloseReturnsPromptly(renderer: PdfBoxPageRenderer) {
        val caller = thread(name = "close-caller") { renderer.close() }
        caller.join(CLOSE_RETURN_TIMEOUT_MS)
        assertFalse(caller.isAlive, "close() must not wait for the work holding the document lock")
    }

    private suspend fun awaitQueued(renderer: PdfBoxPageRenderer) {
        withTimeout(10_000) { while (!renderer.hasQueuedWork()) yield() }
    }

    private companion object {
        const val CLOSE_RETURN_TIMEOUT_MS = 10_000L
    }
}
