package org.example.project.ui.templates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact

@Composable
fun SinglePaneTemplate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val windowSize = LocalWindowSizeClass.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val contentModifier =
            when {
                windowSize.isCompact() -> Modifier
                else -> Modifier.widthIn(max = 600.dp)
            }
        Box(modifier = contentModifier) {
            content()
        }
    }
}
