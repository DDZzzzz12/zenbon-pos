@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertTrue

private fun isDestroyedJs(task: JsAny): Boolean = js("task.destroyed === true")

/** A cancelled open() must not leave a PDF.js document alive in the shared worker. */
class PdfJsCancellationTest {
    @Test
    fun cancelledOpenDestroysTheLoadingTaskOnceItSettles() =
        runTest {
            // Warm the loader so open()'s first real suspension is the getDocument() await.
            PdfJsLoader.load()
            val disposed = CompletableDeferred<JsAny>()
            val renderer = PdfJsPageRenderer(onTaskDisposed = { disposed.complete(it) })
            val opening = launch { renderer.open(PdfSource.Bytes(PdfFixtures.twoPage)) }
            // Runs open() up to that await; JS promises cannot settle before control returns to the event loop.
            yield()
            assertTrue(opening.isActive, "open() should be suspended on getDocument()")
            opening.cancelAndJoin()
            // Completed by the renderer from the promise's settlement, not by a timer.
            val task = disposed.await()
            assertTrue(isDestroyedJs(task), "the orphaned loading task must be destroyed")
            renderer.close()
        }
}
