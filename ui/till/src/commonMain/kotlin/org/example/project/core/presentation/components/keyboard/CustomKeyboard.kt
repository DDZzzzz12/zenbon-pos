package org.example.project.core.presentation.components.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.KeyboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.ui.theme.PosSpacing

@Composable
fun AlphabeticKeyboardLayout(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
    windowSizeClass: WindowSizeClass,
) {
    val spacing = if (windowSizeClass.isCompact()) PosSpacing.xs else PosSpacing.md
    var isSymbolMode by remember { mutableStateOf(false) }
    var isUpperCase by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        if (isSymbolMode) {
            SymbolLayout(
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onEnter = onEnter,
                onDismiss = onDismiss,
                onToggleSymbol = { isSymbolMode = false },
                spacing = spacing,
            )
        } else {
            LetterLayout(
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onEnter = onEnter,
                onDismiss = onDismiss,
                onToggleSymbol = { isSymbolMode = true },
                isUpperCase = isUpperCase,
                onToggleCase = { isUpperCase = !isUpperCase },
                spacing = spacing,
            )
        }
    }
}

@Composable
private fun LetterLayout(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
    onToggleSymbol: () -> Unit,
    isUpperCase: Boolean,
    onToggleCase: () -> Unit,
    spacing: androidx.compose.ui.unit.Dp,
) {
    val row1 = listOf("Q", "W", "E", "R", "T", "Z", "U", "I", "O", "P")
    val row2 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
    val row3 = listOf("Y", "X", "C", "V", "B", "N", "M")
    val longPressMap = mapOf("U" to "Ü", "O" to "Ö", "A" to "Ä", "S" to "ß")

    fun applyCase(key: String) = if (isUpperCase) key else key.lowercase()

    fun applyCaseUmlaut(key: String) = if (key == "ß") "ß" else applyCase(key)

    // Row 1: Number row + Backspace
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { key ->
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        }
        ActionKeyButton(
            icon = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = str(AccessibilityKeys.backspace),
            onClick = onBackspace,
            modifier = Modifier.weight(1.5f),
        )
    }

    // Row 2: Q W E R T Z U I O P — staggered right ~0.25 key
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Spacer(Modifier.weight(0.25f))
        row1.forEach { upperKey ->
            val key = applyCase(upperKey)
            val longPress = longPressMap[upperKey]?.let { applyCaseUmlaut(it) }
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f), longPressKey = longPress)
        }
        Spacer(Modifier.weight(0.25f))
    }

    // Row 3: A S D F G H J K L — staggered right ~0.5 key
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Spacer(Modifier.weight(0.5f))
        row2.forEach { upperKey ->
            val key = applyCase(upperKey)
            val longPress = longPressMap[upperKey]?.let { applyCaseUmlaut(it) }
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f), longPressKey = longPress)
        }
        Spacer(Modifier.weight(0.5f))
    }

    // Row 4: Shift + Y X C V B N M . *
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        ActionKeyButton(
            icon = Icons.Filled.KeyboardCapslock,
            contentDescription = str(AccessibilityKeys.shift),
            onClick = onToggleCase,
            modifier = Modifier.weight(1.5f),
            isActive = isUpperCase,
        )
        row3.forEach { upperKey ->
            KeyButton(key = applyCase(upperKey), onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        }
        KeyButton(key = ".", onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        // raw Material 3 by design: heightIn(min=44dp) flexible sizing conflicts with PosTonalButton fixed height param
        FilledTonalButton(
            onClick = { onKeyPress("*") },
            modifier = Modifier.weight(1.5f).heightIn(min = 44.dp),
            shape = MaterialTheme.shapes.extraSmall,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                text = "*",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }

    // Row 5: Hide + Symbol toggle + Space + Enter
    BottomRow(
        onKeyPress = onKeyPress,
        onDismiss = onDismiss,
        onEnter = onEnter,
        toggleLabel = str(KeyboardKeys.symbolMode),
        onToggle = onToggleSymbol,
        spacing = spacing,
    )
}

@Composable
private fun SymbolLayout(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
    onToggleSymbol: () -> Unit,
    spacing: androidx.compose.ui.unit.Dp,
) {
    val symbolRow1 = listOf("!", "@", "#", "$", "%", "^", "&", "(", ")", "=")
    val symbolRow2 = listOf("+", "-", "_", "~", "`", "|", "\\", "{", "}")
    val symbolRow3 = listOf("\"", "'", ";", ":", "/", "?", "<", ">")

    // Row 1: Number row + Backspace
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { key ->
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        }
        ActionKeyButton(
            icon = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = str(AccessibilityKeys.backspace),
            onClick = onBackspace,
            modifier = Modifier.weight(1.5f),
        )
    }

    // Row 2: Symbols — staggered
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Spacer(Modifier.weight(0.25f))
        symbolRow1.forEach { key ->
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(0.25f))
    }

    // Row 3: Symbols
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Spacer(Modifier.weight(0.5f))
        symbolRow2.forEach { key ->
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(0.5f))
    }

    // Row 4: Symbols
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Spacer(Modifier.weight(1.5f))
        symbolRow3.forEach { key ->
            KeyButton(key = key, onKeyPress = onKeyPress, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1.5f))
    }

    // Row 5: Hide + ABC toggle + Space + Enter
    BottomRow(
        onKeyPress = onKeyPress,
        onDismiss = onDismiss,
        onEnter = onEnter,
        toggleLabel = str(KeyboardKeys.letterMode),
        onToggle = onToggleSymbol,
        spacing = spacing,
    )
}

/**
 * Bottom row shared between letter and symbol layouts:
 * [Hide] [Toggle] [          Space          ] [Enter]
 */
@Composable
private fun BottomRow(
    onKeyPress: (String) -> Unit,
    onDismiss: () -> Unit,
    onEnter: () -> Unit,
    toggleLabel: String,
    onToggle: () -> Unit,
    spacing: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        // Hide keyboard
        ActionKeyButton(
            icon = Icons.Filled.KeyboardHide,
            contentDescription = str(AccessibilityKeys.dismiss),
            onClick = onDismiss,
            modifier = Modifier.weight(1.5f),
        )

        // Symbol/letter toggle
        // raw Material 3 by design: heightIn(min=44dp) flexible sizing conflicts with PosTonalButton fixed height param
        FilledTonalButton(
            onClick = onToggle,
            modifier = Modifier.weight(1.5f).heightIn(min = 44.dp),
            shape = MaterialTheme.shapes.extraSmall,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                text = toggleLabel,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }

        // Space bar
        KeyButton(
            key = " ",
            displayText = str(KeyboardKeys.space),
            onKeyPress = onKeyPress,
            modifier = Modifier.weight(6f),
        )

        // Enter
        ActionKeyButton(
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            contentDescription = str(AccessibilityKeys.enter),
            onClick = onEnter,
            modifier = Modifier.weight(1.5f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(
    key: String,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    displayText: String = key,
    longPressKey: String? = null,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = 44.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = { onKeyPress(key) },
                    onLongClick = longPressKey?.let { { onKeyPress(it) } },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (longPressKey != null) {
            Text(
                text = longPressKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = PosSpacing.xs, top = 1.dp),
            )
        }
    }
}

@Composable
private fun ActionKeyButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
) {
    // raw Material 3 by design: heightIn(min=44dp) flexible sizing conflicts with PosTonalButton fixed height param
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = MaterialTheme.shapes.extraSmall,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor =
                    if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint =
                if (isActive) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
        )
    }
}
