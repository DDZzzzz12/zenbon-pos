package org.example.project.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Matches the height of a `FilterChip` row, so a search bar can sit in a filter bar without towering over it. */
private val MIN_HEIGHT = 40.dp

/**
 * A search input that reads as a filter control rather than a form field.
 *
 * A thin wrapper over [PosFlatField] — the shared flat input surface — adding a magnifier and an
 * optional clear affordance.
 *
 * Deliberately **not** [PosTextField]'s default: that wraps a Material `OutlinedTextField`, 56dp tall
 * and carries a floating-label notch cut into its outline. Correct in a form, wrong beside 40dp
 * chips and a segmented period stepper — the Belege filter bar had exactly that mismatch, with the
 * same string passed as both `label` and `placeholder` so the label floated up to reveal a
 * placeholder saying the same thing.
 *
 * **Sizing is the caller's.** This used to append `.fillMaxWidth().padding(12.dp)` after the
 * caller's [modifier], which silently overrode it — `TableManagementContent` asked for
 * `Modifier.width(240.dp)` and got full width for as long as that call site has existed. Callers now
 * state their own width, which is what lets this sit in a `Row` beside other controls.
 *
 * @param onClear when non-null, a clear affordance appears while [query] is non-empty.
 *   [clearContentDescription] is then its accessible name and must be localized by the caller.
 */
@Composable
fun PosSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    clearContentDescription: String? = null,
) {
    PosFlatField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = Icons.Default.Search,
        trailing =
            if (onClear != null && query.isNotEmpty()) {
                {
                    PosIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = clearContentDescription,
                        onClick = onClear,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                }
            } else {
                null
            },
    )
}
