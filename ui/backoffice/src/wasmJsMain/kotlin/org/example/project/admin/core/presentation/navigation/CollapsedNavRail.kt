package org.example.project.admin.core.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownAnchor
import org.example.project.ui.components.navigation.PosSidebarNavItem
import org.example.project.ui.theme.PosSpacing

/**
 * The sidebar at rail width: one icon per GROUP, not one per destination.
 *
 * The rail used to render all 25 destinations as unlabelled glyphs separated by six hairlines,
 * which told you neither what an icon was nor which group it belonged to. Showing the seven group
 * icons instead makes it scannable, and the flyout supplies the labels the rail cannot — so the
 * trade is one extra click for names that were previously unavailable at any width.
 *
 * [AdminNavGroup.PINNED] has no group icon and its destinations stay direct: Übersicht is one click
 * here exactly as it is in the expanded drawer.
 *
 * Einstellungen is deliberately **flattened** into the SYSTEM flyout rather than opening a second
 * popup from inside the first. It had its own rail flyout before this, which under a group-level
 * rail would have become a menu nested in a menu.
 */
@Composable
fun CollapsedNavRail(
    grouped: Map<AdminNavGroup, List<AdminNavItem>>,
    selectedItem: AdminNavItem?,
    activeGroup: AdminNavGroup?,
    currentSection: String?,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    // At most one flyout is open. Keyed by group so opening another closes the first.
    var openGroup by remember { mutableStateOf<AdminNavGroup?>(null) }

    Column(modifier = modifier) {
        grouped.forEach { (group, items) ->
            if (group == AdminNavGroup.PINNED) {
                items.forEach { item ->
                    PosSidebarNavItem(
                        icon = item.icon,
                        label = str(item.labelKey),
                        isSelected = item == selectedItem,
                        onClick = { onNavigate(item.route) },
                        showLabel = false,
                    )
                }
                return@forEach
            }

            val icon = group.icon ?: return@forEach
            Box {
                PosSidebarNavItem(
                    icon = icon,
                    label = str(group.labelKey ?: return@Box),
                    // The group holding the current destination, since no single icon here is the
                    // destination any more.
                    isSelected = group == activeGroup,
                    onClick = { openGroup = if (openGroup == group) null else group },
                    showLabel = false,
                )
                PosDropdown(
                    expanded = openGroup == group,
                    // Idempotent by construction — never a toggle. Clicking the icon to close fires
                    // dismissal AND the icon's own click in one gesture, so a toggle here would
                    // reopen the panel and the rail would look stuck open.
                    onDismissRequest = { openGroup = null },
                    anchor = PosDropdownAnchor.EndTop,
                    // Roughly the expanded sidebar's width, so opening a group reads as that
                    // sidebar appearing beside the rail rather than as a panel of its own size.
                    minWidth = 240.dp,
                    maxWidth = 240.dp,
                ) {
                    GroupFlyoutContent(
                        group = group,
                        items = items,
                        selectedItem = selectedItem,
                        currentSection = currentSection,
                        onNavigate = {
                            openGroup = null
                            onNavigate(it)
                        },
                    )
                }
            }
        }
    }
}

/** The group's name, then its destinations as the same rows the expanded drawer uses. */
@Composable
private fun GroupFlyoutContent(
    group: AdminNavGroup,
    items: List<AdminNavItem>,
    selectedItem: AdminNavItem?,
    currentSection: String?,
    onNavigate: (Any) -> Unit,
) {
    val rowMinHeight = adminTouchSize(0.dp)
    group.labelKey?.let { labelKey ->
        Text(
            text = str(labelKey),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = PosSpacing.sm),
        )
    }
    items.forEach { item ->
        PosSidebarNavItem(
            icon = item.icon,
            label = str(item.labelKey),
            isSelected = item == selectedItem,
            onClick = { onNavigate(item.route) },
            modifier = Modifier.heightIn(min = rowMinHeight),
        )
        if (item == AdminNavItem.SETTINGS) {
            SettingsSection.visibleTo(LocalAccessPolicy.current).forEach { section ->
                PosSidebarNavItem(
                    icon = null,
                    label = str(section.labelKey),
                    isSelected = currentSection == section.name,
                    onClick = { onNavigate(SettingsRoute(section.name)) },
                    indent = 32.dp,
                    modifier = Modifier.heightIn(min = rowMinHeight),
                )
            }
        }
    }
}
