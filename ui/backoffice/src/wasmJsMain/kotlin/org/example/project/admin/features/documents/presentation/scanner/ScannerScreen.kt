@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.camera.CameraSession
import org.example.project.admin.core.util.camera.CameraStartResult
import org.example.project.admin.core.util.camera.CameraSupport
import org.example.project.admin.core.util.camera.CameraVideoOverlay
import org.example.project.admin.core.util.camera.ImageDecoder
import org.example.project.admin.core.util.camera.captureViaFileInput
import org.example.project.admin.core.util.dom.BlockDomOverlayWhileVisible
import org.example.project.admin.features.documents.presentation.scanner.imaging.CanvasPageEncoder
import org.example.project.admin.features.documents.presentation.scanner.imaging.OpenCvDetector
import org.example.project.admin.features.documents.presentation.scanner.imaging.OpenCvWarper
import org.example.project.admin.features.documents.presentation.scanner.imaging.ScanFilter
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.file.FilePickResult
import org.example.project.core.utils.file.PickedFile
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.guard.rememberDirtySource
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Preview detection cadence: about 5 fps keeps the main thread free for the video and the UI. */
private const val PREVIEW_INTERVAL_MS = 200L

/** The preview loop only needs enough pixels for edge detection (which itself works at 640 px). */
private const val PREVIEW_LONG_EDGE = 640

/** While a corner is dragged, the thumbnail re-renders at most this often. */
private const val LIVE_REFRESH_MS = 150L

private val log = Logger.withTag("scanner-screen")

/**
 * The scanner. [onResult] receives the finished PDF (one page per scan); [onCancel] fires
 * after the camera has been stopped. The caller decides what to do with the file.
 */
@Composable
fun ScannerScreen(
    onResult: (PickedFile) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm =
        remember {
            ScannerViewModel(
                detector = OpenCvDetector(),
                warper = OpenCvWarper(),
                encoder = CanvasPageEncoder(),
                support = CameraSupport.detect(),
                nowMillis = { Clock.System.now().toEpochMilliseconds() },
            )
        }
    val state = vm.state
    val scope = rememberCoroutineScope()
    val session = remember { CameraSession() }

    // Stop the camera however this composable leaves composition.
    DisposableEffect(session) { onDispose { session.stop() } }

    // Captured pages / an adjust-in-progress draft are lost on leaving. Cancel leaves via the guarded
    // `onCancel`; the camera is stopped by the effect above, so Keep editing leaves it running.
    rememberDirtySource(dirty = state.pages.isNotEmpty() || state.draft != null, onDiscard = session::stop)

    val live = state.phase == ScannerPhase.CAPTURE && state.support == CameraSupport.Available

    LaunchedEffect(live, state.cameraAttempt) {
        if (live) {
            when (val r = session.start()) {
                CameraStartResult.Started -> vm.onCameraStarted(session.torchSupported())
                CameraStartResult.Cancelled -> Unit
                is CameraStartResult.Failed -> vm.onCameraError(r.error)
            }
        } else {
            session.stop()
            vm.onCameraStopped()
        }
    }

    // Preview detection loop; auto-capture fires from here when the frame has settled.
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            delay(PREVIEW_INTERVAL_MS)
            // Detection runs on a small copy; auto-capture then takes a full-resolution still.
            val preview = session.grabFrame(PREVIEW_LONG_EDGE) ?: continue
            if (vm.onPreviewFrame(preview)) session.captureBest()?.let { vm.capture(it) }
        }
    }

    // The DOM <video> paints above the canvas: hide it while an error dialog could be showing.
    if (state.error != null) BlockDomOverlayWhileVisible()

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            ScannerPhase.CAPTURE -> CaptureBody(vm, session, live, onCancel, scope, Modifier.weight(1f))
            ScannerPhase.ADJUST -> AdjustBody(vm, onCancel, scope, Modifier.weight(1f))
            ScannerPhase.PAGES -> PagesBody(vm, onResult, onCancel, scope, Modifier.weight(1f))
        }
    }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.ScannerErrorTitle),
        onClear = vm::clearError,
    )
}

/** Touch size of the icon buttons in the one action row under every step. */
private val ACTION_ICON_SIZE = 48.dp

/** Slightly tighter in the adjust row, which carries five controls and the longest label. */
private val ADJUST_ICON_SIZE = 44.dp

/**
 * The top line of every step: close, what to do, and what is on the right (page count / retake).
 * Kept to one line so the picture below gets the height.
 */
@Composable
private fun ScannerHeader(
    title: String,
    onClose: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PosIconButton(Icons.Filled.Close, str(AdminStringKeys.ScannerCancel), onClose, size = ACTION_ICON_SIZE)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
        trailing()
    }
}

@Composable
private fun CaptureBody(
    vm: ScannerViewModel,
    session: CameraSession,
    live: Boolean,
    onCancel: () -> Unit,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    val state = vm.state
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hint =
            when {
                state.support != CameraSupport.Available -> AdminStringKeys.ScannerTitle
                state.busy -> AdminStringKeys.ScannerLoadingOpenCv
                state.settled -> AdminStringKeys.ScannerReady
                else -> AdminStringKeys.ScannerHoldStill
            }
        ScannerHeader(title = str(hint), onClose = onCancel) {
            if (state.pages.isNotEmpty()) {
                PosTextButton(text = str(AdminStringKeys.ScannerPagesCount, state.pages.size), onClick = vm::showPages)
                PosButton(text = str(AdminStringKeys.ScannerDone), onClick = vm::showPages, height = 40.dp)
            }
        }
        when (state.support) {
            CameraSupport.Available -> {
                val border = if (state.settled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                Box(Modifier.weight(1f).fillMaxWidth().border(BorderStroke(3.dp, border))) {
                    CameraVideoOverlay(session, visible = live, modifier = Modifier.fillMaxSize())
                }
            }
            CameraSupport.InsecureContext ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(AdminStringKeys.ScannerInsecureTitle), style = MaterialTheme.typography.titleMedium)
                    Text(str(AdminStringKeys.ScannerInsecureBody))
                }
            CameraSupport.Unsupported -> Text(str(AdminStringKeys.ScannerUnsupported), Modifier.weight(1f))
        }
        // One row: the two no-camera ways in, the shutter, torch and auto-capture.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Works without a secure context: a file chooser (gallery and files on phones)...
            PosIconButton(
                Icons.Filled.PhotoLibrary,
                str(AdminStringKeys.ScannerChooseFile),
                { scope.launch { pickPhoto(vm, capture = false) } },
                enabled = !state.busy,
                size = ACTION_ICON_SIZE,
            )
            // ...and the device's camera app.
            PosIconButton(
                Icons.Filled.PhotoCamera,
                str(AdminStringKeys.ScannerUseCameraApp),
                { scope.launch { pickPhoto(vm, capture = true) } },
                enabled = !state.busy,
                size = ACTION_ICON_SIZE,
            )
            if (state.support == CameraSupport.Available) {
                // Icon only: the shutter needs no word, and a word would not fit beside the other four.
                PosButton(
                    icon = Icons.Filled.Camera,
                    enabled = !state.busy,
                    onClick = { scope.launch { session.captureBest()?.let { frame -> vm.capture(frame) } } },
                    modifier = Modifier.weight(1f),
                )
                if (state.torchSupported) {
                    PosIconButton(
                        if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        str(AdminStringKeys.ScannerTorch),
                        {
                            scope.launch {
                                val want = !state.torchOn
                                if (session.setTorch(want)) vm.setTorch(want)
                            }
                        },
                        size = ACTION_ICON_SIZE,
                    )
                }
                PosIconButton(
                    Icons.Filled.AutoMode,
                    str(AdminStringKeys.ScannerAutoCapture),
                    { vm.setAutoCapture(!state.autoCapture) },
                    tint = if (state.autoCapture) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = ACTION_ICON_SIZE,
                )
                if (state.cameraFailed) {
                    PosIconButton(Icons.Filled.Refresh, str(AdminStringKeys.ScannerRetryCamera), vm::retryCamera, size = ACTION_ICON_SIZE)
                }
            }
        }
    }
}

private suspend fun pickPhoto(
    vm: ScannerViewModel,
    capture: Boolean,
) {
    when (val r = captureViaFileInput(capture)) {
        is FilePickResult.Picked -> {
            val image =
                try {
                    ImageDecoder.decode(r.file.bytes, r.file.mimeType)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.w(e) { "picked image could not be decoded" }
                    vm.onImageUnreadable()
                    return
                }
            vm.capture(image)
        }
        FilePickResult.ReadFailed -> vm.onImageUnreadable()
        FilePickResult.Cancelled -> Unit
    }
}

@Composable
private fun AdjustBody(
    vm: ScannerViewModel,
    onCancel: () -> Unit,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    val state = vm.state
    val draft = state.draft ?: return
    val source by rememberScanBitmap(draft.original)
    val thumb by rememberScanBitmap(draft.preview)
    val clock = remember { TimeSource.Monotonic.markNow() }
    val lastLiveRefresh = remember { longArrayOf(-LIVE_REFRESH_MS) }
    val refreshing = remember { booleanArrayOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScannerHeader(title = str(AdminStringKeys.ScannerAdjustTitle), onClose = onCancel) {
            thumb?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp), contentScale = ContentScale.Fit) }
            PosTextButton(text = str(AdminStringKeys.ScannerRetake), onClick = vm::retake)
        }
        if (state.busy) Text(str(AdminStringKeys.ScannerLoadingOpenCv))
        if (state.notice == ScannerState.Notice.NOTHING_DETECTED) {
            Text(str(AdminStringKeys.ScannerNothingDetected), color = MaterialTheme.colorScheme.error)
        }
        // The editor takes everything the one action row leaves.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            source?.let { img ->
                QuadEditor(
                    image = img,
                    quad = draft.quad,
                    onMoveCorner = { i, p ->
                        vm.moveCorner(i, p)
                        val now = clock.elapsedNow().inWholeMilliseconds
                        if (!refreshing[0] && now - lastLiveRefresh[0] >= LIVE_REFRESH_MS) {
                            lastLiveRefresh[0] = now
                            refreshing[0] = true
                            scope.launch {
                                try {
                                    vm.refreshPreview()
                                } finally {
                                    refreshing[0] = false
                                }
                            }
                        }
                    },
                    onDragEnd = { scope.launch { vm.refreshPreview() } },
                )
            }
        }
        // One row: rotate, corners back to detected, whole image, the filter (tap cycles), accept.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PosIconButton(Icons.Filled.RotateRight, str(AdminStringKeys.ScannerRotate), { scope.launch { vm.rotate() } }, size = ADJUST_ICON_SIZE)
            PosIconButton(
                Icons.Filled.AutoFixHigh,
                str(AdminStringKeys.ScannerResetCorners),
                { scope.launch { vm.resetToDetected() } },
                size = ADJUST_ICON_SIZE,
            )
            PosIconButton(Icons.Filled.CropFree, str(AdminStringKeys.ScannerWholeImage), { scope.launch { vm.useWholeImage() } }, size = ADJUST_ICON_SIZE)
            FilterCycle(draft.filter) { scope.launch { vm.setFilter(nextFilter(draft.filter)) } }
            PosButton(
                text = str(AdminStringKeys.ScannerAccept),
                icon = Icons.Filled.Check,
                enabled = !state.busy,
                onClick = { scope.launch { vm.accept() } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The current filter as an icon and its name; a tap moves to the next one. */
@Composable
private fun FilterCycle(
    filter: ScanFilter,
    onClick: () -> Unit,
) {
    Column(
        Modifier.height(ACTION_ICON_SIZE).width(56.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            when (filter) {
                ScanFilter.COLOUR -> Icons.Filled.Palette
                ScanFilter.GREYSCALE -> Icons.Filled.FilterBAndW
                ScanFilter.BLACK_WHITE -> Icons.Filled.Contrast
            },
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(str(filterLabel(filter)), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private fun nextFilter(f: ScanFilter): ScanFilter = ScanFilter.entries[(f.ordinal + 1) % ScanFilter.entries.size]

private fun filterLabel(f: ScanFilter): StringKey =
    when (f) {
        ScanFilter.COLOUR -> AdminStringKeys.ScannerFilterColour
        ScanFilter.GREYSCALE -> AdminStringKeys.ScannerFilterGrey
        ScanFilter.BLACK_WHITE -> AdminStringKeys.ScannerFilterBlackWhite
    }

@Composable
private fun PagesBody(
    vm: ScannerViewModel,
    onResult: (PickedFile) -> Unit,
    onCancel: () -> Unit,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    val state = vm.state
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScannerHeader(title = str(AdminStringKeys.ScannerPagesCount, state.pages.size), onClose = onCancel)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                val thumb by rememberScanBitmap(page.result)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    thumb?.let { Image(it, contentDescription = null, modifier = Modifier.size(72.dp), contentScale = ContentScale.Fit) }
                    PosTextButton(
                        text = str(AdminStringKeys.ScannerPageLabel, index + 1),
                        onClick = { scope.launch { vm.editPage(page.id) } },
                        modifier = Modifier.weight(1f),
                    )
                    PosIconButton(Icons.Filled.ArrowUpward, str(AdminStringKeys.ScannerMoveUp), { vm.movePage(page.id, -1) }, size = 48.dp)
                    PosIconButton(Icons.Filled.ArrowDownward, str(AdminStringKeys.ScannerMoveDown), { vm.movePage(page.id, 1) }, size = 48.dp)
                    PosIconButton(Icons.Filled.Delete, str(AdminStringKeys.ScannerDeletePage), { vm.deletePage(page.id) }, size = 48.dp)
                }
            }
        }
        // One row: another page first (the usual next step), then done.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosSecondaryButton(
                text = str(AdminStringKeys.ScannerAddPage),
                icon = Icons.Filled.Add,
                onClick = vm::addPage,
                modifier = Modifier.weight(1f),
            )
            PosButton(
                text = str(AdminStringKeys.ScannerDone),
                enabled = !state.busy && state.pages.isNotEmpty(),
                isLoading = state.busy,
                onClick = { scope.launch { vm.finish(onResult) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
