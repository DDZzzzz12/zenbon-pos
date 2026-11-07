package org.example.project.admin.core.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact

/**
 * A screen's headline. Composes nothing on a compact (phone) window, where the app's top bar already
 * shows the destination title (adminApp CLAUDE.md rule 9; UI-C-17 / SYS-16): repeating it cost about
 * 80dp of an already short screen. The window class decides, because it is what decides whether that
 * top bar exists.
 */
@Composable
fun AdminPageTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (LocalWindowSizeClass.current.isCompact()) return
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}
