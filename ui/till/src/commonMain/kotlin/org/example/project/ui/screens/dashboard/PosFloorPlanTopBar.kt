package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

@Composable
fun PosFloorPlanTopBar(
    occupiedCount: Int,
    selectedMode: Int,
    locationTypes: List<LocationTypeDto.ResponseDto>,
    onModeSelected: (Int) -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalLanguageManager.current.currentLanguage.value
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val occupiedColor = PosTheme.colors.posOccupied

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp)
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height - strokeWidth),
                        end = Offset(size.width, size.height - strokeWidth),
                        strokeWidth = strokeWidth,
                    )
                }.padding(horizontal = PosSpacing.xl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: title + occupied badge
        Row(
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(DashboardKeys.floorPlanTitle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Surface(
                color = occupiedColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = PosSpacing.md, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "\u25CF",
                        color = occupiedColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "$occupiedCount ${str(DashboardKeys.occupiedLabel)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = occupiedColor,
                    )
                }
            }
        }

        // Right side: next-employee chip + segmented toggle + refresh
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotationNextChip()

            PosSegmentedButtonRow(
                options = locationTypes.indices.toList(),
                selectedOption = selectedMode,
                onOptionSelected = onModeSelected,
            ) { index ->
                val locationType = locationTypes[index]
                when (language) {
                    Language.ENGLISH -> locationType.englishName
                    Language.CHINESE -> locationType.chineseName
                    else -> locationType.name
                }
            }

            PosIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = null,
                onClick = onRefreshClick,
            )
        }
    }
}
