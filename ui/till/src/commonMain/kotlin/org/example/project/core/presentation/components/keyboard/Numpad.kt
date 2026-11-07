package org.example.project.core.presentation.components.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.releaseInstantTapMode
import org.example.project.core.utils.requestInstantTapMode

/**
 * Standalone numpad grid for numeric input.
 *
 * Layout:
 * [1] [2] [3] | [⌫]
 * [4] [5] [6] | [Enter]
 * [7] [8] [9] | [Enter]
 * [.] [0] [ ] | [⌨↓]
 */
@Composable
fun Numpad(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onEnter: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
) {
    // Enable instant tap mode while numpad is visible (bypasses touch pending delay)
    DisposableEffect(Unit) {
        requestInstantTapMode()
        onDispose { releaseInstantTapMode() }
    }

    val rows =
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", ""),
        )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Number grid (3 columns, 4 rows)
        Column(
            modifier = Modifier.weight(3f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { key ->
                        if (key.isEmpty()) {
                            // Empty spacer where hide key used to be
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            NumpadKey(
                                key = key,
                                onClick = { onKeyPress(key) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // Right column: Backspace, Enter (2 rows), Hide
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Backspace
            // raw Material 3 by design: fillMaxWidth().weight() flexible sizing conflicts with PosTonalButton fixed height param
            FilledTonalButton(
                onClick = onBackspace,
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.small,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = str(AccessibilityKeys.backspace))
            }

            // Enter (spans 2 rows)
            if (onEnter != null) {
                // raw Material 3 by design: fillMaxWidth().weight() flexible sizing conflicts with PosTonalButton fixed height param
                FilledTonalButton(
                    onClick = onEnter,
                    modifier = Modifier.fillMaxWidth().weight(2f),
                    shape = MaterialTheme.shapes.small,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = str(AccessibilityKeys.enter))
                }
            } else {
                Spacer(modifier = Modifier.weight(2f))
            }

            // Hide keyboard
            if (onHide != null) {
                // raw Material 3 by design: fillMaxWidth().weight() flexible sizing conflicts with PosTonalButton fixed height param
                FilledTonalButton(
                    onClick = onHide,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Filled.KeyboardHide, contentDescription = str(AccessibilityKeys.dismiss))
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumpadKey(
    key: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // raw Material 3 by design: fillMaxSize() modifier conflicts with PosTonalButton fixed height param
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.small,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
