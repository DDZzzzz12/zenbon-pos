package org.example.project.ui.templates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isAtLeastExpanded
import org.example.project.core.utils.isCompact

@Composable
fun FormPanelTemplate(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    mainContent: @Composable () -> Unit,
    panelContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    panelWidth: Int = 420,
) {
    val windowSize = LocalWindowSizeClass.current
    when {
        windowSize.isCompact() -> {
            Box(modifier = modifier.fillMaxSize()) {
                if (isOpen) {
                    panelContent()
                } else {
                    mainContent()
                }
            }
        }
        windowSize.isAtLeastExpanded() -> {
            Box(modifier = modifier.fillMaxSize()) {
                mainContent()

                if (isOpen) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onDismiss,
                                ),
                    )
                }

                AnimatedVisibility(
                    visible = isOpen,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(panelWidth.dp)
                                .fillMaxHeight(),
                    ) {
                        panelContent()
                    }
                }
            }
        }
        else -> {
            // Fallback to compact-like behavior
            Box(modifier = modifier.fillMaxSize()) {
                if (isOpen) {
                    panelContent()
                } else {
                    mainContent()
                }
            }
        }
    }
}
