package org.example.project.core.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton

/**
 * "A new version is available" strip with a reload button. While [busy] it only offers the button;
 * as soon as the cashier is back on the dashboard root the page reloads by itself.
 */
@Composable
fun ServerUpdateBanner(
    watcher: ServerBuildWatcher,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val updateAvailable by watcher.updateAvailable.collectAsState()
    LaunchedEffect(updateAvailable, busy) {
        if (watcher.consumeAutoReload(busy)) PageReloader.reload()
    }
    if (!updateAvailable) return
    // raw Material 3 by design: an edge-to-edge strip, PosCard is a rounded elevated card
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str(CommonKeys.updateAvailable), color = MaterialTheme.colorScheme.onTertiaryContainer)
            PosButton(text = str(CommonKeys.reloadNow), onClick = PageReloader::reload, height = 36.dp)
        }
    }
}
