package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosCard

/** What a dashboard section shows during its very first load, so the page does not jump when it arrives. */
@Composable
fun SectionLoadingPlaceholder(modifier: Modifier = Modifier) {
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}
