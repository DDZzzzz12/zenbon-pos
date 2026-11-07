package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import org.example.project.ui.layout.PaneBreakpoints

/**
 * Hosts a list/detail screen that drills on a phone and tells it whether the **detail alone** is on
 * screen, so the screen can drop its header (`AdminListScaffold(showHeader = !phoneDetail)`) and keep
 * its loading skeleton and empty state out of the way of an open detail.
 *
 * The width it measures is this box minus the scaffold's own padding on both sides, which is exactly
 * what the [AdminSplitPane] inside the scaffold sees, so header and pane can never disagree.
 * Pass the same [minWidthForSplit] the inner [AdminSplitPane] gets.
 * Debtors wrote this by hand as the Slice 0 pilot; every drill screen now goes through here.
 */
@Composable
fun AdminDrillHost(
    detailOpen: Boolean,
    hasSelection: Boolean,
    modifier: Modifier = Modifier,
    minWidthForSplit: Dp = DEFAULT_MIN_WIDTH_FOR_SPLIT,
    content: @Composable (phoneDetail: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = maxWidth - AdminListScaffoldPadding * 2
        content(PaneBreakpoints.isPhoneDetail(contentWidth, detailOpen, hasSelection, splitMin = minWidthForSplit))
    }
}
