package org.example.project.ui.components.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str

private val MONTH_KEYS =
    arrayOf(
        EmployeeHubKeys.january,
        EmployeeHubKeys.february,
        EmployeeHubKeys.march,
        EmployeeHubKeys.april,
        EmployeeHubKeys.may,
        EmployeeHubKeys.june,
        EmployeeHubKeys.july,
        EmployeeHubKeys.august,
        EmployeeHubKeys.september,
        EmployeeHubKeys.october,
        EmployeeHubKeys.november,
        EmployeeHubKeys.december,
    )

@Composable
fun MonthStepper(
    year: Int,
    month: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val monthName = str(MONTH_KEYS.getOrElse(month - 1) { EmployeeHubKeys.january })
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // raw Material 3 by design: standalone icon buttons for month-stepper nav
        IconButton(onClick = onPrevMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, str(EmployeeHubKeys.previousMonth))
        }
        Text(
            text = "$monthName $year",
            style = MaterialTheme.typography.bodyMedium,
        )
        // raw Material 3 by design: standalone icon buttons for month-stepper nav
        IconButton(onClick = onNextMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, str(EmployeeHubKeys.nextMonth))
        }
    }
}
