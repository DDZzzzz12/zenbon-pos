package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.str
import org.example.project.ui.navigation.PosNavDestination
import org.example.project.ui.theme.PosSpacing

@Composable
fun PosMgmtGrid(
    items: List<PosNavDestination>,
    onItemClick: (PosNavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { dest ->
            PosTileButton(
                label = str(dest.labelKey),
                onClick = { onItemClick(dest) },
                modifier = Modifier.fillMaxWidth(),
                icon = dest.icon,
                iconSize = 26.dp,
                iconLabelSpacing = 8.dp,
                contentPadding = PaddingValues(horizontal = PosSpacing.md, vertical = 18.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                textMaxLines = 1,
                iconTint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
