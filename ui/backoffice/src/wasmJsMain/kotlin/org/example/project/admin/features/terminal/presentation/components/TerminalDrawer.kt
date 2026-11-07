package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DRAWER_WIDTH = 400.dp

/**
 * The medium layout's detail: a 400dp panel on the end edge over a scrim (a tap on the scrim is
 * [onDismiss]). The content brings its own header with the ×, so there is no second title bar; it also
 * scrolls itself (the terminal panel), so callers must not nest a `verticalScroll`.
 */
@Composable
fun TerminalDrawer(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).width(minOf(DRAWER_WIDTH, maxWidth)).fillMaxHeight(),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 8.dp,
        ) { content() }
    }
}
