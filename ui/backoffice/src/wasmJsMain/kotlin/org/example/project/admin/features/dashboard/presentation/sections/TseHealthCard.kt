package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dashboard.presentation.components.DashboardStatusLine
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * Never hidden once it has loaded: "the TSE is fine" is worth one line, and a card that only appears
 * when something is wrong teaches operators that its absence means nothing was checked.
 *
 * @param refreshTick incremented by the screen's refresh button; the card reloads on every change.
 */
@Composable
fun TseHealthCard(
    refreshTick: Int,
    modifier: Modifier = Modifier,
    viewModel: TseHealthViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    val title = str(AdminStringKeys.DashboardTseTitle)

    val settled = rememberSectionSettled(state.isLoading, state.failed)
    if (state.isLoading && !settled) {
        SectionLoadingPlaceholder(modifier)
        return
    }
    if (state.failed) {
        SectionUnavailable(title = title, modifier = modifier)
        return
    }

    val posColors = LocalPosColors.current
    val statusColor = if (state.operational) posColors.posAvailable else posColors.posError
    val statusText =
        if (state.operational) {
            str(AdminStringKeys.DashboardTseOperational)
        } else {
            str(AdminStringKeys.DashboardTseDegraded)
        }
    val unsignedText =
        if (state.unsignedCount > 0) {
            " · " + str(AdminStringKeys.DashboardTseUnsigned, state.unsignedCount.toString())
        } else {
            ""
        }

    PosCard(modifier = modifier.fillMaxWidth(), variant = PosCardVariant.Plain) {
        body {
            val statusLine = @Composable { DashboardStatusLine(text = statusText + unsignedText, color = statusColor) }
            val icon =
                @Composable {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
            if (LocalPosLayout.current.isCompact) {
                // One row on a phone: icon, name, status.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon()
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    statusLine()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        statusLine()
                    }
                    icon()
                }
            }
        }
    }
}
