package org.example.project.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.touchTargetSize
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.filter.PosFilterPicker

/**
 * Places peer [sections] by the width the content has (see [PaneBreakpoints]):
 *  - phone: one section at a time, swipe or chips (a wrapping selector, never a scrolling tab strip);
 *    the other sections stay composed, so unsaved input and loaded data survive a switch;
 *  - middle widths: every section stacked, exactly like a plain Column;
 *  - wide: up to [maxColumns] columns; **every** section is composed and flowed into the currently
 *    shortest column (order kept), so no section is hidden and the columns stay balanced.
 *
 * [gutter] is the screen's own padding per side, added back before deciding (the host measures
 * inside it). [showSectionTitles] draws each section's title above it (only with several sections),
 * for screens whose sections do not carry a heading of their own. [selectorStyle] picks the phone
 * selector: wrapping chips (default, what every host had before) or a one-line dropdown naming the
 * current section ([PaneSelectorStyle.Dropdown]), for hosts whose chips would take two lines.
 *
 * It adds **no vertical scroll** of its own: screens in adminApp already sit inside a scrolling
 * scaffold, and nesting a second scroll crashes on wasm. The column layout therefore measures its
 * children with unbounded height.
 */
@Composable
fun PosPaneHost(
    sections: List<PaneSection>,
    modifier: Modifier = Modifier,
    maxColumns: Int = PaneBreakpoints.MAX_COLUMNS,
    gutter: Dp = 0.dp,
    showSectionTitles: Boolean = false,
    selectorStyle: PaneSelectorStyle = PaneSelectorStyle.Default,
) {
    if (sections.isEmpty()) return
    var selectedId by remember { mutableStateOf(sections.first().id) }
    val selectedIndex = PaneSelection.pageOf(sections, selectedId) ?: 0
    val withTitles = showSectionTitles && sections.size > 1

    BoxWithConstraints(modifier.fillMaxWidth()) {
        when (val layout = PaneBreakpoints.layoutFor(maxWidth, maxColumns, gutter)) {
            PaneLayout.Single -> SinglePaneSwipe(sections, selectedIndex, selectorStyle) { selectedId = it }
            PaneLayout.Stacked ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    sections.forEach { section -> key(section.id) { SectionBlock(section, withTitles) } }
                }
            is PaneLayout.Columns ->
                BalancedColumns(count = layout.count, gap = COLUMN_GAP) {
                    sections.forEach { section -> key(section.id) { SectionBlock(section, withTitles) } }
                }
        }
    }
}

private val COLUMN_GAP: Dp = 16.dp

@Composable
private fun SectionBlock(
    section: PaneSection,
    showTitle: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(section.title, style = MaterialTheme.typography.titleMedium)
        section.content()
    }
}

/**
 * Flows its children (one layout node each) into [count] equal columns, each child under the
 * currently shortest column ([PaneBreakpoints.balancedColumns]). Children are measured at the column
 * width with unbounded height, so the host stays free of any scroll container of its own.
 */
@Composable
private fun BalancedColumns(
    count: Int,
    gap: Dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val columnWidth = ((constraints.maxWidth - gapPx * (count - 1)) / count).coerceAtLeast(0)
        val childConstraints = Constraints(minWidth = columnWidth, maxWidth = columnWidth, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(childConstraints) }
        val assignment = PaneBreakpoints.balancedColumns(placeables.map { it.height }, count)
        val columnTops = IntArray(count)
        val tops =
            placeables.mapIndexed { index, placeable ->
                val column = assignment[index]
                val top = columnTops[column]
                columnTops[column] = top + placeable.height + gapPx
                top
            }
        val height = columnTops.maxOf { if (it > 0) it - gapPx else 0 }
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(assignment[index] * (columnWidth + gapPx), tops[index])
            }
        }
    }
}

/**
 * Phone: swipe between sections, chip selector kept (discoverability, mouse). Every page stays
 * composed (`beyondViewportPageCount`) so unsaved input and loaded data survive a swipe, as the
 * zero-size slots did; pages other than the current one are not focusable (Tab must not enter them).
 * The settled page is reported upward as [selectedId], which only drives this phone pager (the wider
 * layouts show every section).
 */
@Composable
private fun SinglePaneSwipe(
    sections: List<PaneSection>,
    selectedIndex: Int,
    selectorStyle: PaneSelectorStyle,
    onSelect: (String) -> Unit,
) {
    if (!PaneSelection.needsSelector(sections.size)) {
        sections.first().content()
        return
    }
    val pagerState = rememberPagerState(initialPage = selectedIndex.coerceIn(0, sections.lastIndex)) { sections.size }
    val currentSections by rememberUpdatedState(sections)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentSections.getOrNull(page)?.let { onSelect(it.id) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val selectSection: (String) -> Unit = { id ->
            PaneSelection.pageOf(sections, id)?.let { target -> scope.launch { pagerState.animateScrollToPage(target) } }
        }
        when (selectorStyle) {
            PaneSelectorStyle.Chips -> PaneSelector(sections, isSelected = { it == pagerState.currentPage }, onSelect = selectSection)
            PaneSelectorStyle.Dropdown -> PaneDropdownSelector(sections, pagerState.currentPage, onSelect = selectSection)
        }
        val density = LocalDensity.current
        val pageHeights = remember(sections.map { it.id }) { mutableStateMapOf<Int, Int>() }
        // A pager is as tall as its tallest composed page, so a short page would sit above a blank
        // area. Once the pages report their natural height the pager is pinned to the visible one
        // (the taller of current and target while a swipe is in flight, so nothing is clipped).
        val pagerHeightPx =
            listOf(pagerState.currentPage, pagerState.targetPage)
                .mapNotNull { pageHeights[it] }
                .maxOrNull()
                ?.takeIf { it > 0 }
        HorizontalPager(
            state = pagerState,
            modifier =
                if (pagerHeightPx != null) {
                    Modifier.fillMaxWidth().height(with(density) { pagerHeightPx.toDp() })
                } else {
                    Modifier.fillMaxWidth()
                },
            beyondViewportPageCount = sections.size - 1,
            pageSpacing = 16.dp,
            verticalAlignment = Alignment.Top,
            key = { index -> sections.getOrNull(index)?.id ?: index },
        ) { page ->
            // unbounded: the page is measured at its natural height, never squeezed by the pager's.
            Box(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .then(if (page == pagerState.currentPage) Modifier else Modifier.focusProperties { canFocus = false }),
            ) {
                Box(Modifier.fillMaxWidth().onSizeChanged { pageHeights[page] = it.height }) {
                    sections[page].content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaneSelector(
    sections: List<PaneSection>,
    isSelected: (Int) -> Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEachIndexed { index, section ->
            FilterChip(
                selected = isSelected(index),
                onClick = { onSelect(section.id) },
                label = { Text(section.title) },
                modifier = Modifier.heightIn(min = touchTargetSize(32.dp)),
            )
        }
    }
}

/**
 * [PaneSelectorStyle.Dropdown]: one full-width field naming the current section (no caption, the
 * section title is the value) that opens the list of all sections.
 */
@Composable
private fun PaneDropdownSelector(
    sections: List<PaneSection>,
    currentPage: Int,
    onSelect: (String) -> Unit,
) {
    val description = str(PosPatternKeys.paneSelectorDescription)
    PosFilterPicker(
        label = "",
        value = PaneSelection.titleAt(sections, currentPage),
        modifier = Modifier.semantics { contentDescription = description },
    ) { close ->
        sections.forEachIndexed { index, section ->
            PosDropdownItem(
                text = section.title,
                selected = index == currentPage,
                onClick = {
                    onSelect(section.id)
                    close()
                },
            )
        }
    }
}
