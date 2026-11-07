package org.example.project.ui.components.employee

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.localizedEmployeeRoleName
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/**
 * Employee list row card: avatar-initials circle (EmployeeColors), bold name (HR name, else username),
 * `#employeeNumber` subtitle, role badge Surface, green/red status dot, and an
 * optional trailing slot (e.g. a per-row action menu) next to the status dot.
 *
 * Extracted from composeApp's `PosEmployeeScreen.kt`. Preserves the original
 * visual contract: border-based selection indicator (primary when selected,
 * outlineVariant otherwise), `#` prefix on the employee number, and a red
 * inactive status dot. The avatar uses [EmployeeColors] keyed off the employee
 * number, with the dark/light palette selected from the active theme so dark
 * mode keeps working.
 */
@Composable
fun EmployeeCard(
    employee: EmployeeDto.EmployeeSummaryResponseDto,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val borderColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }

    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colorPair =
        EmployeeColors.getColorPair(
            employeeNumber = employee.employeeNumber,
            isDark = isDark,
            fallback = MaterialTheme.colorScheme.primary,
        )

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border =
            BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            ) {
                // Avatar circle with initials
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colorPair.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            employee
                                .shownName()
                                .firstOrNull()
                                ?.uppercaseChar()
                                ?.toString() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorPair.border,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Column {
                    // Name (bold): the HR name when the server sent one, else the login name
                    Text(
                        text = employee.shownName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                    ) {
                        // Employee number
                        Text(
                            text = "#${employee.employeeNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Role badge
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = localizedEmployeeRoleName(employee.role, LocalAppLanguage.current),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Colour alone must not carry the state: inactive staff also get a text chip.
                if (!employee.isActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = str(SettingsKeys.inactive),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                        )
                    }
                }
                // Status dot (green = active, red = inactive)
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (employee.isActive) {
                                    LocalPosColors.current.posAvailable
                                } else {
                                    LocalPosColors.current.posError
                                },
                            ),
                )
                trailing?.invoke()
            }
        }
    }
}
