package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard

/**
 * What a dashboard section renders when its own load failed.
 *
 * A failed section must never render as empty and must never vanish: a zero that means "the call
 * was refused" is indistinguishable from a zero that means "you have no work", and a section that
 * disappears on failure tells the operator nothing at all. It stays, named, saying it does not know.
 *
 * Deliberately not routed through `PosErrorWatcher`. Six independent cards load here; a modal error
 * because one of them could not answer would make the landing screen unusable, and the setup
 * checklist already set this precedent by hiding itself rather than interrupting the dashboard.
 */
@Composable
fun SectionUnavailable(
    title: String,
    modifier: Modifier = Modifier,
) {
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = str(AdminStringKeys.DashboardSectionUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
