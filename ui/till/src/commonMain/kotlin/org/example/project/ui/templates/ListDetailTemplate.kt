package org.example.project.ui.templates

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact

@Composable
fun ListDetailTemplate(
    hasSelection: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    emptyDetail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listWidth: Int = 400,
) {
    val windowSize = LocalWindowSizeClass.current
    when {
        windowSize.isCompact() -> {
            // Single-column flow on compact sizes
            AnimatedContent(
                targetState = hasSelection,
                modifier = modifier.fillMaxSize(),
            ) { showDetail ->
                if (showDetail) {
                    detail()
                } else {
                    list()
                }
            }
        }
        windowSize.isAtLeastExpanded() -> {
            // Two-pane layout on medium/expanded sizes
            Row(modifier = modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .width(listWidth.dp)
                            .fillMaxHeight(),
                ) {
                    list()
                }
                VerticalDivider()
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    if (hasSelection) {
                        detail()
                    } else {
                        emptyDetail()
                    }
                }
            }
        }
        else -> {
            // Fallback to compact-like flow
            AnimatedContent(
                targetState = hasSelection,
                modifier = modifier.fillMaxSize(),
            ) { showDetail ->
                if (showDetail) {
                    detail()
                } else {
                    list()
                }
            }
        }
    }
}
