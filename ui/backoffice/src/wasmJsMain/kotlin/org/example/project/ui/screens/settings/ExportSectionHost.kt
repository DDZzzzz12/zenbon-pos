package org.example.project.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import org.example.project.ui.layout.PaneSection

/**
 * Datenexport's peer sections, one visible at a time behind a wrapping [FilterChip] selector at every
 * width. Local to this screen: the shared `PosPaneHost` stacks at middle widths, which made this page a
 * long scroll of unrelated cards.
 *
 * Every section stays composed. The unselected ones are laid out at zero size, clipped and not
 * focusable, so unsaved input survives a switch and a section's one-time `LaunchedEffect(Unit)` load
 * (the account mapping's) does not run again. The selection defaults to the first section and falls
 * back to it when the selected id disappears ([selectedSectionOrFirst]). Adds no scroll.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportSectionHost(
    sections: List<PaneSection>,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) return
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedSectionOrFirst(sections, selectedId)

    Column(modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sections.forEach { section ->
                FilterChip(
                    selected = section.id == selected.id,
                    onClick = { selectedId = section.id },
                    label = { Text(section.title) },
                )
            }
        }
        sections.forEach { section ->
            key(section.id) {
                val visibleModifier =
                    if (section.id == selected.id) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.size(0.dp).clipToBounds().focusProperties { canFocus = false }
                    }
                Box(visibleModifier) { section.content() }
            }
        }
    }
}
