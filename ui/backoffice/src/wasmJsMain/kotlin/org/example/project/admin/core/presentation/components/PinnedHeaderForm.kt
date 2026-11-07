package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A full-page form whose [header] (back arrow, title, primary Save/Issue action) stays pinned
 * while the form body scrolls.
 *
 * The header sits **outside** the scrolling region on purpose: as the first child of a scrolling
 * `Column` it scrolls out of view on a long form and takes the primary action with it.
 *
 * [content] is laid out in the single `verticalScroll` owned by this composable, so it must not
 * contain another vertical scroll container (a nested one measures to infinite height and crashes
 * the wasm layout pass).
 */
@Composable
fun PinnedHeaderForm(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().padding(padding)) {
        header()
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
