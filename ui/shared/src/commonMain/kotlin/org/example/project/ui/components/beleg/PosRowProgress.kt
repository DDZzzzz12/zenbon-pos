package org.example.project.ui.components.beleg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The per-row progress of the Belegfluss: [total] small squares (Erfassen, Prüfen, Zuordnen,
 * Erledigt), the first [steps] filled.
 *
 * [description] is the localized "Schritt n von 4" read out by assistive technology; the squares
 * themselves say nothing.
 */
@Composable
fun PosRowProgress(
    steps: Int,
    description: String,
    modifier: Modifier = Modifier,
    total: Int = BELEG_PROGRESS_STEPS,
) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Image
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(total) { index ->
            Box(Modifier.size(10.dp).background(if (index < steps) filled else empty))
        }
    }
}

/** Number of squares in a row's progress: Erfassen, Prüfen, Zuordnen, Erledigt. */
const val BELEG_PROGRESS_STEPS = 4
