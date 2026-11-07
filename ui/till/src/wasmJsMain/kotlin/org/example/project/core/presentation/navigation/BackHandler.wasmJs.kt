package org.example.project.core.presentation.navigation

import androidx.compose.runtime.Composable

/**
 * No-op. The app's back stack is not the browser's history, and wiring the two together would let a
 * browser Back button leave a half-finished order in a state the till never expects.
 */
@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // Intentionally empty — see the KDoc above.
}
