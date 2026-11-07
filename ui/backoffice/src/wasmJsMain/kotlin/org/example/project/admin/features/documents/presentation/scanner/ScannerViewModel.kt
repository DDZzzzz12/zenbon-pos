package org.example.project.admin.features.documents.presentation.scanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.camera.CameraError
import org.example.project.admin.core.util.camera.CameraSupport
import org.example.project.admin.core.util.camera.ScanImage
import org.example.project.admin.features.documents.presentation.scanner.imaging.CornerDetector
import org.example.project.admin.features.documents.presentation.scanner.imaging.ImageWarper
import org.example.project.admin.features.documents.presentation.scanner.imaging.PageEncoder
import org.example.project.admin.features.documents.presentation.scanner.imaging.PdfPage
import org.example.project.admin.features.documents.presentation.scanner.imaging.PdfWriteResult
import org.example.project.admin.features.documents.presentation.scanner.imaging.PdfWriter
import org.example.project.admin.features.documents.presentation.scanner.imaging.Pt
import org.example.project.admin.features.documents.presentation.scanner.imaging.Quad
import org.example.project.admin.features.documents.presentation.scanner.imaging.QuadMath
import org.example.project.admin.features.documents.presentation.scanner.imaging.ScanFilter
import org.example.project.admin.features.documents.presentation.scanner.imaging.StabilityTracker
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.file.PickedFile
import kotlin.math.sqrt
import kotlin.time.Instant
import kotlin.time.TimeSource

private val monotonicOrigin = TimeSource.Monotonic.markNow()

private fun processMonotonicMillis(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

/**
 * Drives the scan flow: capture, adjust corners, pick a filter, accept, repeat, finish.
 * Collaborators come in by constructor so tests use fakes; the screen supplies the OpenCV ones.
 * [nowMillis] is epoch millis, injected for the file name only. The stability clock must never jump
 * backwards (an NTP step would settle or unsettle the outline), so it reads [monotonicMillis] instead.
 */
class ScannerViewModel(
    private val detector: CornerDetector,
    private val warper: ImageWarper,
    private val encoder: PageEncoder,
    support: CameraSupport,
    private val nowMillis: () -> Long,
    private val monotonicMillis: () -> Long = ::processMonotonicMillis,
) : ViewModel() {
    private val log = Logger.withTag("scanner")
    private val stability = StabilityTracker()
    private var nextId = 1
    private var detectorFailed = false

    var state by mutableStateOf(ScannerState(support = support))
        private set

    // ---- live preview -------------------------------------------------------------------------

    /**
     * Feed a preview frame; updates the outline and the settled flag. Returns true when auto-capture should fire.
     * Ignored while a capture/accept is running or outside the capture phase. After the first detector
     * failure the detector is not called again (and the operator is told once), so a missing OpenCV does
     * not raise an error five times a second.
     */
    suspend fun onPreviewFrame(frame: ScanImage): Boolean {
        if (state.busy || state.phase != ScannerPhase.CAPTURE || detectorFailed) return false
        val detection =
            try {
                detector.detect(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(e) { "preview detection failed; the preview loop stops detecting" }
                detectorFailed = true
                state = state.copy(error = error(AdminStringKeys.ScannerErrOpenCv))
                return false
            }
        if (state.busy || state.phase != ScannerPhase.CAPTURE) return false
        val quad = detection.takeIf { it.found }?.quad
        val diagonal = sqrt((frame.width * frame.width + frame.height * frame.height).toFloat())
        val settled = stability.update(quad, diagonal, monotonicMillis())
        state = state.copy(liveQuad = quad, settled = settled)
        return settled && state.autoCapture
    }

    fun setAutoCapture(on: Boolean) {
        state = state.copy(autoCapture = on)
    }

    fun setTorch(on: Boolean) {
        state = state.copy(torchOn = on)
    }

    /** The camera is live: records whether it has a torch (the screen cannot read that during composition). */
    fun onCameraStarted(torchSupported: Boolean) {
        state = state.copy(torchSupported = torchSupported, cameraFailed = false)
    }

    /** The camera stopped or the capture phase ended: the torch goes off with it. */
    fun onCameraStopped() {
        if (state.torchOn || state.torchSupported) state = state.copy(torchOn = false, torchSupported = false)
    }

    /** Re-runs the camera start effect after a recoverable failure (busy, permission prompt dismissed). */
    fun retryCamera() {
        state = state.copy(cameraFailed = false, cameraAttempt = state.cameraAttempt + 1)
    }

    // ---- capture and adjust -------------------------------------------------------------------

    suspend fun capture(frame: ScanImage) {
        if (state.busy) return
        if (state.pages.size >= MAX_PAGES) {
            state = state.copy(error = error(AdminStringKeys.ScannerErrTooManyPages))
            return
        }
        state = state.copy(busy = true)
        stability.reset()
        try {
            val detection = detector.detect(frame)
            state =
                state.copy(
                    phase = ScannerPhase.ADJUST,
                    draft = ScannerDraft(frame, detection.quad, detection.quad, detection.found),
                    notice = if (detection.found) null else ScannerState.Notice.NOTHING_DETECTED,
                    liveQuad = null,
                    settled = false,
                    busy = false,
                )
            refreshPreview()
        } catch (e: CancellationException) {
            state = state.copy(busy = false)
            throw e
        } catch (e: Throwable) {
            log.e(e) { "detection failed; opening manual adjustment" }
            val manual = QuadMath.insetFullImage(frame.width.toFloat(), frame.height.toFloat())
            state =
                state.copy(
                    phase = ScannerPhase.ADJUST,
                    draft = ScannerDraft(frame, manual, manual, found = false),
                    notice = ScannerState.Notice.NOTHING_DETECTED,
                    busy = false,
                    error = error(AdminStringKeys.ScannerErrOpenCv),
                )
        }
    }

    fun moveCorner(
        index: Int,
        target: Pt,
    ) {
        val d = state.draft ?: return
        val moved =
            QuadMath.moveCorner(
                d.quad,
                index,
                target,
                d.original.width.toFloat(),
                d.original.height.toFloat(),
                MIN_SEPARATION_PX,
            )
        if (moved == d.quad) return
        state = state.copy(draft = d.withQuad(moved))
    }

    /** Re-renders the thumbnail; call when a drag ends (not on every move) to keep dragging smooth. */
    suspend fun refreshPreview() {
        val d = state.draft ?: return
        try {
            val out = warper.warp(d.original, d.quad, d.filter, PREVIEW_LONG_EDGE)
            state = state.copy(draft = state.draft?.let { rebuild(it, preview = rotated(out, it.rotations)) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "preview warp failed" }
        }
    }

    suspend fun resetToDetected() {
        val d = state.draft ?: return
        state = state.copy(draft = d.withQuad(d.detected))
        refreshPreview()
    }

    suspend fun useWholeImage() {
        val d = state.draft ?: return
        state = state.copy(draft = d.withQuad(QuadMath.fullImage(d.original.width.toFloat(), d.original.height.toFloat())))
        refreshPreview()
    }

    suspend fun setFilter(filter: ScanFilter) {
        val d = state.draft ?: return
        state = state.copy(draft = rebuild(d, filter = filter))
        refreshPreview()
    }

    suspend fun rotate() {
        val d = state.draft ?: return
        state = state.copy(draft = rebuild(d, rotations = (d.rotations + 1) % 4))
        refreshPreview()
    }

    suspend fun accept() {
        if (state.busy) return
        val d = state.draft ?: return
        state = state.copy(busy = true)
        try {
            val result = rotated(warper.warp(d.original, d.quad, d.filter, EXPORT_LONG_EDGE), d.rotations)
            val replacing = d.replacingId
            val page = ScannerPage(replacing ?: nextId++, d.original, d.quad, d.filter, result, d.rotations)
            val pages =
                if (replacing == null) {
                    state.pages + page
                } else {
                    state.pages.map { if (it.id == replacing) page else it }
                }
            // A new page goes straight back to the camera for the next one (the page count and "Done"
            // are one tap away); a re-edited page returns to the list it came from. At the page limit
            // there is no next page to take, so show the list.
            val next = if (replacing == null && pages.size < MAX_PAGES) ScannerPhase.CAPTURE else ScannerPhase.PAGES
            state = state.copy(phase = next, pages = pages, draft = null, notice = null, busy = false)
            if (next == ScannerPhase.CAPTURE) stability.reset()
            log.d { "page accepted; ${pages.size} total" }
        } catch (e: CancellationException) {
            state = state.copy(busy = false)
            throw e
        } catch (e: Throwable) {
            log.e(e) { "accept failed" }
            state = state.copy(busy = false, error = error(AdminStringKeys.ScannerErrOpenCv))
        }
    }

    fun retake() {
        if (state.busy) return
        state = state.copy(phase = ScannerPhase.CAPTURE, draft = null, notice = null)
        stability.reset()
    }

    fun addPage() = retake()

    /** Back to the page list; a no-op while there are no pages to list. */
    fun showPages() {
        if (state.pages.isNotEmpty()) state = state.copy(phase = ScannerPhase.PAGES)
    }

    suspend fun editPage(id: Int) {
        val p = state.pages.firstOrNull { it.id == id } ?: return
        state =
            state.copy(
                phase = ScannerPhase.ADJUST,
                draft = ScannerDraft(p.original, p.quad, p.quad, found = true, filter = p.filter, rotations = p.rotations, replacingId = id),
            )
        refreshPreview()
    }

    // ---- page list ----------------------------------------------------------------------------

    fun deletePage(id: Int) {
        val pages = state.pages.filterNot { it.id == id }
        state = state.copy(pages = pages, phase = if (pages.isEmpty()) ScannerPhase.CAPTURE else state.phase)
    }

    fun movePage(
        id: Int,
        delta: Int,
    ) {
        val from = state.pages.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in state.pages.indices) return
        val list = state.pages.toMutableList()
        list.add(to, list.removeAt(from))
        state = state.copy(pages = list)
    }

    // ---- output -------------------------------------------------------------------------------

    /**
     * Always returns a PDF, one page per scan: the archive and its viewer handle PDFs, not loose images.
     * Nothing is delivered when there are no pages or the PDF is too large.
     */
    suspend fun finish(onResult: (PickedFile) -> Unit) {
        if (state.busy) return
        val pages = state.pages
        if (pages.isEmpty()) return
        state = state.copy(busy = true)
        try {
            val stamp = fileStamp()
            val pdfPages =
                pages.map {
                    PdfPage(encoder.toJpeg(it.result, JPEG_QUALITY), it.result.width, it.result.height)
                }
            when (val r = PdfWriter.write(pdfPages)) {
                is PdfWriteResult.Success -> deliver(PickedFile("scan-$stamp.pdf", "application/pdf", r.bytes), onResult)
                is PdfWriteResult.TooLarge -> {
                    log.w { "pdf too large: ${r.sizeBytes} bytes" }
                    state = state.copy(busy = false, error = error(AdminStringKeys.ScannerErrTooLarge))
                }
            }
        } catch (e: CancellationException) {
            state = state.copy(busy = false)
            throw e
        } catch (e: Throwable) {
            log.e(e) { "finish failed" }
            state = state.copy(busy = false, error = error(AdminStringKeys.ScannerErrImage))
        }
    }

    private fun deliver(
        file: PickedFile,
        onResult: (PickedFile) -> Unit,
    ) {
        state = state.copy(busy = false)
        onResult(file)
    }

    // ---- errors -------------------------------------------------------------------------------

    fun onCameraError(e: CameraError) {
        val key =
            when (e) {
                CameraError.PermissionDenied -> AdminStringKeys.ScannerErrPermission
                CameraError.NoCamera -> AdminStringKeys.ScannerErrNoCamera
                CameraError.Busy -> AdminStringKeys.ScannerErrBusy
                CameraError.Unknown -> AdminStringKeys.ScannerErrNoCamera
            }
        // Busy and a dismissed permission prompt can be fixed by the operator: keep the live camera on offer
        // and let them retry. No camera at all (or an unknown failure) will not fix itself.
        val recoverable = e == CameraError.PermissionDenied || e == CameraError.Busy
        state =
            state.copy(
                error = error(key),
                support = if (recoverable) state.support else CameraSupport.Unsupported,
                cameraFailed = recoverable,
            )
    }

    fun onImageUnreadable() {
        state = state.copy(error = error(AdminStringKeys.ScannerErrImage))
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun error(message: StringKey): UiError.Unknown = UiError.Unknown(titleKey = AdminStringKeys.ScannerErrorTitle, messageKey = message)

    private fun ScannerDraft.withQuad(q: Quad) = rebuild(this, quad = q)

    private fun rebuild(
        d: ScannerDraft,
        quad: Quad = d.quad,
        filter: ScanFilter = d.filter,
        rotations: Int = d.rotations,
        preview: ScanImage? = d.preview,
    ) = ScannerDraft(d.original, d.detected, quad, d.found, filter, rotations, preview, d.replacingId)

    private suspend fun rotated(
        image: ScanImage,
        rotations: Int,
    ): ScanImage {
        var out = image
        repeat(rotations) { out = warper.rotate90(out) }
        return out
    }

    private fun fileStamp(): String {
        val t = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(TimeZone.currentSystemDefault())

        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}${two(t.month.number)}${two(t.day)}-${two(t.hour)}${two(t.minute)}${two(t.second)}"
    }

    companion object {
        /** A scan holds at most this many pages; more would not fit the upload limit and the browser's memory. */
        const val MAX_PAGES = 20
        private const val MIN_SEPARATION_PX = 12f
        private const val PREVIEW_LONG_EDGE = 480
        private const val EXPORT_LONG_EDGE = 3000
        private const val JPEG_QUALITY = 0.9f
    }
}
