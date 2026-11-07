package org.example.project.ui.components.table

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldAppearance

/** Start inset of table-cell text; matches the internal padding of the Borderless text field so nothing jumps on edit. */
val PosTableCellInset: Dp = 16.dp

private val CellMinHeight = 48.dp

/** The height of the Borderless text field, so the text state and the field state occupy the same row height. */
private val TextCellMinHeight = 56.dp

/**
 * Editable text cell. Shows ellipsized text until tapped, then swaps in the real field.
 *
 * [errorText] outlines the cell in the error colour and puts the message on its own line **beneath**
 * the field, growing the row; the field keeps its full height (and the 48dp touch target), and the
 * placeholder stays the placeholder instead of being replaced by the error.
 */
@Composable
fun RowScope.PosTableTextCell(
    value: String,
    onValueChange: (String) -> Unit,
    weight: Float,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    errorText: String? = null,
) {
    CellWithMessage(weight = weight, errorText = errorText) {
        EllipsizingEditCell(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            placeholder = placeholder,
            keyboardType = KeyboardType.Text,
            isError = errorText != null,
        )
    }
}

/**
 * Editable numeric cell. [digitsOnly] rejects non-digit input keystrokes (integer fields like
 * seats). For money, keep [digitsOnly] false and parse/format via the `:platform-money` kernel in the caller
 * — never a Double.
 */
@Composable
fun RowScope.PosTableNumberCell(
    value: String,
    onValueChange: (String) -> Unit,
    weight: Float,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    digitsOnly: Boolean = true,
    errorText: String? = null,
) {
    CellWithMessage(weight = weight, errorText = errorText) {
        EllipsizingEditCell(
            value = value,
            onValueChange = { input ->
                if (!digitsOnly || input.isEmpty() || input.all { it.isDigit() }) onValueChange(input)
            },
            modifier = modifier,
            placeholder = placeholder,
            keyboardType = KeyboardType.Number,
            isError = errorText != null,
        )
    }
}

/**
 * The weighted slot of an editable cell: the field, then — only while there is one — its error message
 * on a line of its own beneath it, aligned with the cell text. A grid cell has no supporting-text slot
 * of its own, which is why the message used to replace the placeholder inside the field (UI-E2-06).
 */
@Composable
private fun RowScope.CellWithMessage(
    weight: Float,
    errorText: String?,
    field: @Composable () -> Unit,
) {
    Column(modifier = Modifier.weight(weight).padding(end = 8.dp)) {
        field()
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = PosTableCellInset, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * Text that ellipsizes (a text field would scroll it) until tapped; a tap swaps in the borderless
 * field with focus, and losing focus swaps the text back.
 */
@Composable
private fun EllipsizingEditCell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String,
    keyboardType: KeyboardType,
    isError: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing) {
            hadFocus = false
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier =
            Modifier.fillMaxWidth().onFocusChanged {
                if (it.hasFocus) {
                    hadFocus = true
                } else if (editing && hadFocus) {
                    editing = false
                }
            },
    ) {
        if (editing) {
            PosTextField(
                value = value,
                onValueChange = onValueChange,
                label = "",
                placeholder = placeholder,
                keyboardType = keyboardType,
                textStyle = MaterialTheme.typography.bodyMedium,
                appearance = PosTextFieldAppearance.Borderless,
                caretAtEndOnFocus = true,
                focusRequester = focusRequester,
                isError = isError,
                modifier = modifier.fillMaxWidth(),
            )
        } else {
            val empty = value.isEmpty()
            Text(
                text = if (empty) placeholder else value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .heightIn(min = TextCellMinHeight)
                        .then(
                            if (isError) {
                                Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                            } else {
                                Modifier
                            },
                        )
                        // Before clickable, so it observes the focusable that clickable adds: reaching the
                        // cell with Tab or IME-Next enters edit mode just like a tap. The field then takes
                        // the focus (see the LaunchedEffect), and blur flips editing back off.
                        .onFocusChanged { if (it.isFocused) editing = true }
                        .clickable { editing = true }
                        .padding(horizontal = PosTableCellInset)
                        .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * Dropdown/enum cell. Options are (key, label); [selectedLabel] is shown ellipsized. The anchor is
 * a plain clickable row (a picker field would scroll its text), and the menu is a [PosDropdown]
 * that positions itself against it.
 */
@Composable
fun <K> RowScope.PosTableDropdownCell(
    selectedLabel: String,
    options: List<Pair<K, String>>,
    onSelected: (K) -> Unit,
    weight: Float,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.weight(weight).padding(end = 8.dp).then(modifier)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CellMinHeight)
                    .clickable { expanded = true }
                    .padding(start = PosTableCellInset, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                PosDropdownItem(text = label, selected = label == selectedLabel, onClick = {
                    onSelected(key)
                    expanded = false
                })
            }
        }
    }
}

/** Boolean toggle cell. */
@Composable
fun RowScope.PosTableCheckboxCell(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    weight: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = Modifier.weight(weight), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier)
    }
}

/** Read-only text cell (used when canEdit is false, or for computed/runtime columns). */
@Composable
fun RowScope.PosTableReadonlyCell(
    text: String,
    weight: Float,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(weight).then(modifier),
    )
}

/** Trailing actions cell — caller supplies the icon buttons. */
@Composable
fun RowScope.PosTableActionsCell(
    weight: Float,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.weight(weight).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
