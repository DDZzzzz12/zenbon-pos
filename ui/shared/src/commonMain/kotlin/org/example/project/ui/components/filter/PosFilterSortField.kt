package org.example.project.ui.components.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.touchTargetSize
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.theme.PosSpacing

/**
 * The panel's sort field: a [PosFilterPicker] for the sort key ([keys], shown by [keyLabel]) with the
 * ascending / descending segmented row right under it. Both edit the caller's draft through
 * [onChange]; changing the key keeps the direction, changing the direction keeps the key.
 *
 * The strings are the generic `PosPatternKeys.sortLabel/sortAscending/sortDescending`; never add a
 * per-screen copy.
 */
@Composable
fun <K> PosFilterSortField(
    keys: List<K>,
    selectedKey: K,
    ascending: Boolean,
    keyLabel: @Composable (K) -> String,
    onChange: (key: K, ascending: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The picker carries the "Sortierung" label; the direction row sits right under it.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        PosFilterPicker(label = str(PosPatternKeys.sortLabel), value = keyLabel(selectedKey)) { close ->
            keys.forEach { option ->
                PosDropdownItem(
                    text = keyLabel(option),
                    selected = option == selectedKey,
                    minHeight = touchTargetSize(40.dp),
                    onClick = {
                        close()
                        onChange(option, ascending)
                    },
                )
            }
        }
        PosSegmentedButtonRow(
            options = listOf(true, false),
            selectedOption = ascending,
            onOptionSelected = { onChange(selectedKey, it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = touchTargetSize(40.dp)),
            optionLabel = { posSortDirectionLabel(it) },
        )
    }
}

/** "Aufsteigend" / "Absteigend": the direction row's labels and the sort chip's direction word. */
@Composable
fun posSortDirectionLabel(ascending: Boolean): String = if (ascending) str(PosPatternKeys.sortAscending) else str(PosPatternKeys.sortDescending)
