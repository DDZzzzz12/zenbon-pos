package org.example.project.ui.components.beleg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosLayout
import org.example.project.ui.theme.PosSpacing

/** How the strip lays itself out; chosen from the width it is given, never from the window class. */
enum class BelegFlowStripLayout {
    /** Icon, label, count and sub-text per block, chevrons between (≥ 840dp). */
    Large,

    /** Label above count, blocks side by side (600–839dp). */
    Medium,

    /** Four equal columns, count over label, the selected one underlined (< 600dp). */
    Compact,
}

/** Pure width → layout rule, pinned by a test. */
fun belegFlowStripLayoutFor(widthDp: Int): BelegFlowStripLayout =
    when {
        widthDp >= PosLayout.EXPANDED_MIN_WIDTH_DP -> BelegFlowStripLayout.Large
        widthDp >= PosLayout.MEDIUM_MIN_WIDTH_DP -> BelegFlowStripLayout.Medium
        else -> BelegFlowStripLayout.Compact
    }

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/**
 * The Belegfluss progress strip: Erfassen · Prüfen · Zuordnen · Erledigt (or any other short list of
 * [stages]) with live counts. It is a filter and a navigation at once — the caller decides what
 * [onSelect] does with a stage id.
 */
@Composable
fun BelegFlowStrip(
    stages: List<BelegStage>,
    onSelect: (String) -> Unit,
    groupDescription: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = groupDescription }) {
        when (belegFlowStripLayoutFor(maxWidth.value.toInt())) {
            BelegFlowStripLayout.Large ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).height(IntrinsicSize.Min)) {
                    stages.forEachIndexed { index, stage ->
                        if (index > 0) Chevron()
                        LargeBlock(stage, onSelect)
                    }
                }
            BelegFlowStripLayout.Medium ->
                Row(Modifier.fillMaxWidth()) {
                    stages.forEach { stage -> MediumBlock(stage, onSelect) }
                }
            BelegFlowStripLayout.Compact ->
                Row(Modifier.fillMaxWidth()) {
                    stages.forEach { stage -> CompactBlock(stage, onSelect) }
                }
        }
    }
}

@Composable
private fun RowScope.Chevron() {
    Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun countText(stage: BelegStage): String = stage.count?.toString() ?: "–"

@Composable
private fun contentColor(stage: BelegStage): Color = if (stage.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

@Composable
private fun mutedColor(stage: BelegStage): Color =
    if (stage.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun Modifier.blockSurface(
    stage: BelegStage,
    onSelect: (String) -> Unit,
): Modifier =
    this
        .background(if (stage.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
        .border(1.dp, if (stage.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        .semantics { selected = stage.selected }
        .clickable(role = Role.Tab) { onSelect(stage.id) }

@Composable
private fun RowScope.LargeBlock(
    stage: BelegStage,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .blockSurface(stage, onSelect)
                .padding(horizontal = PosSpacing.xl, vertical = PosSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
    ) {
        Icon(stage.icon, contentDescription = null, tint = mutedColor(stage), modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
                Text(
                    stage.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor(stage),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    countText(stage),
                    style = MaterialTheme.typography.titleMedium.merge(TABULAR),
                    fontWeight = FontWeight.Bold,
                    color = contentColor(stage),
                    maxLines = 1,
                )
            }
            stage.sub?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RowScope.MediumBlock(
    stage: BelegStage,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).blockSurface(stage, onSelect).padding(horizontal = PosSpacing.lg, vertical = PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.xs),
    ) {
        Text(
            stage.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = mutedColor(stage),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            countText(stage),
            style = MaterialTheme.typography.titleMedium.merge(TABULAR),
            fontWeight = FontWeight.Bold,
            color = contentColor(stage),
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.CompactBlock(
    stage: BelegStage,
    onSelect: (String) -> Unit,
) {
    val underline = if (stage.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .background(if (stage.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .drawBehind {
                    val thickness = 3.dp.toPx()
                    drawRect(underline, topLeft = Offset(0f, size.height - thickness), size = Size(size.width, thickness))
                }.semantics { selected = stage.selected }
                .clickable(role = Role.Tab) { onSelect(stage.id) }
                .padding(horizontal = PosSpacing.xs, vertical = PosSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            countText(stage),
            style = MaterialTheme.typography.titleMedium.merge(TABULAR),
            fontWeight = FontWeight.Bold,
            color = contentColor(stage),
            maxLines = 1,
        )
        Text(
            stage.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = mutedColor(stage),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
