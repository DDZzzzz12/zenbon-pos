package org.example.project.ui.components.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfBoxPageRendererCancellationTest {
    @Test
    fun renderCancelledWhileQueuedBehindAnotherDoesNotRasterise() =
        runBlocking(Dispatchers.Default) {
            val bytes =
                checkNotNull(javaClass.classLoader.getResourceAsStream("pdf/two-page.pdf")).use { it.readBytes() }
            val firstInside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val rasterised = AtomicInteger(0)
            val renderer =
                PdfBoxPageRenderer(
                    beforeRasterise = {
                        if (rasterised.incrementAndGet() == 1) {
                            firstInside.countDown()
                            release.await()
                        }
                    },
                )
            try {
                renderer.open(PdfSource.Bytes(bytes))
                val first = async { renderer.render(0, 100) }
                firstInside.await()
                val queued = async { renderer.render(1, 100) }
                withTimeout(10_000) { while (!renderer.hasQueuedWork()) yield() }
                queued.cancelAndJoinAfter { release.countDown() }
                first.await()
                assertFailsWith<CancellationException> { queued.await() }
                assertEquals(1, rasterised.get(), "the cancelled render must not reach rasterisation")
            } finally {
                release.countDown()
                renderer.close()
            }
        }

    private suspend fun kotlinx.coroutines.Deferred<*>.cancelAndJoinAfter(afterCancel: () -> Unit) {
        cancel()
        afterCancel()
        cancelAndJoin()
    }
}
