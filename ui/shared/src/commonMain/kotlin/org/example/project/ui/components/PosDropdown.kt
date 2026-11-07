package org.example.project.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.example.project.ui.theme.PosSpacing

/** The gap a menu keeps from the window edge when it is clamped into the window (NEW-14). */
private val DROPDOWN_EDGE_MARGIN = 8.dp

/**
 * An anchored panel — the replacement for Material's `DropdownMenu` across this app.
 *
 * `DropdownMenu` draws its own elevated, tonally-shaded container with Material's menu shape and
 * item metrics, which reads as a different design language from the surfaces around it. This is a
 * bare [Popup] plus a flat [Surface]: hairline border, card radius, no tonal shading and no shadow
 * unless a caller asks for one. It carries **no item component of its own** — put whatever rows the
 * surrounding UI already uses inside it, so a menu opened from the navigation drawer is built from
 * the same rows as the drawer rather than from menu items that merely sit beside it.
 *
 * [anchor] chooses the side and alignment, and flips when the window has no room — see
 * [PosDropdownPositionProvider].
 *
 * Dismissal is handled for you: clicking outside or pressing escape calls [onDismissRequest].
 * **That callback must be idempotent.** Clicking the anchor while the panel is open fires dismissal
 * *and* the anchor's own click in the same gesture, so an `onDismissRequest` that toggles instead of
 * closing reopens the panel and the control appears stuck open.
 *
 * @param maxWidth **load-bearing, not cosmetic.** A popup is measured against the whole window, so
 *   any child that calls `fillMaxWidth` — `PosSidebarNavItem` does — stretches the panel from the
 *   anchor to the far edge of the screen. A floor alone does not stop that; this is the ceiling.
 * @param maxHeight the panel scrolls past this rather than running off-window. The System group's
 *   eleven rows are what this exists for.
 */
@Composable
fun PosDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: PosDropdownAnchor = PosDropdownAnchor.BottomStart,
    gap: Dp = 4.dp,
    minWidth: Dp = 180.dp,
    maxWidth: Dp = 320.dp,
    maxHeight: Dp = 420.dp,
    shadowElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return

    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    val edgeMarginPx = with(LocalDensity.current) { DROPDOWN_EDGE_MARGIN.roundToPx() }
    Popup(
        popupPositionProvider = PosDropdownPositionProvider(anchor, gapPx, edgeMarginPx),
        onDismissRequest = onDismissRequest,
        // focusable so an outside click and the escape key both reach onDismissRequest.
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                modifier
                    .widthIn(min = minWidth, max = maxWidth)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.medium,
                    ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = shadowElevation,
        ) {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = PosSpacing.sm, horizontal = PosSpacing.sm),
                content = content,
            )
        }
    }
}
