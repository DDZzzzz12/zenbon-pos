package org.example.project.admin.core.presentation.layout

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Widest a label/value panel or a form grows before it stops stretching (SYS-23). */
val ADMIN_PANEL_MAX_WIDTH: Dp = 720.dp

/** Widest a list screen's content grows; wider than a panel because a table earns the columns. */
val ADMIN_LIST_MAX_WIDTH: Dp = 1160.dp

/**
 * Caps a label/value panel or form at [ADMIN_PANEL_MAX_WIDTH]. A maximum only: below it the modifier
 * changes nothing, so the phone layout is untouched. The caller centres the bounded child.
 */
fun Modifier.adminPanelMaxWidth(): Modifier = widthIn(max = ADMIN_PANEL_MAX_WIDTH)

/** Caps a list screen's content at [ADMIN_LIST_MAX_WIDTH]; same maximum-only contract as [adminPanelMaxWidth]. */
fun Modifier.adminListMaxWidth(): Modifier = widthIn(max = ADMIN_LIST_MAX_WIDTH)
