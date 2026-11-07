package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem

/**
 * Per-row credential shortcut in the staff directory.
 *
 * The urgent task on this screen is resetting a forgotten PIN mid-shift. Reaching it through
 * the detail pane costs a selection, a detail fetch, a tab and a scroll; from here it costs
 * one menu.
 */
@Composable
fun EmployeeRowMenu(
    onResetPassword: () -> Unit,
    onResetPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // The Box is load-bearing: a DropdownMenu anchors to its parent layout node, so as a bare
    // sibling of the IconButton it becomes another child of the surrounding row and opens
    // detached from the button that triggered it.
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(adminTouchSize(32.dp))) {
            Icon(Icons.Default.MoreVert, contentDescription = str(AdminStringKeys.StaffSectionCredentials))
        }
        PosDropdown(expanded, { expanded = false }) {
            PosDropdownItem(text = str(AdminStringKeys.StaffResetPin), onClick = {
                onResetPin()
                expanded = false
            })
            PosDropdownItem(text = str(AdminStringKeys.StaffResetPassword), onClick = {
                onResetPassword()
                expanded = false
            })
        }
    }
}
