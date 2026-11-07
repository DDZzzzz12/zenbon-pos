package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseEvent
import kotlin.math.abs

private val logger = Logger.withTag("TouchScreenFix")

// ── Configuration ────────────────────────────────────────────────────────────

@Volatile
private var scrollFactor: Double = 0.3

@Volatile
private var flingFactor: Double = 0.15

// ── Touch Interceptor State ──────────────────────────────────────────────────

/** Distance (px) the pointer can move before we consider it a drag, not a tap. */
private const val TOUCH_MOVE_THRESHOLD = 10

/**
 * Distance (px) jump in pointer position that indicates a "teleport" —
 * i.e., a finger touching the screen rather than a mouse gliding to a new spot.
 */
private const val TELEPORT_THRESHOLD = 50

/**
 * Time (ms) to wait after a teleport before deciding if it was touch or mouse.
 * Touch confirms via WHEEL (scroll) or EXITED (tap) within this window.
 * If only MOVED events arrive, it was a mouse moving fast.
 */
private const val PENDING_RESOLVE_MS = 150L

@Volatile
private var lastMouseX: Int = -1

@Volatile
private var lastMouseY: Int = -1

@Volatile
private var touchDown: Boolean = false

@Volatile
private var touchDownX: Int = 0

@Volatile
private var touchDownY: Int = 0

@Volatile
private var touchDownLocalX: Int = 0

@Volatile
private var touchDownLocalY: Int = 0

@Volatile
private var touchDragged: Boolean = false

@Volatile
private var touchStartTime: Long = 0

/** Settling period (ms) after touch start — WHEEL events are ignored during this window. */
private const val WHEEL_SETTLE_MS = 150

/** Cooldown period (ms) after touch end — WHEEL/MOVED events are ignored to prevent residual scroll. */
private const val POST_TOUCH_COOLDOWN_MS = 200

@Volatile
private var touchEndTime: Long = 0

@Volatile
private var touchSource: Component? = null

/** Event that triggered teleport detection, awaiting confirmation as touch or mouse. */
@Volatile
private var pendingTouchEvent: MouseEvent? = null

@Volatile
private var pendingTouchTime: Long = 0

@Volatile
private var interceptorInstalled: Boolean = false

/**
 * Reference count for instant tap mode. When > 0, pending resolve time is near-zero
 * so taps register instantly. Multiple components (keyboard, numpad) can request this
 * concurrently without interfering with each other.
 */
private val instantTapRefCount =
    java.util.concurrent.atomic
        .AtomicInteger(0)

/** True when any component has requested instant tap mode. */
val instantTapMode: Boolean get() = instantTapRefCount.get() > 0

/** Increments the instant tap mode reference count. */
fun requestInstantTapMode() {
    instantTapRefCount.incrementAndGet()
}

/** Decrements the instant tap mode reference count. */
fun releaseInstantTapMode() {
    instantTapRefCount.updateAndGet { (it - 1).coerceAtLeast(0) }
}

// ── Public API ───────────────────────────────────────────────────────────────

/**
 * Installs the AWT touch event interceptor and configures scroll dampening.
 *
 * Call **once** at startup, before `application {}`.
 *
 * @param initialScrollFactor Drag dampening (0.1-1.0, default 0.3)
 * @param initialFlingFactor Fling dampening (0.05-1.0, default 0.15)
 */
fun installTouchSupport(
    initialScrollFactor: Double = 0.3,
    initialFlingFactor: Double = 0.15,
) {
    scrollFactor = initialScrollFactor.coerceIn(0.1, 1.0)
    flingFactor = initialFlingFactor.coerceIn(0.05, 1.0)

    if (!interceptorInstalled) {
        Toolkit.getDefaultToolkit().systemEventQueue.push(TouchEventQueue())
        interceptorInstalled = true
        logger.i { "Touch interceptor installed: scroll=$scrollFactor, fling=$flingFactor" }
    }
}

/** Updates the drag scroll dampening factor at runtime. */
fun updateScrollFactor(factor: Double) {
    scrollFactor = factor.coerceIn(0.1, 1.0)
}

/** Updates the fling dampening factor at runtime. */
fun updateFlingFactor(factor: Double) {
    flingFactor = factor.coerceIn(0.05, 1.0)
}

// ── AWT Touch Event Queue ────────────────────────────────────────────────────

/**
 * Custom [EventQueue] that intercepts touchscreen input on Linux.
 *
 * On Linux, AWT translates touch into MOUSE_ENTERED/MOUSE_MOVED (hover) rather
 * than MOUSE_PRESSED. Since AWT provides no API to distinguish touch from mouse,
 * this queue uses **behavioral detection**:
 *
 * - On teleport (large position jump), enters a **pending** state instead of
 *   immediately synthesizing events
 * - **WHEEL** or **EXITED** during pending → confirmed as touch
 * - **Native PRESSED** or **timeout** during pending → confirmed as mouse
 *
 * This prevents false click synthesis when a mouse moves quickly or enters the window.
 */
private class TouchEventQueue : EventQueue() {
    override fun dispatchEvent(event: AWTEvent) {
        if (event !is MouseEvent) {
            super.dispatchEvent(event)
            return
        }

        // Check if pending teleport detection has timed out (→ mouse)
        resolvePendingTimeout()

        when (event.id) {
            MouseEvent.MOUSE_ENTERED -> handleEntered(event)
            MouseEvent.MOUSE_MOVED -> handleMoved(event)
            MouseEvent.MOUSE_DRAGGED -> handleDragged(event)
            MouseEvent.MOUSE_PRESSED -> handlePressed(event)
            MouseEvent.MOUSE_RELEASED -> handleReleased(event)
            MouseEvent.MOUSE_EXITED -> handleExited(event)
            MouseEvent.MOUSE_WHEEL -> handleWheel(event)
            else -> super.dispatchEvent(event)
        }
    }

    private fun handleEntered(event: MouseEvent) {
        if (isInPostTouchCooldown()) {
            updateLastPosition(event)
            super.dispatchEvent(event)
            return
        }
        if (isTeleport(event.xOnScreen, event.yOnScreen)) {
            // Don't immediately start touch — enter pending state for confirmation
            setPending(event)
            return
        }
        updateLastPosition(event)
        super.dispatchEvent(event)
    }

    private fun handleMoved(event: MouseEvent) {
        if (pendingTouchEvent != null) {
            // During pending: swallow MOVED (could be mouse hover or touch jitter)
            // Don't update lastMouseX/Y — keep it at pre-teleport position so that
            // if pending resolves as mouse, subsequent moves are tracked correctly
            return
        }
        if (touchDown) {
            // Check if pointer teleported — means finger lifted and new touch started
            if (isTeleport(event.xOnScreen, event.yOnScreen)) {
                endTouch(event)
                startTouch(event)
            }
            // Otherwise swallow MOVED during touch — scroll is driven by WHEEL events only
            return
        } else if (isInPostTouchCooldown()) {
            updateLastPosition(event)
            return
        } else if (isTeleport(event.xOnScreen, event.yOnScreen)) {
            setPending(event)
            return
        } else {
            updateLastPosition(event)
            super.dispatchEvent(event)
        }
    }

    private fun handleDragged(event: MouseEvent) {
        if (pendingTouchEvent != null) {
            // Native DRAGGED during pending → mouse (touch never generates DRAGGED)
            resolvePendingAsMouse()
            updateLastPosition(event)
            super.dispatchEvent(event)
            return
        }
        if (touchDown) {
            return
        } else {
            updateLastPosition(event)
            super.dispatchEvent(event)
        }
    }

    private fun handleWheel(event: MouseEvent) {
        if (pendingTouchEvent != null) {
            // WHEEL during pending → confirmed touch!
            val pending = pendingTouchEvent!!
            pendingTouchEvent = null
            startTouch(pending)
            // Fall through to handle this WHEEL in touch mode
        }
        if (touchDown) {
            updateLastPosition(event)

            val elapsed = System.currentTimeMillis() - touchStartTime
            if (elapsed < WHEEL_SETTLE_MS) {
                return
            }
            val dx = abs(event.xOnScreen - touchDownX)
            val dy = abs(event.yOnScreen - touchDownY)
            if (dx > TOUCH_MOVE_THRESHOLD || dy > TOUCH_MOVE_THRESHOLD) {
                touchDragged = true
                super.dispatchEvent(event)
            } else {
                return
            }
        } else if (isInPostTouchCooldown()) {
            return
        } else {
            super.dispatchEvent(event)
        }
    }

    private fun handlePressed(event: MouseEvent) {
        if (pendingTouchEvent != null) {
            // Native PRESSED during pending → confirmed mouse
            resolvePendingAsMouse()
            updateLastPosition(event)
            super.dispatchEvent(event)
            return
        }
        if (touchDown) {
            // Touch on Linux can generate native PRESSED after WHEEL events.
            // Since touchDown was confirmed via WHEEL/EXITED, this is part of
            // the touch sequence — swallow it.
            updateLastPosition(event)
            return
        }
        updateLastPosition(event)
        super.dispatchEvent(event)
    }

    private fun handleReleased(event: MouseEvent) {
        if (pendingTouchEvent != null) {
            // Native RELEASED during pending → mouse
            resolvePendingAsMouse()
            updateLastPosition(event)
            super.dispatchEvent(event)
            return
        }
        if (touchDown) {
            endTouch(event)
            lastMouseX = -1
            lastMouseY = -1
            return
        }
        updateLastPosition(event)
        super.dispatchEvent(event)
    }

    private fun handleExited(event: MouseEvent) {
        if (pendingTouchEvent != null) {
            // Quick EXITED during pending → confirmed touch tap!
            val pending = pendingTouchEvent!!
            pendingTouchEvent = null
            startTouch(pending)
            endTouch(event)
            super.dispatchEvent(event)
            return
        }
        if (touchDown) {
            endTouch(event)
            super.dispatchEvent(event)
            return
        }
        lastMouseX = -1
        lastMouseY = -1
        super.dispatchEvent(event)
    }

    // ── Pending State ────────────────────────────────────────────────────────

    private fun setPending(event: MouseEvent) {
        pendingTouchEvent = event
        pendingTouchTime = System.currentTimeMillis()
    }

    /**
     * If pending teleport has been waiting longer than the resolve threshold
     * without WHEEL/EXITED confirmation, resolve it as a mouse movement.
     *
     * When [instantTapMode] is active (virtual keyboard visible), the threshold
     * is reduced to 5ms so taps register near-instantly — no scroll detection
     * is needed in the keyboard area.
     */
    private fun resolvePendingTimeout() {
        pendingTouchEvent ?: return
        val threshold = if (instantTapMode) 5L else PENDING_RESOLVE_MS
        if (System.currentTimeMillis() - pendingTouchTime >= threshold) {
            if (instantTapMode) {
                // In instant tap mode, resolve as touch (not mouse) for immediate click
                val pending = pendingTouchEvent!!
                pendingTouchEvent = null
                startTouch(pending)
                endTouch(pending)
            } else {
                resolvePendingAsMouse()
            }
        }
    }

    /** Resolve pending teleport as mouse — update position and discard. */
    private fun resolvePendingAsMouse() {
        val pending = pendingTouchEvent ?: return
        pendingTouchEvent = null
        updateLastPosition(pending)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isInPostTouchCooldown(): Boolean = touchEndTime > 0 && (System.currentTimeMillis() - touchEndTime) < POST_TOUCH_COOLDOWN_MS

    private fun isTeleport(
        x: Int,
        y: Int,
    ): Boolean {
        if (lastMouseX == -1 && lastMouseY == -1) return true
        val dx = abs(x - lastMouseX)
        val dy = abs(y - lastMouseY)
        return (dx > TELEPORT_THRESHOLD || dy > TELEPORT_THRESHOLD)
    }

    private fun updateLastPosition(event: MouseEvent) {
        lastMouseX = event.xOnScreen
        lastMouseY = event.yOnScreen
    }

    private fun startTouch(event: MouseEvent) {
        touchDown = true
        touchDragged = false
        touchEndTime = 0
        touchStartTime = System.currentTimeMillis()
        touchDownX = event.xOnScreen
        touchDownY = event.yOnScreen
        touchDownLocalX = event.x
        touchDownLocalY = event.y
        touchSource = event.source as? Component
        updateLastPosition(event)

        // Synthesize and dispatch MOUSE_PRESSED
        val source = event.source
        if (source is Component) {
            val synthetic =
                MouseEvent(
                    source,
                    MouseEvent.MOUSE_PRESSED,
                    event.`when`,
                    MouseEvent.BUTTON1_DOWN_MASK,
                    event.x,
                    event.y,
                    event.xOnScreen,
                    event.yOnScreen,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            super.dispatchEvent(synthetic)
        }
    }

    private fun endTouch(event: MouseEvent) {
        val source = touchSource ?: event.source as? Component
        if (source != null) {
            val useLocalX = if (!touchDragged) touchDownLocalX else event.x
            val useLocalY = if (!touchDragged) touchDownLocalY else event.y
            val useScreenX = if (!touchDragged) touchDownX else event.xOnScreen
            val useScreenY = if (!touchDragged) touchDownY else event.yOnScreen

            val released =
                MouseEvent(
                    source,
                    MouseEvent.MOUSE_RELEASED,
                    event.`when`,
                    0,
                    useLocalX,
                    useLocalY,
                    useScreenX,
                    useScreenY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
            super.dispatchEvent(released)

            if (!touchDragged) {
                val clicked =
                    MouseEvent(
                        source,
                        MouseEvent.MOUSE_CLICKED,
                        event.`when`,
                        0,
                        useLocalX,
                        useLocalY,
                        useScreenX,
                        useScreenY,
                        1,
                        false,
                        MouseEvent.BUTTON1,
                    )
                super.dispatchEvent(clicked)
            }
        }

        touchDown = false
        touchDragged = false
        touchSource = null
        touchEndTime = System.currentTimeMillis()
    }
}

// ── Scroll Dampening ─────────────────────────────────────────────────────────

/**
 * [NestedScrollConnection] with separate dampening for drag and fling.
 *
 * - Drag (finger-on-screen): dampened by [scrollFactor]
 * - Fling (after release): dampened by [flingFactor] (more aggressive)
 *
 * Both factors are read on every event, so runtime updates take effect immediately.
 */
private val scrollDampeningConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val factor = scrollFactor.toFloat()
            if (factor >= 1.0f) return Offset.Zero
            return Offset(
                x = available.x * (1f - factor),
                y = available.y * (1f - factor),
            )
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // Consume ALL fling velocity — no momentum scrolling after finger lifts.
            // On a POS touchscreen, fling causes "first tap after scroll" to catch
            // residual momentum instead of registering as a click.
            return available
        }
    }

// ── Compose ViewConfiguration ────────────────────────────────────────────────

/**
 * Composable wrapper that overrides [ViewConfiguration] for touchscreen devices
 * and applies scroll dampening.
 *
 * - touchSlop: 62px (default 18px is too tight for finger jitter)
 * - minimumTouchTargetSize: 48dp (easier to hit with finger)
 * - Scroll dampening via [scrollDampeningConnection]
 */
@Composable
fun WithTouchFriendlyConfig(content: @Composable () -> Unit) {
    val current = LocalViewConfiguration.current
    val config =
        remember(current) {
            object : ViewConfiguration {
                override val touchSlop: Float get() = 62f
                override val longPressTimeoutMillis: Long get() = 500L
                override val doubleTapTimeoutMillis: Long get() = 300L
                override val doubleTapMinTimeMillis: Long get() = 40L
                override val minimumTouchTargetSize: DpSize get() = DpSize(48.dp, 48.dp)
            }
        }

    CompositionLocalProvider(LocalViewConfiguration provides config) {
        Box(modifier = Modifier.nestedScroll(scrollDampeningConnection)) {
            content()
        }
    }
}
