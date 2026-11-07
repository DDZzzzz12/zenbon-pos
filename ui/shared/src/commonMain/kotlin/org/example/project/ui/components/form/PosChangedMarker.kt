package org.example.project.ui.components.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/**
 * "● Geändert" — shown in a drawer's or page's header while the draft differs from the saved record
 * ([PosFormState.isDirty]). Amber dot (`pos-occupied`, round by nature) and amber text; the text
 * colour is the darker amber in light theme so it meets 4.5:1.
 */
@Composable
fun PosChangedMarker(modifier: Modifier = Modifier) {
    val amber = LocalPosColors.current.posOccupied
    // tertiary is the amber text tone in both Zenbon schemes (#B45309 light, #FCD34D dark).
    val textColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(amber, CircleShape))
        Text(text = str(PosPatternKeys.formChanged), style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}
