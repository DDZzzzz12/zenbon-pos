@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

private fun newObjectJs(): JsAny = js("({})")

private fun resolvedJs(value: JsAny): Promise<JsAny?> = js("Promise.resolve(value)")

private fun rejectedJs(): Promise<JsAny?> = js("Promise.reject(new Error('chunk failed to load'))")

/** [PdfJsLoader]'s cache: one in-flight start shared by concurrent callers (one worker), failures not cached. */
class SharedPromiseLoaderTest {
    @Test
    fun concurrentFirstLoadsShareOneStart() =
        runTest {
            var starts = 0
            val loader =
                SharedPromiseLoader(
                    start = {
                        starts++
                        resolvedJs(newObjectJs())
                    },
                    isReady = { true },
                )
            val first = async { loader.load() }
            val second = async { loader.load() }
            assertSame(first.await(), second.await())
            assertEquals(1, starts, "a second start would create a second Worker/PDFWorker and orphan the first")
            assertSame(first.await(), loader.load())
            assertEquals(1, starts)
        }

    @Test
    fun failedLoadIsNotCachedSoARetryCanSucceed() =
        runTest {
            var starts = 0
            val loader =
                SharedPromiseLoader(start = { if (starts++ == 0) rejectedJs() else resolvedJs(newObjectJs()) }, isReady = { true })
            assertFails { loader.load() }
            loader.load()
            assertEquals(2, starts)
        }

    @Test
    fun loadThatIsNotReadyIsNotCached() =
        runTest {
            var checks = 0
            val loader = SharedPromiseLoader(start = { resolvedJs(newObjectJs()) }, isReady = { checks++ > 0 })
            assertFails { loader.load() }
            loader.load()
            assertEquals(2, checks)
        }
}
