package org.example.project.core.utils

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

enum class VirtualKeyboardType { ALPHABETIC, NUMERIC }

class VirtualKeyboardController {
    var hasPhysicalKeyboard by mutableStateOf(true)

    /** When true, the keyboard shows regardless of [hasPhysicalKeyboard]. */
    var forceVisible by mutableStateOf(false)

    val isVisible: Boolean
        get() = (forceVisible || !hasPhysicalKeyboard) && _activeFieldId != null

    var keyboardType by mutableStateOf(VirtualKeyboardType.ALPHABETIC)
        private set

    /** Measured keyboard height in pixels, updated by the overlay via onSizeChanged. */
    var keyboardHeightPx by mutableStateOf(0)

    private var _activeFieldId by mutableStateOf<String?>(null)
    var textFieldValue by mutableStateOf(TextFieldValue(""))
        private set
    private var _onValueChange: ((String) -> Unit)? = null
    private var _onEnter: (() -> Unit)? = null

    fun isActiveField(fieldId: String): Boolean = _activeFieldId == fieldId

    fun registerField(
        fieldId: String,
        value: String,
        onValueChange: (String) -> Unit,
        type: VirtualKeyboardType,
        onEnter: (() -> Unit)? = null,
    ) {
        _activeFieldId = fieldId
        textFieldValue = TextFieldValue(value, TextRange(value.length))
        _onValueChange = onValueChange
        _onEnter = onEnter
        keyboardType = type
    }

    fun unregisterField(fieldId: String) {
        if (_activeFieldId == fieldId) {
            _activeFieldId = null
            _onValueChange = null
        }
    }

    fun updateValue(
        fieldId: String,
        newValue: String,
    ) {
        if (_activeFieldId == fieldId && textFieldValue.text != newValue) {
            val cursor = textFieldValue.selection.start.coerceAtMost(newValue.length)
            textFieldValue = TextFieldValue(newValue, TextRange(cursor))
        }
    }

    /** Called by the TextField when the user interacts directly (tap to move cursor, select, physical keyboard). */
    fun onTextFieldValueChange(newValue: TextFieldValue) {
        textFieldValue = newValue
        _onValueChange?.invoke(newValue.text)
    }

    fun onKeyPress(key: String) {
        val text = textFieldValue.text
        val selection = textFieldValue.selection
        val newText = text.substring(0, selection.min) + key + text.substring(selection.max)
        val newCursor = selection.min + key.length
        textFieldValue = TextFieldValue(newText, TextRange(newCursor))
        _onValueChange?.invoke(newText)
    }

    fun onBackspace() {
        val text = textFieldValue.text
        val selection = textFieldValue.selection
        if (selection.length > 0) {
            val newText = text.substring(0, selection.min) + text.substring(selection.max)
            textFieldValue = TextFieldValue(newText, TextRange(selection.min))
            _onValueChange?.invoke(newText)
        } else if (selection.start > 0) {
            val newText = text.substring(0, selection.start - 1) + text.substring(selection.start)
            textFieldValue = TextFieldValue(newText, TextRange(selection.start - 1))
            _onValueChange?.invoke(newText)
        }
    }

    fun onClear() {
        textFieldValue = TextFieldValue("")
        _onValueChange?.invoke("")
    }

    fun onEnter() {
        _onEnter?.invoke()
    }

    fun dismiss() {
        _activeFieldId = null
        _onValueChange = null
        _onEnter = null
    }
}

val LocalVirtualKeyboard =
    compositionLocalOf<VirtualKeyboardController> {
        error("VirtualKeyboardController not provided. Make sure to provide it at the root level using CompositionLocalProvider.")
    }

/**
 * Current virtual keyboard height as Dp. Provided at root level via CompositionLocalProvider.
 * Returns 0.dp when keyboard is hidden or on non-desktop platforms.
 */
val LocalVirtualKeyboardPadding = compositionLocalOf { 0.dp }

/**
 * Modifier that adds bottom padding equal to the current virtual keyboard height.
 * Use on scrollable content containers (Column with verticalScroll, LazyColumn, etc.)
 * to ensure focused text fields aren't hidden behind the virtual keyboard.
 */
fun Modifier.keyboardAwarePadding(): Modifier =
    composed {
        val keyboardPadding = LocalVirtualKeyboardPadding.current
        padding(bottom = keyboardPadding)
    }
