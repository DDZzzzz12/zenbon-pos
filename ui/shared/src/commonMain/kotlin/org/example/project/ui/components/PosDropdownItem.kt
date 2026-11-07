package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.utils.touchTargetSize

/**
 * A plain selectable row for [PosDropdown] — the stand-in for Material's `DropdownMenuItem`.
 *
 * [PosDropdown] deliberately has no item component of its own so that a menu opened from a list can
 * reuse that list's rows. This is for the ordinary case where there is no such row to reuse: a
 * choice list, a filter, a picker.
 *
 * [selected] marks the current choice with a check rather than only a tint, so the state survives
 * for anyone who cannot separate the two colours. [leadingIcon] is for action menus, where the icon
 * is the item's identity rather than decoration; a selectable list normally leaves it null.
 * [color] overrides the content colour for a destructive item (log out, delete); leave it null
 * otherwise so the item keeps tracking [enabled] and [selected]. [supportingText] adds a second
 * line for a choice whose consequence is not obvious from its name.
 */
@Composable
fun PosDropdownItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    color: Color? = null,
    supportingText: String? = null,
    /** Overrides the label's font, e.g. the CJK family for a Chinese name under a Latin theme. */
    fontFamily: FontFamily? = null,
    /** Row's minimum height: 40dp on an expanded window, 48dp on any touch-sized window (see [touchTargetSize]). */
    minHeight: Dp = touchTargetSize(40.dp),
) {
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            color != null -> color
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                ).clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = text,
                style =
                    MaterialTheme.typography.bodyMedium.let {
                        if (fontFamily != null) it.copy(fontFamily = fontFamily) else it
                    },
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
