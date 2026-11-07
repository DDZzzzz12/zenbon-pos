package org.example.project.ui.components.employee

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str

/**
 * Colored chip showing a shift's time range `startTime – endTime` with an
 * optional break label. Extracted from composeApp's `ScheduleTab.kt`; the
 * original joined start/end times inline with a `Surface` chip. Break text is
 * shown only when [breakMinutes] is positive.
 */
@Composable
fun ShiftChip(
    startTime: String,
    endTime: String,
    breakMinutes: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$startTime – $endTime",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (breakMinutes > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = str(EmployeeHubKeys.breakDuration, breakMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}
