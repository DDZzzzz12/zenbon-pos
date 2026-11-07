package org.example.project.ui.components.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosHorizontalScroller
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSearchBar
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The one filter header above every list, table and grid (Zenbon "FilterBar" pattern).
 *
 * **Phone width: exactly one row of filter controls** — the search field (or, with
 * [periodIsPrimary], the [period] control) plus an icon-only filter button carrying
 * [activeFilterCount]. Quick chips, applied chips and the period live in the [PosFilterPanel] there.
 * **Wider windows:** a primary row (search · period · filter button) and at most one chip row —
 * [quickFilters], a divider, then removable [appliedFilters]. The chip row scrolls sideways and never
 * wraps, so the header height is fixed.
 *
 * Both layouts end with the result line: [resultText] ("128 Belege"), plus "· 3 Filter" on phones,
 * and the clear-all action when anything is applied.
 *
 * Purely a view: all state belongs to the caller. Search should apply as the user types (debounced
 * by the caller), quick chips immediately, panel fields only from the panel's apply button.
 *
 * Width decisions (does search + period + this Filter button fit the wide row?) go through
 * [PosFilterBarLayout], never a per-screen guess of the button's width. The panel's state belongs in
 * a [PosFilterPanelState] remembered above the screen's layout branches.
 *
 * @param onOpenPanel null when the screen has no panel (the till); quick chips then show in the chip
 *   row on every width, because there is nowhere else to put them.
 * @param onClearAll **contract:** clears the quick filters and the panel filters, and **never the
 *   search text while [search] is in the bar** — what the operator typed in front of them stays. Where
 *   search has moved into the panel (a period-primary screen on a narrow window), it *is* a panel
 *   filter and clear-all clears it too. null hides the action.
 * @param resultContent replaces the plain [resultText] line with the caller's own content (Tische: the
 *   count with its status legend, whose dots need colour); [resultText] stays the accessible fallback
 *   and is ignored while this is set. The "N Filter" suffix of a phone is not added to it.
 * @param labelledPhoneFilterButton a phone shows the Filter button with its word ("Filter (2)") instead of the
 *   icon-only default, for screens whose design labels it (Terminals, Produkte).
 * @param horizontalPadding the bar's own side gutter, the screen padding by default. A caller that
 *   already sits inside a padded container (adminApp's `AdminListScaffold`) passes 0.dp so the bar
 *   lines up with the content below it instead of doubling the gutter.
 */
@Composable
fun PosFilterBar(
    search: PosFilterSearch?,
    resultText: String,
    modifier: Modifier = Modifier,
    period: (@Composable () -> Unit)? = null,
    periodIsPrimary: Boolean = false,
    quickFilters: List<PosFilterChipItem> = emptyList(),
    appliedFilters: List<PosFilterChipItem> = emptyList(),
    activeFilterCount: Int = appliedFilters.size,
    onOpenPanel: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    horizontalPadding: Dp = LocalPosLayout.current.screenPadding,
    resultContent: (@Composable () -> Unit)? = null,
    labelledPhoneFilterButton: Boolean = false,
) {
    val compact = LocalPosLayout.current.isCompact
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = PosSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compact) {
                    CompactPrimaryRow(search, period, periodIsPrimary, activeFilterCount, onOpenPanel, labelledPhoneFilterButton)
                } else {
                    WidePrimaryRow(search, period, activeFilterCount, onOpenPanel)
                }
            }
            val chipsOnThisWidth = if (compact) onOpenPanel == null else true
            if (chipsOnThisWidth && (quickFilters.isNotEmpty() || appliedFilters.isNotEmpty())) {
                FilterChipRow(quickFilters, if (compact) emptyList() else appliedFilters)
            }
        }
        ResultLine(
            horizontalPadding = horizontalPadding,
            content = resultContent,
            text =
                if (compact && activeFilterCount > 0) {
                    "$resultText · ${str(PosPatternKeys.filterActiveCount, activeFilterCount)}"
                } else {
                    resultText
                },
            clearLabel =
                if (compact) str(PosPatternKeys.filterReset) else str(PosPatternKeys.filterClearAll),
            onClearAll = onClearAll.takeIf { activeFilterCount > 0 || quickFilters.any { it.selected } },
        )
    }
}

@Composable
private fun RowScope.CompactPrimaryRow(
    search: PosFilterSearch?,
    period: (@Composable () -> Unit)?,
    periodIsPrimary: Boolean,
    activeFilterCount: Int,
    onOpenPanel: (() -> Unit)?,
    labelled: Boolean,
) {
    Row(modifier = Modifier.weight(1f)) {
        if (periodIsPrimary && period != null) period() else search?.let { SearchField(it) }
    }
    if (onOpenPanel != null) {
        if (labelled) {
            PosSecondaryButton(
                text = if (activeFilterCount > 0) "${str(PosPatternKeys.filter)} ($activeFilterCount)" else str(PosPatternKeys.filter),
                icon = Icons.Filled.Tune,
                onClick = onOpenPanel,
            )
        } else {
            CompactFilterButton(activeFilterCount, onOpenPanel)
        }
    }
}

@Composable
private fun RowScope.WidePrimaryRow(
    search: PosFilterSearch?,
    period: (@Composable () -> Unit)?,
    activeFilterCount: Int,
    onOpenPanel: (() -> Unit)?,
) {
    // The weighted slot takes what the fixed-width controls leave; empty, it still keeps the
    // controls right-aligned on a screen without search.
    Row(modifier = Modifier.weight(1f)) { search?.let { SearchField(it) } }
    period?.invoke()
    if (onOpenPanel != null) {
        PosSecondaryButton(
            text =
                if (activeFilterCount > 0) {
                    "${str(PosPatternKeys.filter)} ($activeFilterCount)"
                } else {
                    str(PosPatternKeys.filter)
                },
            icon = Icons.Filled.Tune,
            onClick = onOpenPanel,
            height = 40.dp,
        )
    }
}

@Composable
private fun SearchField(search: PosFilterSearch) {
    PosSearchBar(
        query = search.query,
        onQueryChange = search.onQueryChange,
        placeholder = search.placeholder,
        modifier = Modifier.fillMaxWidth(),
        onClear = { search.onQueryChange("") },
        clearContentDescription = str(PosPatternKeys.filterReset),
    )
}

@Composable
private fun CompactFilterButton(
    activeFilterCount: Int,
    onOpenPanel: () -> Unit,
) {
    val description = str(PosPatternKeys.filterButtonDescription, activeFilterCount)
    BadgedBox(
        modifier = Modifier.semantics { contentDescription = description },
        badge = {
            if (activeFilterCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(activeFilterCount.toString()) }
            }
        },
    ) {
        if (activeFilterCount > 0) {
            PosFilledIconButton(icon = Icons.Filled.Tune, contentDescription = null, onClick = onOpenPanel)
        } else {
            PosIconButton(icon = Icons.Filled.Tune, contentDescription = null, onClick = onOpenPanel)
        }
    }
}

@Composable
private fun FilterChipRow(
    quickFilters: List<PosFilterChipItem>,
    appliedFilters: List<PosFilterChipItem>,
) {
    PosHorizontalScroller(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        quickFilters.forEach { item ->
            FilterChip(
                selected = item.selected,
                onClick = item.onClick,
                label = { Text(item.label) },
                leadingIcon =
                    if (item.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
            )
        }
        if (quickFilters.isNotEmpty() && appliedFilters.isNotEmpty()) {
            VerticalDivider(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        val removeDescription = str(PosPatternKeys.filterRemoveDescription)
        appliedFilters.forEach { item ->
            FilterChip(
                selected = true,
                onClick = item.onClick,
                label = { Text(item.label) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, contentDescription = removeDescription, modifier = Modifier.size(18.dp))
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        selectedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                border = null,
            )
        }
    }
}

@Composable
private fun ResultLine(
    horizontalPadding: Dp,
    content: (@Composable () -> Unit)?,
    text: String,
    clearLabel: String,
    onClearAll: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (content != null) {
            Box(Modifier.weight(1f)) { content() }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (onClearAll != null) {
            PosTextButton(
                text = clearLabel,
                onClick = onClearAll,
                height = 32.dp,
                contentPadding = PaddingValues(horizontal = PosSpacing.md),
            )
        }
    }
}
