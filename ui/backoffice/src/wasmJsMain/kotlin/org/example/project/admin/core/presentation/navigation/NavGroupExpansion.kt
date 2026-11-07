package org.example.project.admin.core.presentation.navigation

import androidx.compose.runtime.mutableStateMapOf
import org.example.project.core.data.database.KeyValueStore

/**
 * Which sidebar groups are expanded, and the rule for what happens when nobody has said.
 *
 * Extracted from [AdminSidebar] so the rule is testable without composing anything: the sidebar
 * renders, this decides.
 *
 * **The default is "only the group you are in".** The drawer holds 25 destinations, about 1200px
 * of rows in a ~660px scroll area, so expanding everything cannot fit and the last two groups fall
 * permanently below the fold — which is how Compliance and System became undiscoverable. Collapsed
 * by default trades a click for showing all seven headers ([AdminNavGroup.PINNED] renders none) at
 * once.
 *
 * [PINNED][AdminNavGroup.PINNED] has no header, so there is nothing to click and nothing to store.
 *
 * Holds Compose snapshot state ([expanded]), seeded from [store] once in `init` — **the instance
 * MUST be `remember`ed at the call site**. A per-recomposition instance would re-seed from
 * localStorage every frame, and worse, would never observe its own writes: a plain `KeyValueStore`
 * read invalidates no Compose state, so toggling a header would change localStorage while the
 * drawer stayed visually frozen.
 */
class NavGroupExpansion(
    private val store: KeyValueStore,
) {
    private val expanded = mutableStateMapOf<AdminNavGroup, Boolean>()

    init {
        AdminNavGroup.entries.forEach { group ->
            store.getString(keyFor(group))?.toBooleanStrictOrNull()?.let { expanded[group] = it }
        }
    }

    /**
     * The active group always reads as expanded regardless of what is stored. Navigating into a
     * group — from the dashboard, a deep link, or a redirect — must reveal where you landed;
     * otherwise the selected row is hidden inside a collapsed header.
     *
     * Because of that force-open, the active group's header must not be offered a toggle — the only
     * toggle expression a caller could write, `setExpanded(g, !isExpanded(g, active))`, would always
     * write `false` first and silently destroy a previously stored `true`. Use [isActiveGroup] to
     * decide whether to render a chevron at all, and [toggle] for the click handler on the groups
     * that get one.
     */
    fun isExpanded(
        group: AdminNavGroup,
        activeGroup: AdminNavGroup?,
    ): Boolean {
        if (group == AdminNavGroup.PINNED) return true
        if (group == activeGroup) return true
        return expanded[group] ?: false
    }

    /** Whether [group] is the one currently open — its header renders with no collapse control. */
    fun isActiveGroup(
        group: AdminNavGroup,
        activeGroup: AdminNavGroup?,
    ): Boolean = group == activeGroup

    /** Flips the stored value. Never call this for the active group — see [isExpanded]. */
    fun toggle(group: AdminNavGroup) {
        setExpanded(group, !(expanded[group] ?: false))
    }

    fun setExpanded(
        group: AdminNavGroup,
        expanded: Boolean,
    ) {
        if (group == AdminNavGroup.PINNED) return
        this.expanded[group] = expanded
        store.setString(keyFor(group), expanded.toString())
    }

    companion object {
        /** One key per group, matching the `admin.sidebar.collapsed` convention already in AdminApp. */
        fun keyFor(group: AdminNavGroup): String = "admin.sidebar.group.${group.name.lowercase()}"
    }
}
