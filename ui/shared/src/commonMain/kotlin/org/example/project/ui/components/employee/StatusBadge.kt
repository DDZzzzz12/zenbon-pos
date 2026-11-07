package org.example.project.ui.components.employee

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.PosSpacing

enum class ApprovalStatus { APPROVED, REJECTED, DRAFT }

@Composable
fun StatusBadge(
    status: ApprovalStatus,
    modifier: Modifier = Modifier,
) {
    val (bgColor, textColor, label) =
        when (status) {
            ApprovalStatus.APPROVED ->
                Triple(
                    Color(0xFFE8F5E9),
                    Color(0xFF2E7D32),
                    str(EmployeeHubKeys.approvedStatus),
                )
            ApprovalStatus.REJECTED ->
                Triple(
                    Color(0xFFFFEBEE),
                    Color(0xFFC62828),
                    str(EmployeeHubKeys.rejectedStatus),
                )
            ApprovalStatus.DRAFT ->
                Triple(
                    Color(0xFFF5F5F5),
                    Color(0xFF616161),
                    str(EmployeeHubKeys.draftStatus),
                )
        }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = bgColor,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
        )
    }
}
