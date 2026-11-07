package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.PosSpacing

/**
 * Reusable POS dialog template matching the new design language.
 *
 * Provides consistent dialog structure: header with icon + title + close button,
 * scrollable body, and footer for action buttons. Used for settings, forms,
 * confirmations, and notifications throughout the app.
 *
 * Footer contract: pass [primary] (and optionally [secondary]; the default is a Cancel that calls
 * `onDismiss`) and the dialog draws the one standard footer: Cancel at the start, the primary action at
 * the end, stacked full-width when the labels do not fit side by side. [destructive] draws the primary
 * in the error colour. [showSecondary] = false is for purely informational dialogs whose primary is Close. The legacy [footer] slot is used only when [primary] is null and keeps its
 * SpaceBetween row. [titleExtras] sits right after the title (a "Geändert" marker); [startAction] adds a red
 * start-edge action (an edit dialog's Löschen) and moves Cancel beside the primary. [kind] decides the phone presentation in Auto mode: Form is full-screen, Confirm
 * stays a compact card.
 *
 * On a phone (window below 600dp) a form-like dialog (see [PosDialogPhoneMode]) fills the screen
 * with a top bar, a scrolling body and a sticky footer; the caller's `modifier` then applies to the
 * full-screen surface (`fillMaxSize` is chained after it). [phoneMode] null means
 * [LocalPosDialogPhoneDefault]: `Compact` unless the app's theme provides `Auto` (adminApp does,
 * the till does not).
 */
@Composable
fun PosDialog(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    titleIcon: ImageVector? = null,
    maxWidth: Dp = PosDialogDefaultMaxWidth,
    dismissOnClickOutside: Boolean = true,
    showCloseButton: Boolean = true,
    phoneMode: PosDialogPhoneMode? = null,
    kind: PosDialogKind? = null,
    primary: PosDialogAction? = null,
    secondary: PosDialogAction? = null,
    destructive: Boolean = false,
    showSecondary: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    titleExtras: (@Composable () -> Unit)? = null,
    startAction: PosDialogAction? = null,
    startActionIcon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    val windowWidth =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    val mode = phoneMode ?: LocalPosDialogPhoneDefault.current
    val fullScreen =
        posDialogUsesFullScreen(windowWidth, mode, hasFooter = primary != null || footer != null, maxWidth = maxWidth, kind = kind)
    val cancel = if (showSecondary) secondary ?: PosDialogAction(str(CommonKeys.cancel), onDismiss, enabled = primary?.isLoading != true) else null
    val actionFooter: (@Composable (Dp) -> Unit)? =
        primary?.let { action -> { padding -> PosDialogFooter(action, cancel, destructive, padding, start = startAction, startIcon = startActionIcon) } }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = dismissOnClickOutside && !fullScreen,
                usePlatformDefaultWidth = false,
            ),
    ) {
        if (fullScreen) {
            FullScreenPosDialogBody(modifier, onDismiss, title, titleIcon, showCloseButton, actionFooter, footer, titleExtras, content)
        } else {
            CompactPosDialogBody(modifier, maxWidth, windowWidth, title, titleIcon, showCloseButton, onDismiss, actionFooter, footer, titleExtras, content)
        }
    }
}

@Composable
private fun CompactPosDialogBody(
    modifier: Modifier,
    maxWidth: Dp,
    windowWidth: Dp,
    title: String,
    titleIcon: ImageVector?,
    showCloseButton: Boolean,
    onDismiss: () -> Unit,
    actionFooter: (@Composable (Dp) -> Unit)?,
    footer: (@Composable RowScope.() -> Unit)?,
    titleExtras: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .widthIn(max = maxWidth)
                .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 8.dp,
    ) {
        Column {
            // Header (52dp height to match mockup)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            ) {
                if (titleIcon != null) {
                    Icon(
                        imageVector = titleIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (titleExtras == null) Modifier.weight(1f) else Modifier.weight(1f, fill = false),
                )
                if (titleExtras != null) {
                    titleExtras()
                    Spacer(Modifier.weight(1f))
                }
                if (showCloseButton) {
                    // raw Material 3 by design: this IS a Pos* template
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(posDialogCloseTargetSize(windowWidth)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = str(CommonKeys.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Scrollable body
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
            ) {
                content()
            }

            // Footer
            if (actionFooter != null) {
                actionFooter(20.dp)
            } else if (footer != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    footer()
                }
            }
        }
    }
}

@Composable
private fun FullScreenPosDialogBody(
    modifier: Modifier,
    onDismiss: () -> Unit,
    title: String,
    titleIcon: ImageVector?,
    showCloseButton: Boolean,
    actionFooter: (@Composable (Dp) -> Unit)?,
    footer: (@Composable RowScope.() -> Unit)?,
    titleExtras: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCloseButton) {
                    // raw Material 3 by design: this IS a Pos* template
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(CommonKeys.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                if (titleIcon != null) {
                    Icon(
                        imageVector = titleIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (titleExtras == null) Modifier.weight(1f) else Modifier.weight(1f, fill = false),
                )
                if (titleExtras != null) {
                    Spacer(Modifier.width(8.dp))
                    titleExtras()
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(12.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Self-scrolling body: callers must never nest verticalScroll (same rule as the compact body).
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
            ) { content() }
            if (actionFooter != null) {
                actionFooter(16.dp)
            } else if (footer != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    content = footer,
                )
            }
        }
    }
}
