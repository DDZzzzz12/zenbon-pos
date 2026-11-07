package org.example.project.ui.screens.order

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import org.example.project.core.browser.isBrowserClient
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardType

@Composable
fun PosProductSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onEnter: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val controller = LocalVirtualKeyboard.current
    val focusManager = LocalFocusManager.current
    val fieldId = remember { "product-search-bar" }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // The browser till is driven with a physical keyboard captured by the order screen, so the
    // in-app keyboard is never offered there; the field only displays what was typed.
    val usesVirtualKeyboard = !isBrowserClient

    LaunchedEffect(isFocused) {
        if (!usesVirtualKeyboard) return@LaunchedEffect
        if (isFocused) {
            controller.registerField(fieldId, value, onValueChange, VirtualKeyboardType.ALPHABETIC, onEnter)
        } else {
            controller.unregisterField(fieldId)
        }
    }

    // Clear focus when keyboard is dismissed so tapping the field re-triggers it
    LaunchedEffect(controller.isVisible) {
        if (usesVirtualKeyboard && !controller.isVisible && isFocused) {
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(value) {
        controller.updateValue(fieldId, value)
    }

    DisposableEffect(fieldId) {
        onDispose { controller.unregisterField(fieldId) }
    }

    val isActive = controller.isActiveField(fieldId)

    // A real input needs its own cursor/selection. Rebuilding TextFieldValue(value) on every
    // recomposition resets the caret to position 0, so typed text appeared in front of the cursor.
    // The caret is only moved (to the end) when the text was changed from outside, e.g. cleared.
    var inputValue by remember { mutableStateOf(TextFieldValue(value)) }
    val shownInput =
        if (inputValue.text == value) inputValue else TextFieldValue(value, TextRange(value.length))

    OutlinedTextField(
        value =
            if (isActive) {
                controller.textFieldValue
            } else if (usesVirtualKeyboard) {
                TextFieldValue(value)
            } else {
                shownInput
            },
        onValueChange = {
            if (isActive) {
                controller.onTextFieldValueChange(it)
            } else if (!usesVirtualKeyboard) {
                inputValue = it
                if (it.text != value) onValueChange(it.text)
            }
        },
        modifier =
            modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onPreviewKeyEvent { event ->
                    // Browser till: the field itself takes the typing, so Escape clears it here.
                    val clears = !usesVirtualKeyboard && event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (clears) onClear()
                    clears
                },
        interactionSource = interactionSource,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                // raw Material 3 by design: trailingIcon slot of OutlinedTextField requires IconButton directly
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onEnter?.invoke() }),
        // Suppress Android system IME — the custom virtual keyboard drives input on this screen.
        // The browser till has no virtual keyboard, so there the field is a real input.
        readOnly = usesVirtualKeyboard,
    )
}
