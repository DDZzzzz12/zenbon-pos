package org.example.project.admin.core.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.components.verticalScrollFade
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.SessionState
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.navigation.PosSidebarNavItem
import org.koin.compose.koinInject

private val EXPANDED_WIDTH = 240.dp
private val COLLAPSED_WIDTH = 72.dp

/**
 * The admin panel's permanent navigation sidebar. Renders [AdminNavItem.groupedFor], so items the
 * signed-in role cannot reach are absent, and a group whose members are all gated out contributes
 * no header.
 *
 * Stateless by design: [collapsed] and every callback are hoisted, so the caller owns persistence
 * and the window-size default. [onLanguageSelected] is hoisted for the same reason — the sidebar
 * never touches the key-value store itself.
 *
 * @param currentDestination the current back-stack entry's destination, for selected-state matching.
 * @param currentSection the active `SettingsSection` name when on [SettingsRoute], else null.
 */
@Composable
fun AdminSidebar(
    currentDestination: NavDestination?,
    currentSection: String?,
    tokenStore: TokenStore,
    collapsed: Boolean,
    expansion: NavGroupExpansion,
    activeGroup: AdminNavGroup?,
    onToggleCollapse: () -> Unit,
    onNavigate: (Any) -> Unit,
    onLogout: () -> Unit,
    onLanguageSelected: (Language) -> Unit,
    /** Opens "Report a problem"; the caller hosts the dialog outside the compact/wide switch (see `AdminUserMenu`). */
    onReportProblem: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * True when hosted in the phone's `ModalDrawerSheet`: fill the sheet instead of the fixed
     * [EXPANDED_WIDTH], and use 48dp touch targets. The permanent desktop sidebar leaves it false.
     */
    inDrawer: Boolean = false,
) {
    // Memoised because every gate reads the token store, re-parsing its stored capability set, so an
    // unremembered call costs one lookup per destination per recomposition.
    //
    // Keyed on the EPOCH rather than on the store. The store is a singleton, so a memo keyed on it
    // never recomputes — which was fine while only `role` mattered (it changes at login, and
    // `AuthenticatedApp` leaves composition then) and is not fine now: a silent token refresh brings
    // a new capability set and moves no Compose state of its own. Keying on `tokenStore.capabilities`
    // instead would pay for the very store read this memo exists to avoid.
    val epoch = koinInject<SessionState>().capabilityEpoch
    val grouped = remember(epoch) { AdminNavItem.groupedFor(tokenStore) }
    val onSettings = currentDestination?.hasRoute(SettingsRoute::class) == true
    val selectedItem = remember(currentDestination) { AdminNavItem.itemForDestination(currentDestination) }
    val toggleSize = adminTouchSize(32.dp)
    val menuItemMinHeight = adminTouchSize(40.dp)
    val settingsRowMinHeight = adminTouchSize(0.dp)

    // Expanded-mode tree. Follows the destination — arriving on a settings route reveals where you
    // landed — until the user overrides it, and the override lasts only for that destination.
    var treeOverride by remember(onSettings, currentSection) { mutableStateOf<Boolean?>(null) }
    val treeExpanded = treeOverride ?: onSettings

    Surface(
        modifier =
            modifier
                .then(
                    if (inDrawer) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.width(if (collapsed) COLLAPSED_WIDTH else EXPANDED_WIDTH)
                    },
                ).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxHeight().padding(8.dp)) {
            SidebarHeader(
                collapsed = collapsed,
                toggleSize = toggleSize,
                // A modal drawer cannot collapse into a rail; the scrim and a swipe close it.
                onToggleCollapse = if (inDrawer) null else onToggleCollapse,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            val navScroll = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScrollFade(navScroll)
                        .verticalScroll(navScroll),
            ) {
                if (collapsed) {
                    // The rail is a different structure, not the same list narrowed: one icon per
                    // GROUP with a flyout, rather than 25 unlabelled destination glyphs.
                    CollapsedNavRail(
                        grouped = grouped,
                        selectedItem = selectedItem,
                        activeGroup = activeGroup,
                        currentSection = currentSection,
                        onNavigate = onNavigate,
                    )
                    return@Column
                }

                grouped.forEach { (group, items) ->
                    val expanded = expansion.isExpanded(group, activeGroup)
                    val isActive = expansion.isActiveGroup(group, activeGroup)

                    GroupHeader(
                        group = group,
                        expanded = expanded,
                        // The active group is force-expanded, so a toggle on it could only write a
                        // value that changes nothing on screen while destroying a stored "keep this
                        // open". Give it no control rather than a control that lies.
                        onToggle = if (isActive) null else ({ expansion.toggle(group) }),
                    )

                    if (!expanded) return@forEach

                    items.forEach { item ->
                        if (item == AdminNavItem.SETTINGS) {
                            SettingsTreeNode(
                                sections = SettingsSection.visibleTo(LocalAccessPolicy.current),
                                onSettings = onSettings,
                                currentSection = currentSection,
                                expanded = treeExpanded,
                                onToggle = { treeOverride = !treeExpanded },
                                onNavigate = onNavigate,
                                indent = 16.dp,
                                minRowHeight = settingsRowMinHeight,
                            )
                        } else {
                            PosSidebarNavItem(
                                icon = item.icon,
                                label = str(item.labelKey),
                                isSelected = item == selectedItem,
                                onClick = { onNavigate(item.route) },
                                // Nested under their group's header row. PINNED has no header, so
                                // Übersicht stays flush and reads as the top-level row it is.
                                indent = if (group == AdminNavGroup.PINNED) 0.dp else 16.dp,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            AdminUserMenu(
                tokenStore = tokenStore,
                collapsed = collapsed,
                onLogout = onLogout,
                onLanguageSelected = onLanguageSelected,
                onReportProblem = onReportProblem,
                itemMinHeight = menuItemMinHeight,
            )
        }
    }
}

/**
 * Logo mark, app title and the collapse toggle.
 *
 * Collapsed the two controls stack: a 72dp rail minus the sidebar's and the header's own padding
 * leaves 48dp of usable width, which cannot hold a 32dp mark beside a 32dp button in one row.
 */
@Composable
private fun SidebarHeader(
    collapsed: Boolean,
    toggleSize: Dp,
    /** Null hides the toggle: in the phone's modal drawer the "<" collapse chevron meant nothing (H-5). */
    onToggleCollapse: (() -> Unit)?,
) {
    if (collapsed) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LogoMark()
            onToggleCollapse?.let { CollapseToggle(collapsed = true, size = toggleSize, onToggleCollapse = it) }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark()
            Text(
                text = str(AdminStringKeys.NavAppTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            onToggleCollapse?.let { CollapseToggle(collapsed = false, size = toggleSize, onToggleCollapse = it) }
        }
    }
}

@Composable
private fun LogoMark() {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PointOfSale,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CollapseToggle(
    collapsed: Boolean,
    size: Dp,
    onToggleCollapse: () -> Unit,
) {
    PosIconButton(
        icon = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
        contentDescription =
            str(
                if (collapsed) {
                    AdminStringKeys.NavExpandSidebar
                } else {
                    AdminStringKeys.NavCollapseSidebar
                },
            ),
        onClick = onToggleCollapse,
        size = size,
        iconSize = if (size > 32.dp) 24.dp else 18.dp,
    )
}

/**
 * A clickable header row. Only the expanded sidebar has headers — the rail groups by flyout.
 * [AdminNavGroup.PINNED] has no label and contributes neither.
 *
 * In the icon rail there is no room for a label, so groups cannot be toggled there — the rail
 * renders every permitted item, which is the behaviour it already had.
 */
@Composable
private fun GroupHeader(
    group: AdminNavGroup,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    val labelKey = group.labelKey ?: return
    // Built to the same metrics as PosSidebarNavItem — 12dp pad, 24dp icon, 12dp gap — so the
    // header's icon shares the one left edge every destination uses. Headers used to be bare text
    // at a different inset, which left the icons anchoring an edge the labels did not share and
    // made the pinned Übersicht row read as a differently-built control.
    //
    // Not PosSidebarNavItem itself: the ACTIVE group has no toggle, and that component's onClick is
    // non-null, so it would render a ripple on a row that does nothing.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        group.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = str(labelKey),
            style = MaterialTheme.typography.labelLarge,
            // onSurface against the items' onSurfaceVariant: the rows are structurally identical
            // now, so weight is what separates a group from the things inside it.
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // The active group is pinned open and cannot be toggled; the muted chevron keeps every
            // header looking alike without suggesting it is clickable.
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Einstellungen: a parent row plus its [sections] children, as indented rows.
 *
 * Only the expanded sidebar reaches this. At rail width `CollapsedNavRail` flattens the sections
 * into the SYSTEM flyout instead, because a flyout opened from inside a flyout is a nested menu.
 *
 * [onToggle] flips whichever affordance is live, while [onDismiss] only ever closes the flyout —
 * `onDismissRequest` must be idempotent, since a toggle there would reopen the menu on any path
 * that dismisses and re-clicks the anchor in one gesture.
 */
@Composable
private fun SettingsTreeNode(
    sections: List<SettingsSection>,
    onSettings: Boolean,
    currentSection: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNavigate: (Any) -> Unit,
    indent: Dp,
    minRowHeight: Dp,
) {
    PosSidebarNavItem(
        icon = AdminNavItem.SETTINGS.icon,
        label = str(AdminNavItem.SETTINGS.labelKey),
        isSelected = onSettings,
        onClick = onToggle,
        indent = indent,
        trailing = {
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    if (expanded) {
        // Einstellungen is the last item of the last group, so its children render below the fold
        // and expanding it moved nothing on screen — the chevron flipped and the control read as
        // dead. Deliberately NOT animateScrollTo(maxValue): that works only while this node
        // happens to be last, and would break silently the moment anything is declared below it.
        val requester = remember { BringIntoViewRequester() }
        LaunchedEffect(Unit) { requester.bringIntoViewRequested() }

        Column(modifier = Modifier.bringIntoViewRequester(requester)) {
            sections.forEach { section ->
                PosSidebarNavItem(
                    icon = null,
                    label = str(section.labelKey),
                    isSelected = onSettings && currentSection == section.name,
                    onClick = { onNavigate(SettingsRoute(section.name)) },
                    indent = indent + 32.dp,
                    modifier = Modifier.heightIn(min = minRowHeight),
                )
            }
        }
    }
}

/** `bringIntoView()` is suspend; this keeps the LaunchedEffect body to one line. */
private suspend fun BringIntoViewRequester.bringIntoViewRequested() = bringIntoView()

/**
 * The destination a problem report is filed from, as the stable [AdminNavItem] name (plus the
 * settings section) rather than the localized label: whoever reads the issue should not have to
 * know which language the operator had chosen. Null on a page that is not a sidebar destination,
 * such as a document's detail page.
 */
internal fun reportScreenName(
    currentDestination: NavDestination?,
    currentSection: String?,
): String? {
    val item = AdminNavItem.entries.firstOrNull { currentDestination?.hasRoute(it.route::class) == true } ?: return null
    return if (item == AdminNavItem.SETTINGS && currentSection != null) "${item.name}/$currentSection" else item.name
}
