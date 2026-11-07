package org.example.project.ui.components.employee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.EmployeeDto

@Composable
fun EmployeePickerGrid(
    employees: List<EmployeeDto.EmployeeInfoDto>,
    selectedEmployeeNumber: String?,
    onEmployeeClick: (EmployeeDto.EmployeeInfoDto) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    maxHeight: Dp = 240.dp,
    enabled: Boolean = true,
) {
    if (employees.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight),
    ) {
        items(employees, key = { it.employeeNumber }) { employee ->
            EmployeePickerTile(
                employee = employee,
                isSelected = selectedEmployeeNumber == employee.employeeNumber,
                enabled = enabled,
                onClick = { onEmployeeClick(employee) },
            )
        }
    }
}
