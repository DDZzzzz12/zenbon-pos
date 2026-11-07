package org.example.project.admin.features.documents.presentation.scanner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.camera.CameraError
import org.example.project.admin.core.util.camera.CameraSupport
import org.example.project.admin.core.util.camera.ScanImage
import org.example.project.admin.features.documents.presentation.scanner.imaging.CornerDetection
import org.example.project.admin.features.documents.presentation.scanner.imaging.CornerDetector
import org.example.project.admin.features.documents.presentation.scanner.imaging.ImageWarper
import org.example.project.admin.features.documents.presentation.scanner.imaging.PageEncoder
import org.example.project.admin.features.documents.presentation.scanner.imaging.Pt
import org.example.project.admin.features.documents.presentation.scanner.imaging.Quad
import org.example.project.admin.features.documents.presentation.scanner.imaging.QuadMath
import org.example.project.admin.features.documents.presentation.scanner.imaging.ScanFilter
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.file.PickedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeImage(
    override val width: Int,
    override val height: Int,
    val tag: String = "img",
) : ScanImage

private class FakeDetector(
    var found: Boolean = true,
    var gate: CompletableDeferred<Unit>? = null,
    var failure: Throwable? = null,
) : CornerDetector {
    var calls = 0

    override suspend fun detect(image: ScanImage): CornerDetection {
        calls++
        failure?.let { throw it }
        gate?.await()
        val q =
            if (found) {
                Quad(Pt(10f, 10f), Pt(90f, 12f), Pt(88f, 140f), Pt(12f, 138f))
            } else {
                QuadMath.insetFullImage(image.width.toFloat(), image.height.toFloat())
            }
        return CornerDetection(q, found)
    }
}

private class FakeWarper : ImageWarper {
    var lastFilter: ScanFilter? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun warp(
        image: ScanImage,
        quad: Quad,
        filter: ScanFilter,
        maxLongEdge: Int,
    ): ScanImage {
        lastFilter = filter
        gate?.await()
        return FakeImage(80, 130, "warped-$filter")
    }

    override suspend fun rotate90(image: ScanImage): ScanImage = FakeImage(image.height, image.width, "rot")
}

private class FakeEncoder : PageEncoder {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun toJpeg(
        image: ScanImage,
        quality: Float,
    ): ByteArray {
        gate?.await()
        return "JPEG-${(image as FakeImage).tag}".encodeToByteArray()
    }
}

class ScannerViewModelTest {
    private val detector = FakeDetector()
    private val warper = FakeWarper()
    private val encoder = FakeEncoder()

    private fun vm(
        support: CameraSupport = CameraSupport.Available,
        now: () -> Long = { 0L },
        monotonic: () -> Long = { 0L },
    ) = ScannerViewModel(detector, warper, encoder, support, now, monotonic)

    private fun UiError?.messageKey() = this?.messageKey

    private val frame = FakeImage(100, 150)

    @Test
    fun startsInCaptureWithTheGivenSupport() {
        assertEquals(CameraSupport.InsecureContext, vm(CameraSupport.InsecureContext).state.support)
        assertEquals(ScannerPhase.CAPTURE, vm().state.phase)
    }

    @Test
    fun captureMovesToAdjustWithTheDetectedQuadAsProposal() =
        runTest {
            val v = vm()
            v.capture(frame)
            println("ScannerViewModelTest capture phase=${v.state.phase}")
            assertEquals(ScannerPhase.ADJUST, v.state.phase)
            val d = assertNotNull(v.state.draft)
            assertEquals(d.detected, d.quad)
            assertTrue(d.found)
        }

    @Test
    fun noDetectionStillOpensAdjustWithANotice() =
        runTest {
            detector.found = false
            val v = vm()
            v.capture(frame)
            assertEquals(ScannerPhase.ADJUST, v.state.phase)
            assertEquals(ScannerState.Notice.NOTHING_DETECTED, v.state.notice)
        }

    @Test
    fun movingACornerChangesOnlyThatCorner() =
        runTest {
            val v = vm()
            v.capture(frame)
            val before = v.state.draft!!.quad
            v.moveCorner(0, Pt(20f, 25f))
            val after = v.state.draft!!.quad
            assertEquals(Pt(20f, 25f), after.tl)
            assertEquals(before.tr, after.tr)
            assertEquals(before.br, after.br)
            assertEquals(before.bl, after.bl)
        }

    @Test
    fun resetRestoresTheDetectedQuadAndWholeImageUsesTheFrame() =
        runTest {
            val v = vm()
            v.capture(frame)
            v.moveCorner(0, Pt(30f, 30f))
            v.resetToDetected()
            assertEquals(v.state.draft!!.detected, v.state.draft!!.quad)
            v.useWholeImage()
            assertEquals(QuadMath.fullImage(100f, 150f), v.state.draft!!.quad)
        }

    @Test
    fun acceptAddsAPageWithTheChosenFilterAndReturnsToTheCamera() =
        runTest {
            val v = vm()
            v.capture(frame)
            v.setFilter(ScanFilter.BLACK_WHITE)
            v.accept()
            println("ScannerViewModelTest accept pages=${v.state.pages.size} phase=${v.state.phase}")
            assertEquals(ScannerPhase.CAPTURE, v.state.phase)
            assertEquals(1, v.state.pages.size)
            assertEquals(ScanFilter.BLACK_WHITE, v.state.pages[0].filter)
            assertNull(v.state.draft)
        }

    @Test
    fun retakeDiscardsTheDraftAndReturnsToCapture() =
        runTest {
            val v = vm()
            v.capture(frame)
            v.retake()
            assertEquals(ScannerPhase.CAPTURE, v.state.phase)
            assertNull(v.state.draft)
            assertTrue(v.state.pages.isEmpty())
        }

    @Test
    fun editingAnAcceptedPageReplacesItInPlace() =
        runTest {
            val v = vm()
            v.capture(frame)
            v.accept()
            v.capture(frame)
            v.accept()
            val firstId = v.state.pages[0].id
            v.editPage(firstId)
            assertEquals(ScannerPhase.ADJUST, v.state.phase)
            v.moveCorner(2, Pt(80f, 130f))
            v.accept()
            assertEquals(2, v.state.pages.size)
            assertEquals(firstId, v.state.pages[0].id)
            assertEquals(
                Pt(80f, 130f),
                v.state.pages[0]
                    .quad.br,
            )
        }

    @Test
    fun deleteAndReorderPages() =
        runTest {
            val v = vm()
            repeat(3) {
                v.capture(frame)
                v.accept()
            }
            val ids = v.state.pages.map { it.id }
            v.movePage(ids[0], +1)
            assertEquals(listOf(ids[1], ids[0], ids[2]), v.state.pages.map { it.id })
            v.movePage(ids[2], +1) // already last: no-op
            assertEquals(listOf(ids[1], ids[0], ids[2]), v.state.pages.map { it.id })
            v.deletePage(ids[1])
            assertEquals(listOf(ids[0], ids[2]), v.state.pages.map { it.id })
        }

    @Test
    fun finishWithOnePageReturnsAPdf() =
        runTest {
            val v = vm(now = { 1_700_000_000_000L })
            v.capture(frame)
            v.accept()
            var delivered: PickedFile? = null
            v.finish { delivered = it }
            val f = assertNotNull(delivered)
            println("ScannerViewModelTest finish1 ${f.fileName} ${f.mimeType}")
            assertEquals("application/pdf", f.mimeType)
            assertTrue(f.fileName.startsWith("scan-") && f.fileName.endsWith(".pdf"), f.fileName)
            assertTrue(f.bytes.decodeToString().startsWith("%PDF-1.4"))
        }

    @Test
    fun finishWithTwoPagesReturnsAPdf() =
        runTest {
            val v = vm()
            repeat(2) {
                v.capture(frame)
                v.accept()
            }
            var delivered: PickedFile? = null
            v.finish { delivered = it }
            val f = assertNotNull(delivered)
            assertEquals("application/pdf", f.mimeType)
            assertTrue(f.fileName.endsWith(".pdf"))
            assertTrue(f.bytes.decodeToString().startsWith("%PDF-1.4"))
        }

    @Test
    fun finishWithNoPagesDeliversNothing() =
        runTest {
            val v = vm()
            var called = false
            v.finish { called = true }
            assertEquals(false, called)
        }

    @Test
    fun showPagesReturnsToTheListOnlyWhenThereAreSomePages() =
        runTest {
            val v = vm()
            v.showPages()
            assertEquals(ScannerPhase.CAPTURE, v.state.phase)
            v.capture(frame)
            v.accept()
            v.addPage()
            assertEquals(ScannerPhase.CAPTURE, v.state.phase)
            v.showPages()
            assertEquals(ScannerPhase.PAGES, v.state.phase)
        }

    // ---- busy guards ----------------------------------------------------------------------------

    @Test
    fun captureWhileBusyIsIgnored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            detector.gate = gate
            val v = vm()
            val first = FakeImage(100, 150, "first")
            val job = launch { v.capture(first) }
            runCurrent()
            assertTrue(v.state.busy)
            v.capture(FakeImage(100, 150, "second"))
            assertEquals(1, detector.calls)
            gate.complete(Unit)
            job.join()
            assertEquals("first", (v.state.draft!!.original as FakeImage).tag)
        }

    @Test
    fun acceptTwiceAddsOnePage() =
        runTest {
            val v = vm()
            v.capture(frame)
            val gate = CompletableDeferred<Unit>()
            warper.gate = gate
            val first = launch { v.accept() }
            runCurrent()
            assertTrue(v.state.busy)
            v.accept() // second tap while the first is warping
            v.retake() // must not discard the draft under the pending accept
            assertEquals(ScannerPhase.ADJUST, v.state.phase)
            gate.complete(Unit)
            first.join()
            assertEquals(1, v.state.pages.size)
            assertEquals(ScannerPhase.CAPTURE, v.state.phase)
        }

    @Test
    fun finishTwiceDeliversOnce() =
        runTest {
            val v = vm()
            v.capture(frame)
            v.accept()
            val gate = CompletableDeferred<Unit>()
            encoder.gate = gate
            var delivered = 0
            val first = launch { v.finish { delivered++ } }
            runCurrent()
            v.finish { delivered++ }
            gate.complete(Unit)
            first.join()
            assertEquals(1, delivered)
        }

    @Test
    fun previewFramesAreIgnoredWhileBusyOrOutsideCapture() =
        runTest {
            val v = vm()
            v.capture(frame) // now in ADJUST
            val calls = detector.calls
            assertEquals(false, v.onPreviewFrame(frame))
            assertEquals(calls, detector.calls)
        }

    // ---- limits ---------------------------------------------------------------------------------

    @Test
    fun theTwentyFirstPageIsRefusedWithAMessage() =
        runTest {
            val v = vm()
            repeat(ScannerViewModel.MAX_PAGES) {
                v.capture(frame)
                v.accept()
            }
            assertEquals(ScannerViewModel.MAX_PAGES, v.state.pages.size)
            v.retake()
            v.capture(frame)
            assertEquals(ScannerViewModel.MAX_PAGES, v.state.pages.size)
            assertEquals(ScannerPhase.CAPTURE, v.state.phase)
            assertEquals(AdminStringKeys.ScannerErrTooManyPages, v.state.error.messageKey())
        }

    // ---- detector failure -----------------------------------------------------------------------

    @Test
    fun aFailingDetectorIsReportedOnceAndNotCalledAgainByThePreview() =
        runTest {
            detector.failure = IllegalStateException("no opencv")
            val v = vm()
            assertEquals(false, v.onPreviewFrame(frame))
            assertEquals(AdminStringKeys.ScannerErrOpenCv, v.state.error.messageKey())
            v.clearError()
            repeat(4) { v.onPreviewFrame(frame) }
            assertEquals(1, detector.calls)
            assertNull(v.state.error)
        }

    // ---- stability clock ------------------------------------------------------------------------

    @Test
    fun stabilityUsesTheMonotonicClockNotTheWallClock() =
        runTest {
            var mono = 0L
            val v = vm(now = { 1_700_000_000_000L }, monotonic = { mono })
            v.setAutoCapture(true)
            assertEquals(false, v.onPreviewFrame(frame))
            mono = 800L
            assertEquals(true, v.onPreviewFrame(frame))
        }

    // ---- camera lifecycle state -----------------------------------------------------------------

    @Test
    fun torchSupportComesFromTheStartedCameraAndResetsWhenItStops() {
        val v = vm()
        v.onCameraStarted(torchSupported = true)
        assertTrue(v.state.torchSupported)
        v.setTorch(true)
        assertTrue(v.state.torchOn)
        v.onCameraStopped()
        assertEquals(false, v.state.torchOn)
        assertEquals(false, v.state.torchSupported)
    }

    @Test
    fun permissionDeniedKeepsTheCameraAvailableAndOffersARetry() {
        val v = vm()
        v.onCameraError(CameraError.PermissionDenied)
        assertEquals(CameraSupport.Available, v.state.support)
        assertTrue(v.state.cameraFailed)
        val before = v.state.cameraAttempt
        v.retryCamera()
        assertEquals(false, v.state.cameraFailed)
        assertEquals(before + 1, v.state.cameraAttempt)
    }

    @Test
    fun noCameraIsPermanentlyUnsupported() {
        val v = vm()
        v.onCameraError(CameraError.NoCamera)
        assertEquals(CameraSupport.Unsupported, v.state.support)
        assertEquals(false, v.state.cameraFailed)
    }
}
