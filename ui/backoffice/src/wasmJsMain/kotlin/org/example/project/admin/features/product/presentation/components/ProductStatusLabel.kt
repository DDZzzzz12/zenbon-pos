package org.example.project.admin.features.product.presentation.components

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
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/** Zenbon status: a dot and the word, never colour alone. */
@Composable
fun ProductStatusLabel(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val dot = if (active) LocalPosColors.current.posAvailable else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(
            text = str(if (active) AdminStringKeys.ProductStatusActive else AdminStringKeys.ProductStatusInactive),
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}
