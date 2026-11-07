package org.example.project.ui.components.filter

import androidx.compose.runtime.compositionLocalOf

/**
 * The host's [PosFilterPanelStore], or null (the default) when it has none: the till and tests then
 * keep a screen-level `remember`, exactly as before the store existed.
 */
val LocalPosFilterPanelStore = compositionLocalOf<PosFilterPanelStore?> { null }
