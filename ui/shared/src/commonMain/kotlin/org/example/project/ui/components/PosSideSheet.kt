package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.layout.PaneBreakpoints

/**
 * Modal side sheet: a scrim over the whole area (tap = [onDismiss] unless [dismissOnScrimClick] is false) and a fixed-width panel on the end
 * edge with a title, a x close, a self-scrolling body (callers must never nest verticalScroll) and, when
 * [primary] is given, the one [PosDialogFooter] (default secondary = Cancel -> [onDismiss]).
 * On a phone the sheet fills the width. [footerStart] adds a control (e.g. Löschen) at the start of the footer.
 */
@Composable
fun PosSideSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = PosSideSheetWidth,
    primary: PosDialogAction? = null,
    secondary: PosDialogAction? = null,
    destructive: Boolean = false,
    dismissOnScrimClick: Boolean = true,
    headerExtras: (@Composable RowScope.() -> Unit)? = null,
    footerStart: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val windowWidth =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val phone = windowWidth < PaneBreakpoints.PHONE_MAX
        val sheetWidth = if (phone) maxWidth else minOf(width, maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (dismissOnScrimClick) onDismiss() },
                ),
        )
        Surface(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(sheetWidth)
                    .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                SideSheetHeader(title, windowWidth, onDismiss, headerExtras)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
                if (primary != null) {
                    val cancel = secondary ?: PosDialogAction(str(CommonKeys.cancel), onDismiss, enabled = !primary.isLoading)
                    if (footerStart != null) {
                        PosSideSheetStartFooter(footerStart, primary, cancel, destructive, phone, 16.dp)
                    } else {
                        PosDialogFooter(primary, cancel, destructive, 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SideSheetHeader(
    title: String,
    windowWidth: Dp,
    onDismiss: () -> Unit,
    headerExtras: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        headerExtras?.invoke(this)
        // raw Material 3 by design: this IS a Pos* template
        IconButton(onClick = onDismiss, modifier = Modifier.size(posDialogCloseTargetSize(windowWidth))) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = str(CommonKeys.close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
