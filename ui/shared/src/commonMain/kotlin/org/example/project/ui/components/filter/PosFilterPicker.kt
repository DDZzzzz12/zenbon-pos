package org.example.project.ui.components.filter

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.core.utils.touchTargetSize
import org.example.project.ui.components.LocalPosFlatFieldMinHeight
import org.example.project.ui.components.POS_FLAT_FIELD_MIN_HEIGHT
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosPickerField

/**
 * A labelled single-choice field showing [value] that opens a dropdown exactly as wide as itself
 * (a panel field such as Mitarbeiter or Sortierung, or a view switch whose labels do not fit a
 * segmented row). [items] draws the `PosDropdownItem`s and calls `close` after a choice.
 *
 * The field box is the tap target, so it is at least 48dp tall on every non-expanded window
 * (`touchTargetSize`, the same rule as `adminTouchSize`) and keeps the flat field's 40dp on a
 * desktop-sized window. Only the box grows; the label above it is unchanged.
 */
@Composable
fun PosFilterPicker(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalPosFlatFieldMinHeight provides touchTargetSize(POS_FLAT_FIELD_MIN_HEIGHT)) {
            PosPickerField(value = value, onClick = { open = true }, label = label, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        }
        PosDropdown(expanded = open, onDismissRequest = { open = false }, minWidth = maxWidth, maxWidth = maxWidth) {
            items { open = false }
        }
    }
}
