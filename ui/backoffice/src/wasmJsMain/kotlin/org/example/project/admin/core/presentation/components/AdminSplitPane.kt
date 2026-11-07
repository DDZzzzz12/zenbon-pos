package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.layout.DrillVisibility
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/** Below this much room for the pair, they no longer sit side by side: they drill, or stack without a back handler. */
internal val DEFAULT_MIN_WIDTH_FOR_SPLIT = 900.dp

/** How wide the list rail is when the two do sit side by side. */
private val DEFAULT_RAIL_WIDTH = 300.dp

/**
 * A list beside the detail of whatever is selected in it.
 *
 * Measured with [BoxWithConstraints] against the space this composable actually has, not against the
 * window: the admin panel already spends ~150dp on its sidebar, so a window-class breakpoint would
 * claim room the content does not have and split into two cramped columns.
 *
 * Below [minWidthForSplit], when [onCloseDetail] is supplied, the pair drills (see below). Without a
 * back handler the panes stack, list first — the shape a screen had before drilling existed, so a
 * caller that opted out degrades to the plain list it replaced rather than to a broken version of itself.
 *
 * The rail is a fixed [railWidth] rather than a weight: its content is names and IBANs, whose width
 * does not scale with the viewport, and a weighted rail clipped them at exactly the sizes where the
 * split is tightest.
 *
 * Below [minWidthForSplit] (never below 600dp), and only when [onCloseDetail] is supplied, the pair
 * becomes a drill across the whole stacked band: the list alone until [detailOpen], then the detail
 * alone under a back button.
 *
 * [detailEmpty] is true while nothing is selected: in the **stacked** layout the detail is then not
 * composed, because its "pick one on the left" prompt means nothing above a list; side by side it
 * still renders, where the prompt is right. Also: a drill never shows an empty detail, it stays on the list.
 */
@Composable
fun AdminSplitPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minWidthForSplit: Dp = DEFAULT_MIN_WIDTH_FOR_SPLIT,
    railWidth: Dp = DEFAULT_RAIL_WIDTH,
    detailOpen: Boolean = false,
    onCloseDetail: (() -> Unit)? = null,
    detailEmpty: Boolean = false,
) {
    val guard = LocalLeaveGuard.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Drill mode is opt-in: a caller that supplies no way back keeps the stacked behaviour.
        val visibility =
            PaneBreakpoints.drillVisibility(
                maxWidth,
                detailOpen && !detailEmpty,
                optedIn = onCloseDetail != null,
                splitMin = minWidthForSplit,
            )
        when (visibility) {
            DrillVisibility.ListOnly -> list()
            DrillVisibility.DetailOnly ->
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                    onCloseDetail?.let { close ->
                        TextButton(onClick = { guard.requestLeave(close) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(PosSpacing.md))
                            Text(str(CommonKeys.back))
                        }
                    }
                    detail()
                }
            DrillVisibility.Both ->
                if (maxWidth >= minWidthForSplit) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LocalPosLayout.current.paneGap),
                    ) {
                        Column(Modifier.width(railWidth)) { list() }
                        Column(Modifier.weight(1f)) { detail() }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.paneGap),
                    ) {
                        list()
                        if (!detailEmpty) detail()
                    }
                }
        }
    }
}
