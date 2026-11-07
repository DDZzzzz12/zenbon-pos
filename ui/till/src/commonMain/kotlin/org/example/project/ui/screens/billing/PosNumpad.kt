package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Clear
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
 * POS-styled numpad matching the existing Numpad layout:
 * [1] [2] [3] | [⌫]
 * [4] [5] [6] | [C]
 * [7] [8] [9] |
 * [.] [0] [,] |
 */
@Composable
fun PosNumpad(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onEnter: (() -> Unit)? = null,
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
            listOf(".", "0", ","),
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
                        // raw Material 3 by design: fillMaxSize() modifier conflicts with PosTonalButton fixed height param
                        FilledTonalButton(
                            onClick = { onKey(key) },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            shape = MaterialTheme.shapes.small,
                            colors =
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        // Right action column (Backspace + Clear)
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

            // Clear
            // raw Material 3 by design: fillMaxWidth().weight() flexible sizing conflicts with PosTonalButton fixed height param
            FilledTonalButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.small,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.Clear, contentDescription = str(AccessibilityKeys.clear))
            }

            // Enter/Confirm (takes remaining 2 rows of height)
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
            }
        }
    }
}
