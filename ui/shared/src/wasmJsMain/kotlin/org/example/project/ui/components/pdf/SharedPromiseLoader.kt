@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Starts an expensive JS load at most once and shares it. The in-flight promise is cached, not just the
 * result, so concurrent first callers await the same start (for [PdfJsLoader]: one Worker, never an orphaned
 * second). A start that rejects or yields something [isReady] refuses is forgotten, so a retry can succeed; a
 * caller that is merely cancelled while waiting leaves the shared load running for the others.
 */
internal class SharedPromiseLoader(
    private val start: () -> Promise<JsAny?>,
    private val isReady: (JsAny?) -> Boolean,
) {
    private var pending: Promise<JsAny?>? = null
    private var value: JsAny? = null

    suspend fun load(): JsAny {
        value?.let { return it }
        val promise = pending ?: start().also { pending = it }
        val loaded =
            try {
                promise.await<JsAny?>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                forget(promise)
                throw e
            }
        if (loaded == null || !isReady(loaded)) {
            forget(promise)
            throw IllegalStateException("loaded module is missing the expected members")
        }
        return loaded.also { value = it }
    }

    private fun forget(promise: Promise<JsAny?>) {
        if (pending === promise) pending = null
    }
}
