package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.closeWhenEmpty
import org.example.project.ui.theme.PosSpacing

/**
 * The phone filter header of an [AdminListScaffold] list filtered by one mandatory bucket (Zenbon
 * filter pattern, no search and no period): only the Filter button and the result line, which names
 * the applied bucket. The bucket is a view rather than a narrowing filter (there is no "unfiltered"
 * state), so nothing is counted on the button and there is no clear-all.
 *
 * Goes in the scaffold's `filters` slot on a compact window only; wider windows keep their chips.
 */
@Composable
fun <B> BucketFilterBar(
    resultText: String,
    filterPanel: PosFilterPanelState<BucketPanelFilter<B>>,
) {
    PosFilterBar(
        search = null,
        resultText = resultText,
        onOpenPanel = filterPanel::open,
        // The scaffold already pads its content.
        horizontalPadding = 0.dp,
    )
}

/**
 * The panel of [BucketFilterBar], editing [filterPanel]'s draft: every bucket of every group as a chip
 * (one choice across the groups), applied through [onSelect] only by the panel's button; closing it
 * drops the draft. Compose it once per screen, outside the scaffold's layout branches, so a resize
 * keeps it open with its draft.
 *
 * @param label the bucket's localized name, as on the wide chips.
 * @param count the bucket's count shown after its name, as on the wide chips; null shows none.
 * @param default the screen's opening bucket, which the panel's reset drafts.
 */
@Suppress("LongParameterList") // the panel's own configuration; grouping it would only rename it
@Composable
fun <B> BucketFilterPanel(
    groups: List<BucketChoiceGroup<B>>,
    applied: B,
    default: B,
    compact: Boolean,
    onSelect: (B) -> Unit,
    filterPanel: PosFilterPanelState<BucketPanelFilter<B>>,
    label: @Composable (B) -> String,
    count: ((B) -> String)? = null,
) {
    if (!filterPanel.isOpen) return
    val draft = filterPanel.draft
    // Widened past the point where the chips are back above the list, with nothing drafted.
    if (filterPanel.closeWhenEmpty(draft.value.showsBuckets(compact, applied))) return
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = { filterPanel.applyDraft { target -> target.toApply(applied)?.let(onSelect) } },
        onReset = {
            filterPanel.reset(compact = compact, clearDraft = { it.cleared(default) }, applyReset = { if (default != applied) onSelect(default) })
        },
    ) {
        groups.forEach { group ->
            PosFilterFieldGroup(caption = group.caption) {
                BucketChips(group.buckets, selected = draft.value.bucket, label = label, count = count) { picked ->
                    draft.value = BucketPanelFilter(picked)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <B> BucketChips(
    buckets: List<B>,
    selected: B,
    label: @Composable (B) -> String,
    count: ((B) -> String)?,
    onPick: (B) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        buckets.forEach { bucket ->
            FilterChip(
                selected = bucket == selected,
                onClick = { onPick(bucket) },
                label = { Text(label(bucket)) },
                trailingIcon = count?.let { countOf -> { Text(countOf(bucket)) } },
            )
        }
    }
}
