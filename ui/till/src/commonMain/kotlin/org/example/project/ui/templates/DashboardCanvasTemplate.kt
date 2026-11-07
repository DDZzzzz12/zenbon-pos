package org.example.project.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact

@Composable
fun DashboardCanvasTemplate(
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    val windowSize = LocalWindowSizeClass.current
    val columns =
        when {
            windowSize.isCompact() -> 2
            windowSize.isAtLeastExpanded() -> 4
            else -> 3
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
