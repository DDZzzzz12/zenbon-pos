@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.camera

import co.touchlab.kermit.Logger
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

private val log = Logger.withTag("camera")

/**
 * Resolves to the stream, or to `{ scanError: name }` when the request fails, so the error name
 * survives the promise boundary (a rejected promise loses it on the Kotlin side).
 */
private fun requestStreamJs(): Promise<JsAny?> =
    js(
        "navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 2560 }, height: { ideal: 1440 } }, audio: false }).catch(function (e) { return { scanError: String((e && e.name) || 'Error') }; })",
    )

/** The error name carried by a [requestStreamJs] result, or "" when [result] is a real stream. */
private fun errorOf(result: JsAny?): String = js("(result && typeof result.scanError === 'string') ? result.scanError : ''")

/** Stops a stream that resolves after its caller has gone away, so a cancelled start cannot leak a live camera. */
private fun discardLateStreamJs(pending: Promise<JsAny?>): Unit =
    js(
        "pending.then(function (s) { if (s && typeof s.getTracks === 'function') { s.getTracks().forEach(function (t) { t.stop(); }); } })",
    )

private fun attachJs(
    video: HTMLVideoElement,
    stream: JsAny,
): Unit =
    js(
        "(function () { video.srcObject = stream; video.muted = true; video.setAttribute('playsinline', ''); video.setAttribute('autoplay', ''); var p = video.play(); if (p && p.catch) { p.catch(function () {}); } })()",
    )

private fun stopJs(stream: JsAny): Unit = js("stream.getTracks().forEach(function (t) { t.stop(); })")

private fun torchSupportedJs(stream: JsAny): Boolean =
    js("(function () { var t = stream.getVideoTracks()[0]; return !!(t && t.getCapabilities && t.getCapabilities().torch); })()")

private fun applyTorchJs(
    stream: JsAny,
    on: Boolean,
): Promise<JsAny?> = js("stream.getVideoTracks()[0].applyConstraints({ advanced: [{ torch: on }] })")

private fun errorNameJs(e: JsAny?): String = js("(e && e.name) ? String(e.name) : ''")

private fun drawFrameJs(
    video: HTMLVideoElement,
    canvas: HTMLCanvasElement,
    maxLongEdge: Int,
): Unit =
    js(
        "(function () { var w = video.videoWidth, h = video.videoHeight; var m = Math.max(w, h); var k = (maxLongEdge > 0 && m > maxLongEdge) ? maxLongEdge / m : 1; canvas.width = Math.max(1, Math.round(w * k)); canvas.height = Math.max(1, Math.round(h * k)); canvas.getContext('2d', { willReadFrequently: true }).drawImage(video, 0, 0, canvas.width, canvas.height); })()",
    )

/**
 * Takes a full-resolution still through `ImageCapture` (the camera's photo pipeline: sensor
 * resolution, autofocus and exposure for a still) and draws it into [canvas], scaled to at most
 * [maxLongEdge]. Resolves to `"ok"`, or to `""` when the browser has no `ImageCapture` or the capture
 * failed, so the caller can fall back to a video frame.
 */
private fun takeStillJs(
    stream: JsAny,
    canvas: HTMLCanvasElement,
    maxLongEdge: Int,
): Promise<JsAny?> =
    js(
        "(function () { var t = stream.getVideoTracks()[0]; if (!t || typeof ImageCapture === 'undefined' || typeof createImageBitmap === 'undefined') { return Promise.resolve(''); } return new ImageCapture(t).takePhoto().then(function (blob) { return createImageBitmap(blob); }).then(function (bmp) { var w = bmp.width, h = bmp.height, m = Math.max(w, h); var k = m > maxLongEdge ? maxLongEdge / m : 1; canvas.width = Math.max(1, Math.round(w * k)); canvas.height = Math.max(1, Math.round(h * k)); canvas.getContext('2d', { willReadFrequently: true }).drawImage(bmp, 0, 0, canvas.width, canvas.height); if (bmp.close) { bmp.close(); } return 'ok'; }).catch(function () { return ''; }); })()",
    )

private fun isOk(result: JsAny?): Boolean = js("result === 'ok'")

/** Long edge of a captured still: sensor detail for the crop, without a canvas the browser may refuse. */
private const val STILL_MAX_LONG_EDGE = 4096

/**
 * One live camera. Owns the stream and the `<video>` element; [stop] stops every track so the
 * camera light goes off, and is safe to call any number of times. Callers must stop on dismiss,
 * on dispose and on every error path.
 */
class CameraSession {
    val video: HTMLVideoElement =
        (document.createElement("video") as HTMLVideoElement).apply {
            style.position = "absolute"
            style.zIndex = "10"
            style.visibility = "hidden"
            style.left = "0px"
            style.top = "0px"
            style.width = "0px"
            style.height = "0px"
            // grabFrame captures the whole frame, so show the whole frame (cover would crop what is shown).
            style.setProperty("object-fit", "contain")
        }

    private var stream: JsAny? = null

    /** Bumped by every [stop]; a start whose generation went stale was cancelled and must not keep its stream. */
    private var generation = 0

    val isRunning: Boolean get() = stream != null

    suspend fun start(): CameraStartResult {
        stop()
        val mine = generation
        val result: JsAny? =
            try {
                val pending = requestStreamJs()
                try {
                    pending.await<JsAny?>()
                } catch (e: CancellationException) {
                    discardLateStreamJs(pending)
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val name = (e as? kotlin.js.JsException)?.thrownValue?.let { errorNameJs(it) }
                log.w { "camera start failed: $name ($e)" }
                return CameraStartResult.Failed(CameraError.fromName(name))
            }
        val errorName = errorOf(result)
        if (errorName.isNotEmpty()) {
            if (generation != mine) return CameraStartResult.Cancelled
            log.w { "camera start failed: $errorName" }
            return CameraStartResult.Failed(CameraError.fromName(errorName))
        }
        if (result == null) return CameraStartResult.Failed(CameraError.Unknown)
        if (generation != mine) {
            stopJs(result)
            log.d { "camera start superseded; stream released" }
            return CameraStartResult.Cancelled
        }
        stream = result
        attachJs(video, result)
        log.d { "camera started" }
        return CameraStartResult.Started
    }

    fun torchSupported(): Boolean = stream?.let { torchSupportedJs(it) } ?: false

    /** Best effort: unsupported devices simply ignore it. Returns whether the torch is now in the requested state. */
    suspend fun setTorch(on: Boolean): Boolean {
        val s = stream ?: return false
        return try {
            applyTorchJs(s, on).await<JsAny?>()
            true
        } catch (e: Throwable) {
            log.w(e) { "torch not available" }
            false
        }
    }

    /**
     * The current video frame, or null before the first frame has arrived. Full resolution unless
     * [maxLongEdge] is given, in which case the frame is scaled so its long edge is at most that.
     */
    fun grabFrame(maxLongEdge: Int? = null): CanvasImage? {
        if (video.videoWidth == 0 || video.videoHeight == 0) return null
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        drawFrameJs(video, canvas, maxLongEdge ?: 0)
        return CanvasImage(canvas)
    }

    /**
     * The best picture the camera can give now: a full-resolution still where the browser supports
     * `ImageCapture`, else the current video frame (about the stream's resolution). Null before the
     * first frame. The frame fallback is also what a failed still falls back to.
     */
    suspend fun captureBest(): CanvasImage? {
        val s = stream
        if (s != null) {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val ok =
                try {
                    isOk(takeStillJs(s, canvas, STILL_MAX_LONG_EDGE).await<JsAny?>())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.w(e) { "still capture failed" }
                    false
                }
            if (ok) {
                log.d { "still captured: ${canvas.width}x${canvas.height}" }
                return CanvasImage(canvas)
            }
            log.d { "still capture unavailable; using the video frame" }
        }
        return grabFrame()
    }

    fun stop() {
        generation++
        stream?.let { stopJs(it) }
        stream = null
        video.style.visibility = "hidden"
        log.d { "camera stopped" }
    }
}
