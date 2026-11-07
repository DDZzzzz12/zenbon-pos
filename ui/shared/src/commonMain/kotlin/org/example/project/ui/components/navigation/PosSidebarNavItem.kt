package org.example.project.ui.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One row of a navigation sidebar: optional leading icon, label, selected highlight, optional
 * badge and trailing slot.
 *
 * Unselected rows are transparent rather than tinted so the row sits correctly on whatever surface
 * the host sidebar uses.
 *
 * @param icon leading icon, or null for a tree child that is identified by indent alone.
 * @param label the row's text, and the icon's content description when [showLabel] is false.
 * @param isSelected whether this row is the active destination.
 * @param onClick invoked when the row is tapped.
 * @param modifier applied to the row.
 * @param badgeCount unread count; a badge renders when positive. Suppressed when [showLabel] is
 *   false — a collapsed rail has no room for it.
 * @param isDanger tints the row with the error colour, for destructive actions.
 * @param showLabel false in a collapsed icon-only rail — the row centres its icon and renders
 *   neither the label, the badge, nor [trailing].
 * @param indent left padding for a nested child row.
 * @param trailing optional slot at the row's end, e.g. an expand chevron. Suppressed when
 *   [showLabel] is false; a collapsed host should use an anchored flyout instead.
 */
@Composable
fun PosSidebarNavItem(
    icon: ImageVector?,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    isDanger: Boolean = false,
    showLabel: Boolean = true,
    indent: Dp = 0.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val backgroundColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }
    val contentColor =
        when {
            isDanger -> MaterialTheme.colorScheme.error
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = indent)
                .clip(MaterialTheme.shapes.medium)
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (showLabel) {
                Arrangement.Start
            } else {
                Arrangement.Center
            },
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = if (showLabel) null else label,
                tint = contentColor,
            )
        }
        if (showLabel) {
            if (icon != null) {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badgeCount > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            trailing?.invoke()
        }
    }
}
