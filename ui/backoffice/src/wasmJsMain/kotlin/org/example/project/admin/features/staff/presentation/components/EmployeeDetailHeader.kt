package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.isCompact
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip

/**
 * Identity and the urgent actions, pinned above the tabs.
 *
 * Deliberately holds no edit affordance: editing lives next to the fields it edits, so an
 * operator never has to work out which of several "Bearbeiten" buttons owns which field. Two
 * buttons plus an overflow is also the entire width budget on a collapsed pane, which is why
 * the action row is a [FlowRow] — it wraps rather than clipping.
 *
 * [onBack] is the only route back to the directory once the scaffold collapses to a single
 * pane: at that width the list is not on screen, so without it an operator can reach an
 * employee but never a different one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmployeeDetailHeader(
    employee: EmployeeDto.EmployeeResponseDto,
    /** The HR name from the directory row; null shows the login name. */
    displayName: String?,
    canManage: Boolean,
    canResetCredentials: Boolean,
    canDeactivate: Boolean,
    onResetPassword: () -> Unit,
    onResetPin: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hrName: String? = null,
) {
    val language = LocalAppLanguage.current
    val compact = LocalWindowSizeClass.current.isCompact()
    // The loaded HR record wins; the directory row's name covers the moment before it arrives.
    val shownName = hrName ?: displayName?.takeIf { it.isNotBlank() }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Null on a wide window, where the directory is still visible beside this pane and
            // a back arrow would have nowhere to go.
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(CommonKeys.back),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    shownName ?: employee.username,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        employee.username.takeIf { shownName != null },
                        EmployeeRole.getRole(employee.role).getName(language),
                        employee.employeeNumber,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (employee.isActive) {
                PosStatusChip(
                    text = str(AdminStringKeys.StaffStatusActive),
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                PosStatusChip(
                    text = str(AdminStringKeys.StaffStatusInactive),
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Sits in the identity row rather than with the reset buttons: in the action FlowRow
            // it wrapped onto a line of its own on a narrow pane, and its menu then opened over
            // the tab row below. Top-right is also where an overflow is conventionally looked for.
            val showDeactivate = canManage && employee.isActive
            val showResets = canResetCredentials && compact
            if (showDeactivate || showResets) {
                OverflowMenu(
                    canDeactivate = canDeactivate,
                    showDeactivate = showDeactivate,
                    showResets = showResets,
                    onDeactivate = onDeactivate,
                    onResetPin = onResetPin,
                    onResetPassword = onResetPassword,
                )
            }
        }

        // On a phone the two resets live in the overflow menu instead of a button row.
        if (canResetCredentials && !compact) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosSecondaryButton(text = str(AdminStringKeys.StaffResetPin), onClick = onResetPin)
                PosSecondaryButton(text = str(AdminStringKeys.StaffResetPassword), onClick = onResetPassword)
            }
        }
        if (canManage) {
            if (employee.isActive && !canDeactivate) {
                Text(
                    str(AdminStringKeys.StaffCannotDeactivate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    canDeactivate: Boolean,
    showDeactivate: Boolean,
    showResets: Boolean,
    onDeactivate: () -> Unit,
    onResetPin: () -> Unit,
    onResetPassword: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // The Box is load-bearing: a DropdownMenu anchors to its parent layout node, so as a bare
    // sibling of the IconButton it becomes another child of the surrounding FlowRow, takes a
    // slot there, and opens detached from the button that triggered it.
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = str(CommonKeys.actions))
        }
        PosDropdown(expanded, { expanded = false }) {
            if (showResets) {
                PosDropdownItem(
                    text = str(AdminStringKeys.StaffResetPin),
                    onClick = {
                        onResetPin()
                        expanded = false
                    },
                )
                PosDropdownItem(
                    text = str(AdminStringKeys.StaffResetPassword),
                    onClick = {
                        onResetPassword()
                        expanded = false
                    },
                )
            }
            if (showDeactivate) {
                PosDropdownItem(
                    text = str(AdminStringKeys.StaffDeactivate),
                    enabled = canDeactivate,
                    onClick = {
                        onDeactivate()
                        expanded = false
                    },
                )
            }
        }
    }
}
