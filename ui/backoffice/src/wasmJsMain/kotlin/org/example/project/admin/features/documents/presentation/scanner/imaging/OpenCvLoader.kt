@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner.imaging

import co.touchlab.kermit.Logger
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

private val log = Logger.withTag("opencv")

// A dynamic import() keeps the ~15 MB OpenCV chunk out of the main bundle: it is fetched only when
// the scanner first needs it. `default` is the ready-promise in 4.11+/5.x builds; `.then` flattens it.
private fun importOpenCvJs(): Promise<JsAny?> = js("import('@techstark/opencv-js').then(function (m) { return m.default || m; })")

private fun looksReadyJs(cv: JsAny?): Boolean = js("!!(cv && cv.Mat && cv.findContours && cv.warpPerspective && cv.getPerspectiveTransform)")

/** Loads OpenCV.js once and caches it. A failed load is not cached, so a retry can succeed. */
object OpenCvLoader {
    private var cv: JsAny? = null

    suspend fun load(): JsAny {
        cv?.let { return it }
        val loaded = importOpenCvJs().await<JsAny?>()
        check(looksReadyJs(loaded)) { "OpenCV.js loaded but is missing the expected functions" }
        log.i { "OpenCV.js ready" }
        return loaded!!.also { cv = it }
    }
}
